#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/un.h>
#include <netinet/in.h>
#include <unistd.h>

enum { CREATE_SOCKET = 1, ROUTE_SOCKET = 2, BIND_SOCKET = 3, LISTEN_SOCKET = 4 };
enum { ON_CONNECT = 1, ON_SENDMMSG = 9, ON_SENDMSG = 10, ON_SENDTO = 11 };
struct request_header { uint32_t operation, length; };
struct routing_command { uint32_t command, network, uid, traffic; };
struct destination_info {
    int32_t error;
    uint32_t latency;
    union { struct sockaddr plain; struct sockaddr_in ipv4; struct sockaddr_in6 ipv6; } address;
};
struct routing_message { struct routing_command command; struct destination_info destination; };
_Static_assert(sizeof(struct routing_message) == 52, "Android 15 network protocol layout mismatch");

static int exchange(uint32_t operation, const void *payload, size_t length, int attached, int *received_fd) {
    const char *name = getenv("GALIAIS_NETWORK_SOCKET");
    const char *owner = getenv("GALIAIS_APP_UID");
    if (name == NULL || owner == NULL || strncmp(name, "galiais.network.", 16) != 0 || strlen(name) > 90) {
        errno = ENETDOWN; return -1;
    }
    char *end = NULL;
    unsigned long uid = strtoul(owner, &end, 10);
    if (end == owner || *end != '\0' || uid < 10000 || uid >= 100000) { errno = EINVAL; return -1; }
    int channel = (int)syscall(SYS_socket, AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (channel < 0) return -1;
    int result = -1, failure;
    struct timeval timeout = {.tv_sec = 3};
    if (setsockopt(channel, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0 ||
        setsockopt(channel, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) < 0) goto done;
    struct sockaddr_un endpoint = {.sun_family = AF_UNIX};
    memcpy(endpoint.sun_path + 1, name, strlen(name));
    if (syscall(SYS_connect, channel, &endpoint, offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name)) < 0) goto done;
    struct ucred peer = {0};
    socklen_t peer_size = sizeof(peer);
    if (getsockopt(channel, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) < 0) goto done;
    if (peer.uid != uid) { errno = EACCES; goto done; }
    struct request_header header = {.operation = operation, .length = (uint32_t)length};
    struct iovec body[2] = {{.iov_base = &header, .iov_len = sizeof(header)}, {.iov_base = (void *)payload, .iov_len = length}};
    union { struct cmsghdr alignment; unsigned char bytes[CMSG_SPACE(sizeof(int) * 2)]; } ancillary = {0};
    struct msghdr message = {.msg_iov = body, .msg_iovlen = 2};
    if (attached >= 0) {
        message.msg_control = ancillary.bytes;
        message.msg_controllen = CMSG_SPACE(sizeof(attached));
        struct cmsghdr *rights = CMSG_FIRSTHDR(&message);
        rights->cmsg_level = SOL_SOCKET; rights->cmsg_type = SCM_RIGHTS; rights->cmsg_len = CMSG_LEN(sizeof(attached));
        memcpy(CMSG_DATA(rights), &attached, sizeof(attached));
    }
    ssize_t sent = syscall(SYS_sendmsg, channel, &message, MSG_NOSIGNAL);
    if (sent < 0) goto done;
    if ((size_t)sent != sizeof(header) + length) { errno = EIO; goto done; }
    int32_t status = 0;
    struct iovec response = {.iov_base = &status, .iov_len = sizeof(status)};
    memset(ancillary.bytes, 0, sizeof(ancillary.bytes));
    struct msghdr reply = {.msg_iov = &response, .msg_iovlen = 1,
        .msg_control = ancillary.bytes, .msg_controllen = sizeof(ancillary.bytes)};
    ssize_t count = recvmsg(channel, &reply, MSG_WAITALL | MSG_CMSG_CLOEXEC);
    if (count < 0) goto done;
    int descriptor = -1, descriptors = 0;
    for (struct cmsghdr *item = CMSG_FIRSTHDR(&reply); item != NULL; item = CMSG_NXTHDR(&reply, item)) {
        if (item->cmsg_level != SOL_SOCKET || item->cmsg_type != SCM_RIGHTS || item->cmsg_len < CMSG_LEN(0)) continue;
        size_t bytes = item->cmsg_len - CMSG_LEN(0);
        for (size_t offset = 0; offset + sizeof(int) <= bytes; offset += sizeof(int)) {
            int fd;
            memcpy(&fd, CMSG_DATA(item) + offset, sizeof(fd));
            if (++descriptors == 1) descriptor = fd; else close(fd);
        }
    }
    if (count != sizeof(status) || (reply.msg_flags & MSG_CTRUNC) || status > 0 || status < -4095 ||
        descriptors != (received_fd != NULL && status == 0 ? 1 : 0)) {
        if (descriptor >= 0) close(descriptor);
        errno = EPROTO; goto done;
    }
    if (status != 0) { errno = -status; goto done; }
    if (received_fd != NULL) *received_fd = descriptor;
    result = 0;
done:
    failure = errno;
    close(channel);
    if (result != 0) errno = failure;
    return result;
}

int socket(int domain, int type, int protocol) {
    if (domain != AF_INET && domain != AF_INET6) return (int)syscall(SYS_socket, domain, type, protocol);
    int32_t arguments[3] = {domain, type, protocol};
    int fd = -1;
    if (exchange(CREATE_SOCKET, arguments, sizeof(arguments), -1, &fd) < 0) return -1;
    if (!(type & SOCK_CLOEXEC) && fcntl(fd, F_SETFD, 0) < 0) { int error = errno; close(fd); errno = error; return -1; }
    return fd;
}

static int route(int fd, const struct sockaddr *address, socklen_t length, uint32_t command) {
    int saved = errno;
    if (address == NULL || length < sizeof(sa_family_t) ||
        (address->sa_family != AF_INET && address->sa_family != AF_INET6)) return 0;
    int domain = 0;
    socklen_t size = sizeof(domain);
    if (getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &size) < 0 || domain != address->sa_family) { errno = saved; return 0; }
    size_t address_size = domain == AF_INET ? sizeof(struct sockaddr_in) : sizeof(struct sockaddr_in6);
    if (length < address_size) { errno = EINVAL; return -1; }
    struct routing_message message = {0};
    message.command.command = command;
    memcpy(&message.destination.address, address, address_size);
    int status = exchange(ROUTE_SOCKET, &message, sizeof(message), fd, NULL);
    if (status == 0) errno = saved;
    return status;
}

