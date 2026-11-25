"use strict";
const fs = require("node:fs");
const path = require("node:path");
const {execFileSync} = require("node:child_process");
function createSeed(directory, publicKeyFile, id, setup, {preserveHostKey = false} = {}) {
  const publicKey = fs.readFileSync(publicKeyFile,"utf8").trim();
  if (!/^ssh-ed25519 [A-Za-z0-9+/=]+ galiais-local-vm$/.test(publicKey) || !/^[a-f0-9-]{36}$/.test(id)) throw new Error("Invalid VM seed identity");
  const policy = "PasswordAuthentication no\nKbdInteractiveAuthentication no\nPermitRootLogin no\nAllowUsers agent\nAllowAgentForwarding no\nX11Forwarding no\nPermitTunnel no\nGatewayPorts no\n";
  const consoleTty = process.arch === "arm64" ? "/dev/ttyAMA0" : "/dev/ttyS0";
  const service = "[Unit]\nDescription=GALIAIS Linux runtime bootstrap\nAfter=network-online.target remote-fs.target apparmor.service ssh.service cloud-final.service\nWants=network-online.target ssh.service\n[Service]\nType=oneshot\nExecStart=/usr/local/sbin/galiais-guest-setup " + id + "\nStandardOutput=tty\nStandardError=journal\nTTYPath=" + consoleTty + "\n[Install]\nWantedBy=multi-user.target\n";
  const data = {
    hostname:"galiais-linux",manage_etc_hosts:true,disable_root:true,ssh_pwauth:false,
    ssh_deletekeys:!preserveHostKey,ssh_genkeytypes:["ed25519"],ssh_quiet_keygen:true,
    users:[{name:"agent",uid:1000,lock_passwd:true,shell:"/bin/sh",sudo:null,
      ssh_authorized_keys:["no-agent-forwarding,no-X11-forwarding,no-pty " + publicKey]}],
    packages:["bubblewrap","socat","apparmor-profiles","apparmor-utils","git","libatomic1"],
    bootcmd:[
      ["dpkg","--configure","-a"],
      ["/bin/sh","-c","sed -i 's|http://ports.ubuntu.com/ubuntu-ports|http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports|g; s|http://archive.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true"]
    ],
    mounts:[["galiais-codex","/opt/galiais-codex","9p","ro,trans=virtio,version=9p2000.L,nofail","0","0"]],
    write_files:[
      {path:"/etc/ssh/sshd_config.d/00-galiais.conf",owner:"root:root",permissions:"0644",content:policy},
      {path:"/usr/local/sbin/galiais-guest-setup",owner:"root:root",permissions:"0700",content:fs.readFileSync(setup,"utf8")},
      {path:"/etc/systemd/system/galiais-bootstrap.service",owner:"root:root",permissions:"0644",content:service}
    ],
    runcmd:[["systemctl","daemon-reload"],["systemctl","enable","galiais-bootstrap.service"],["systemctl","start","--no-block","galiais-bootstrap.service"]],
  };
  fs.writeFileSync(path.join(directory,"user-data"),"#cloud-config\n" + JSON.stringify(data),{mode:0o600,flag:"wx"});
  fs.writeFileSync(path.join(directory,"meta-data"),JSON.stringify({"instance-id":"galiais-"+id,"local-hostname":"galiais-linux"}),{mode:0o600,flag:"wx"});
  execFileSync("/usr/bin/cloud-localds",[path.join(directory,"seed.iso"),path.join(directory,"user-data"),path.join(directory,"meta-data")],{stdio:"pipe"});
  fs.chmodSync(path.join(directory,"seed.iso"),0o600);
}
module.exports = {createSeed};
