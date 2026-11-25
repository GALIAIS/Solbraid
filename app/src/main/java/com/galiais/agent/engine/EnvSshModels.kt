package com.galiais.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/** 单条托管环境变量（支持全局或按项目生效、Keystore 加密 Secret 与独立开关）。 */
data class EnvVarEntry(
    val id: String,
    val key: String,
    val value: String = "",
    val secret: Boolean = false,
    val enabled: Boolean = true,
    val scope: String = "global", // "global" | "project:/abs/path"
    val note: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("key", key)
        .put("value", if (secret) "" else value)
        .put("secret", secret)
        .put("enabled", enabled)
        .put("scope", scope)
        .put("note", note)

    companion object {
        fun fromJson(obj: JSONObject) = EnvVarEntry(
            id = obj.getString("id"),
            key = obj.getString("key"),
            value = obj.optString("value", ""),
            secret = obj.optBoolean("secret", false),
            enabled = obj.optBoolean("enabled", true),
            scope = obj.optString("scope", "global"),
            note = obj.optString("note", ""),
        )
    }
}

data class ShellAlias(val name: String, val command: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("command", command)
    companion object {
        fun fromJson(obj: JSONObject) = ShellAlias(obj.getString("name"), obj.optString("command", ""))
    }
}

data class GitIdentityConfig(
    val userName: String = "",
    val userEmail: String = "",
    val defaultBranch: String = "",
    val autoCrlf: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("userName", userName)
        .put("userEmail", userEmail)
        .put("defaultBranch", defaultBranch)
        .put("autoCrlf", autoCrlf)

    companion object {
        fun fromJson(obj: JSONObject?) = if (obj == null) GitIdentityConfig() else GitIdentityConfig(
            userName = obj.optString("userName", ""),
            userEmail = obj.optString("userEmail", ""),
            defaultBranch = obj.optString("defaultBranch", ""),
            autoCrlf = obj.optString("autoCrlf", ""),
        )
    }
}