int connect(int fd, const struct sockaddr *address, socklen_t length) {
    if (route(fd, address, length, ON_CONNECT) < 0) return -1;
    return (int)syscall(SYS_connect, fd, address, length);
}
int bind(int fd, const struct sockaddr *address, socklen_t length) {
    if (address != NULL && length >= sizeof(sa_family_t) &&
        (address->sa_family == AF_INET || address->sa_family == AF_INET6)) {
        if (length != (address->sa_family == AF_INET ? sizeof(struct sockaddr_in) : sizeof(struct sockaddr_in6))) { errno = EINVAL; return -1; }
        return exchange(BIND_SOCKET, address, length, fd, NULL);
    }
    return (int)syscall(SYS_bind, fd, address, length);
}
int listen(int fd, int backlog) {
    int domain = 0;
    socklen_t size = sizeof(domain);
    if (getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &size) == 0 && (domain == AF_INET || domain == AF_INET6)) {
        int32_t queued = backlog;
        return exchange(LISTEN_SOCKET, &queued, sizeof(queued), fd, NULL);
    }
    return (int)syscall(SYS_listen, fd, backlog);
}
ssize_t sendto(int fd, const void *buffer, size_t length, int flags, const struct sockaddr *address, socklen_t address_length) {
    if (route(fd, address, address_length, ON_SENDTO) < 0) return -1;
    return (ssize_t)syscall(SYS_sendto, fd, buffer, length, flags, address, address_length);
}
ssize_t sendmsg(int fd, const struct msghdr *message, int flags) {
    if (message != NULL && route(fd, message->msg_name, message->msg_namelen, ON_SENDMSG) < 0) return -1;
    return (ssize_t)syscall(SYS_sendmsg, fd, message, flags);
}
int sendmmsg(int fd, struct mmsghdr *messages, unsigned count, int flags) {
    if (count != 0 && messages != NULL && route(fd, messages[0].msg_hdr.msg_name,
        messages[0].msg_hdr.msg_namelen, ON_SENDMMSG) < 0) return -1;
    return (int)syscall(SYS_sendmmsg, fd, messages, count, flags);
}