/** 完整开发环境配置：环境变量、加密 Secret、自定义 PATH、Shell 别名/启动片段与 Git 全局身份。 */
data class EnvironmentHubConfig(
    val vars: List<EnvVarEntry> = emptyList(),
    val extraPaths: List<String> = emptyList(),
    val aliases: List<ShellAlias> = emptyList(),
    val shellSnippet: String = "",
    val git: GitIdentityConfig = GitIdentityConfig(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("vars", JSONArray().apply { vars.forEach { put(it.toJson()) } })
        .put("extraPaths", JSONArray(extraPaths))
        .put("aliases", JSONArray().apply { aliases.forEach { put(it.toJson()) } })
        .put("shellSnippet", shellSnippet)
        .put("git", git.toJson())

    companion object {
        fun fromJson(raw: String?): EnvironmentHubConfig {
            if (raw.isNullOrBlank()) return EnvironmentHubConfig()
            return try {
                val obj = JSONObject(raw)
                EnvironmentHubConfig(
                    vars = obj.arr("vars").objects().mapNotNull { runCatching { EnvVarEntry.fromJson(it) }.getOrNull() },
                    extraPaths = obj.arr("extraPaths").strings(),
                    aliases = obj.arr("aliases").objects().mapNotNull { runCatching { ShellAlias.fromJson(it) }.getOrNull() },
                    shellSnippet = obj.optString("shellSnippet", ""),
                    git = GitIdentityConfig.fromJson(obj.optJSONObject("git")),
                )
            } catch (_: Exception) {
                EnvironmentHubConfig()
            }
        }
    }
}

data class RuntimeEnvEntry(val key: String, val value: String, val masked: Boolean)

data class ProjectDotEnvSnapshot(val exists: Boolean, val content: String, val revision: String)

data class EnvPresetTemplate(val title: String, val key: String, val defaultValue: String, val secret: Boolean, val note: String)

val curatedEnvPresets: List<EnvPresetTemplate> = listOf(
    EnvPresetTemplate("GitHub PAT", "GITHUB_TOKEN", "", true, "供 gh CLI、Git 与 MCP 访问 GitHub"),
    EnvPresetTemplate("Go 模块镜像", "GOPROXY", "https://goproxy.cn,direct", false, "国内加速 go get / go mod"),
    EnvPresetTemplate("Rust 工具链镜像", "RUSTUP_DIST_SERVER", "https://rsproxy.cn", false, "加速 rustup 与 crates.io"),
    EnvPresetTemplate("Node 内存上限", "NODE_OPTIONS", "--max-old-space-size=2048", false, "防止大型前端构建 OOM"),
    EnvPresetTemplate("数据库 URL", "DATABASE_URL", "", true, "后端开发与迁移脚本连接串"),
    EnvPresetTemplate("时区 TZ", "TZ", "Asia/Shanghai", false, "统一日志与容器时间为东八区"),
)

fun parseDotEnvLines(raw: String): List<Pair<String, String>> {
    require(raw.toByteArray(Charsets.UTF_8).size <= 1024 * 1024 && '\u0000' !in raw) { ".env 内容无效或超过 1 MB" }
    val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
    val result = linkedMapOf<String, String>()
    var offset = 0
    var line = 1
    fun skipSpaces() { while (offset < text.length && text[offset] in " \t") offset++ }
    fun skipLine() { while (offset < text.length && text[offset] != '\n') offset++ }
    while (offset < text.length) {
        skipSpaces()
        if (offset == text.length) break
        if (text[offset] == '#') skipLine()
        if (offset == text.length) break
        if (text[offset] == '\n') { offset++; line++; continue }
        val startLine = line
        if (text.startsWith("export", offset) && text.getOrNull(offset + 6) in listOf(' ', '\t')) {
            offset += 6
            skipSpaces()
        }
        val start = offset
        while (offset < text.length && text[offset] !in "= \t\n") offset++
        val key = text.substring(start, offset)
        skipSpaces()
        require(Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$").matches(key) && text.getOrNull(offset) == '=') { "第 $startLine 行：应为 KEY=VALUE" }
        offset++
        skipSpaces()
        val value = StringBuilder()
        val quote = text.getOrNull(offset)
        if (quote == '\'' || quote == '"') {
            offset++
            var closed = false
            while (offset < text.length) {
                val char = text[offset++]
                if (char == quote) { closed = true; break }
                if (char == '\\' && quote == '"' && offset < text.length) {
                    val escaped = text[offset++]
                    when (escaped) {
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        '\\', '"', '$' -> value.append(escaped)
                        else -> value.append('\\').append(escaped)
                    }
                    if (escaped == '\n') line++
                } else {
                    value.append(char)
                    if (char == '\n') line++
                }
            }
            require(closed) { "第 $startLine 行：引号未闭合" }
            skipSpaces()
            require(offset == text.length || text[offset] == '#' || text[offset] == '\n') { "第 $line 行：引号后只能包含注释" }
            if (text.getOrNull(offset) == '#') skipLine()
        } else {
            while (offset < text.length && text[offset] != '\n') {
                // 仅当 `#` 前接空白（如 `FOO=bar # note` 或 `EMPTY= # note`）时视作行尾注释，保留 `COLOR=#ff0000` 与 `#anchor`。
                if (text[offset] == '#' && offset > 0 && text[offset - 1] in " \t") break
                value.append(text[offset++])
            }
            if (text.getOrNull(offset) == '#') skipLine()
        }
        result[key] = if (quote == '\'' || quote == '"') value.toString() else value.toString().trimEnd()
    }
    return result.toList()
}

fun encodeDotEnvValue(value: String): String = "\"" + value
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

fun validateEnvEntry(entry: EnvVarEntry) {
    require(Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$").matches(entry.key)) { "环境变量名无效" }
    require(entry.key !in setOf("HOME", "USER", "LOGNAME", "SHELL", "PWD", "SHLVL", "GALIAIS_DELEGATE_SOCKET", "GALIAIS_DELEGATE_TOKEN")) { "${entry.key} 由运行环境管理，不能覆盖" }
    require(entry.scope == "global" || entry.scope.startsWith("project:/")) { "请选择有效的生效范围" }
    require('\u0000' !in entry.value && entry.scope.none { it in "\r\n\u0000" }) { "环境变量包含无效字符" }
}

// ── SSH 套件模型 ──────────────────────────────────────────────────────────────

data class SshKeyInfo(
    val name: String,
    val path: String,
    val publicKey: String,
    val fingerprint: String,
    val keyType: String,
    val bits: Int,
    val isDefault: Boolean,
) {
    companion object {
        fun fromJson(obj: JSONObject) = SshKeyInfo(
            name = obj.getString("name"),
            path = obj.optString("path", ""),
            publicKey = obj.optString("publicKey", ""),
            fingerprint = obj.optString("fingerprint", ""),
            keyType = obj.optString("keyType", "SSH"),
            bits = obj.optInt("bits", 0),
            isDefault = obj.optBoolean("isDefault", false),
        )
    }
}

data class SshKnownHost(val id: String, val hosts: String, val keyType: String, val fingerprint: String, val marker: String)

data class SftpTransfer(
    val id: String, val hostId: String, val direction: String, val localPath: String, val remotePath: String,
    val phase: String, val progressPercent: Int?, val progressLabel: String, val error: String?, val resultPath: String?,
) {
    val active get() = phase in setOf("running", "publishing", "cancelling")
    companion object {
        fun fromJson(value: JSONObject) = SftpTransfer(
            value.getString("id"), value.getString("hostId"), value.getString("direction"),
            value.optString("localPath"), value.optString("remotePath"), value.getString("phase"),
            value.optInt("progressPercent").takeUnless { value.isNull("progressPercent") },
            value.optString("progressLabel"), value.str("error"), value.str("resultPath"),
        )
    }
}

data class SftpTextDocument(val content: String, val revision: String)

data class SshHostConfig(
    val id: String,
    val alias: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    val authType: String = "key", // "key" | "password"
    val keyName: String = "id_ed25519",
    val proxyJump: String = "",
    val forwardAgent: Boolean = false,
    val keepAliveSec: Int = 30,
    val defaultPath: String = "",
    val startupCommand: String = "",
    val group: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("alias", alias)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("user", user)
        .put("authType", authType)
        .put("keyName", keyName)
        .put("proxyJump", proxyJump)
        .put("forwardAgent", forwardAgent)
        .put("keepAliveSec", keepAliveSec)
        .put("defaultPath", defaultPath)
        .put("startupCommand", startupCommand)
        .put("group", group)

    companion object {
        fun fromJson(obj: JSONObject) = SshHostConfig(
            id = obj.getString("id"),
            alias = obj.getString("alias"),
            name = obj.optString("name", obj.getString("alias")),
            host = obj.getString("host"),
            port = obj.optInt("port", 22),
            user = obj.optString("user", "root"),
            authType = obj.optString("authType", "key"),
            keyName = obj.optString("keyName", "id_ed25519"),
            proxyJump = obj.optString("proxyJump", ""),
            forwardAgent = obj.optBoolean("forwardAgent", false),
            keepAliveSec = obj.optInt("keepAliveSec", 30),
            defaultPath = obj.optString("defaultPath", ""),
            startupCommand = obj.optString("startupCommand", ""),
            group = obj.optString("group", ""),
        )
    }
}

data class SshHostTestResult(
    val ok: Boolean,
    val latencyMs: Int = 0,
    val remoteInfo: String = "",
    val error: String = "",
)

data class SshTunnelPreset(
    val id: String,
    val name: String,
    val hostId: String,
    val alias: String,
    val mode: String = "local", // "local" (-L) | "remote" (-R) | "dynamic" (-D)
    val localPort: Int = 8080,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int = 8080,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("hostId", hostId)
        .put("alias", alias)
        .put("mode", mode)
        .put("localPort", localPort)
        .put("remoteHost", remoteHost)
        .put("remotePort", remotePort)

    fun summary(): String = when (mode) {
        "remote" -> "-R $remotePort → $remoteHost:$localPort"
        "dynamic" -> "-D SOCKS5 127.0.0.1:$localPort"
        else -> "-L 127.0.0.1:$localPort → $remoteHost:$remotePort"
    }

    companion object {
        fun fromJson(obj: JSONObject) = SshTunnelPreset(
            id = obj.getString("id"),
            name = obj.optString("name", "隧道"),
            hostId = obj.getString("hostId"),
            alias = obj.optString("alias", ""),
            mode = obj.optString("mode", "local"),
            localPort = obj.optInt("localPort", 8080),
            remoteHost = obj.optString("remoteHost", "127.0.0.1"),
            remotePort = obj.optInt("remotePort", 8080),
        )
    }
}

data class SshActiveTunnel(
    val id: String,
    val hostId: String,
    val alias: String,
    val mode: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val status: String,
    val startedAt: Long,
    val error: String? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject) = SshActiveTunnel(
            id = obj.getString("id"),
            hostId = obj.optString("hostId", ""),
            alias = obj.optString("alias", ""),
            mode = obj.optString("mode", "local"),
            localPort = obj.optInt("localPort", 0),
            remoteHost = obj.optString("remoteHost", "127.0.0.1"),
            remotePort = obj.optInt("remotePort", 0),
            status = obj.optString("status", "running"),
            startedAt = obj.optLong("startedAt", System.currentTimeMillis()),
            error = obj.str("error"),
        )
    }
}

data class SshRemoteEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val permissions: String,
    val modified: String,
) {
    companion object {
        fun fromJson(obj: JSONObject) = SshRemoteEntry(
            name = obj.getString("name"),
            path = obj.getString("path"),
            directory = obj.optBoolean("directory", false),
            size = obj.optLong("size", 0L),
            permissions = obj.optString("permissions", ""),
            modified = obj.optString("modified", ""),
        )
    }
}

data class SshExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Int,
)
