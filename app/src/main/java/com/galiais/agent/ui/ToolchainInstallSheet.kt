package com.galiais.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ToolchainItem
import com.galiais.agent.engine.ToolchainSnapshot
import com.galiais.agent.engine.ToolchainVersionOption
import kotlinx.coroutines.launch

/** 严谨的编译器多版本选择、历史版本下载、安装摘要确认与版本切换/移除工作表。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolchainInstallSheet(
    app: GaliaisApplication,
    tc: ToolchainItem,
    tcSnap: ToolchainSnapshot,
    initialVersion: String = "",
    busy: Boolean,
    onInstall: (version: String, setDefault: Boolean, extraTargets: List<String>, aptComponents: List<String>) -> Unit,
    onUseVersion: (version: String) -> Unit,
    onUninstallVersion: (version: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<ToolchainVersionOption>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var filterText by remember { mutableStateOf("") }
    var selectedVersion by remember(initialVersion) { mutableStateOf(initialVersion) }
    var customVersion by remember(initialVersion) { mutableStateOf(initialVersion) }
    var setDefault by remember { mutableStateOf(true) }
    var pendingRemoveVer by remember { mutableStateOf<String?>(null) }

    val rustTargets = remember { mutableStateListOf<String>() }
    val androidExtras = remember(tcSnap.arch) {
        mutableStateListOf<String>().apply {
            add("build-tools")
            if (tcSnap.arch == "arm64") add("arm64-aapt2")
        }
    }
    val aptPkgs = remember { mutableStateListOf("build-essential", "clang", "cmake", "ninja-build", "pkg-config") }
    val javaReady = tcSnap.toolchains.any { it.id == "java" && it.installed }

    fun loadVersions(refresh: Boolean) {
        loading = true
        loadError = null
        scope.launch {
            try {
                val list = app.toolchains.fetchVersions(tc.id, refresh)
                versions = list
                if (selectedVersion.isBlank() && customVersion.isBlank()) {
                    selectedVersion = list.firstOrNull()?.version ?: ""
                }
            } catch (e: Exception) {
                loadError = e.message ?: "无法读取官方版本列表"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(tc.id) { loadVersions(false) }

    val targetVer = customVersion.trim().ifBlank { selectedVersion.trim() }
    val selectedOption = versions.firstOrNull { it.version == targetVer }
    val filtered = remember(versions, filterText) {
        val q = filterText.trim().lowercase()
        if (q.isBlank()) versions else versions.filter { it.version.lowercase().contains(q) || it.channel.lowercase().contains(q) }
    }

    GaliaisSheetScaffold(
        title = "${tc.name} · 版本与安装",
        subtitle = when (tc.id) {
            "android-sdk" -> "复用 ~/android-sdk，管理 cmdline-tools、API Platforms 与 ARM64 aapt2"
            "cpp-apt" -> "Ubuntu 系统级 APT 安装（需 Root 写权限）"
            else -> "免 Root 隔离托管于 ~/.galiais/toolchains/${tc.id}/versions/"
        },
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton(
                text = if (busy) "安装中…" else if (tc.id == "cpp-apt") "确认安装套件" else "安装 ${targetVer.ifBlank { "所选版本" }}",
                enabled = !busy && (tc.id == "cpp-apt" && tcSnap.aptWritable && aptPkgs.isNotEmpty() || tc.id != "cpp-apt" && targetVer.isNotBlank()),
                modifier = Modifier.weight(1f),
                onClick = {
                    val extras = when (tc.id) {
                        "rust" -> rustTargets.toList()
                        "android-sdk" -> androidExtras.toList()
                        else -> emptyList()
                    }
                    onInstall(targetVer, setDefault, extras, aptPkgs.toList())
                },
            )
        },
    ) {
        if (tc.requiresJdk && !javaReady) {
            CrispCard(background = G.warningTint, borderColor = G.warning.copy(alpha = 0.45f), padding = PaddingValues(10.dp)) {
                Text("${tc.name} 需要 Java。请先安装 OpenJDK 17 或 21。", style = MaterialTheme.typography.bodySmall, color = G.warning)
            }
        }
        if (tc.id == "dart") {
            CrispCard(background = G.surfaceSubtle, padding = PaddingValues(10.dp)) {
                Text("说明：ARM64 Linux 原生完整支持 Dart SDK；上游 Flutter 桌面 SDK 目前仅提供 x64 预编译包。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            }
        }
        // 已安装版本管理（切换默认 / 移除单版本）
        if (tc.installedVersions.isNotEmpty()) {
            Text("已安装版本（点击设为默认）", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            CrispCard(padding = PaddingValues(0.dp)) {
                tc.installedVersions.forEachIndexed { idx, ver ->
                    val isActive = ver == tc.activeVersion
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(ver, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = G.ink, modifier = Modifier.weight(1f))
                        if (isActive) {
                            TagPill("当前默认", fg = G.success, bg = G.successTint)
                        } else {
                            TagPill("设为默认", fg = G.accent, bg = G.accentContainer, onClick = { if (!busy) onUseVersion(ver) })
                        }
                        TagPill("移除", fg = G.danger, bg = G.dangerTint, onClick = { if (!busy) pendingRemoveVer = ver })
                    }
                    if (idx < tc.installedVersions.lastIndex) Hairline()
                }
            }
        }

        if (tc.id == "cpp-apt") {
            if (!tcSnap.aptWritable) {
                CrispCard(background = G.dangerTint.copy(alpha = 0.55f), padding = PaddingValues(10.dp)) {
                    Text("当前为免 Root 只读模式，无法写入 Ubuntu 系统目录。如需免 Root 编译 C/C++，请安装 Zig（内置 zig cc / zig c++）。", style = MaterialTheme.typography.bodySmall, color = G.danger)
                }
            }
            Text("选择要安装的构建组件", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("build-essential", "clang", "cmake", "ninja-build", "pkg-config", "gdb", "xz-utils").forEach { pkg ->
                    val checked = aptPkgs.contains(pkg)
                    SelectableChip(pkg, selected = checked, onClick = { if (checked) aptPkgs.remove(pkg) else aptPkgs.add(pkg) })
                }
            }
        } else {
            // 历史版本筛选与官方清单
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    label = { Text("筛选版本号或渠道（如 1.22 / LTS / 3.11）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                CompactActionButton(if (loading) "刷新中" else "刷新源", enabled = !loading, onClick = { loadVersions(true) })
            }

            if (loadError != null) {
                Text("版本列表加载失败：$loadError。可手动输入版本号。", style = MaterialTheme.typography.bodySmall, color = G.danger)
            }

            CrispCard(padding = PaddingValues(0.dp)) {
                if (loading && versions.isEmpty()) {
                    Text("正在获取版本列表…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.padding(12.dp))
                } else if (filtered.isEmpty()) {
                    Text("无匹配版本，可手动输入版本号。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.padding(12.dp))
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        filtered.take(24).forEachIndexed { idx, opt ->
                            val picked = targetVer == opt.version
                            val already = tc.installedVersions.contains(opt.version)
                            Row(
                                Modifier.fillMaxWidth().clickable { selectedVersion = opt.version; customVersion = "" }.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(opt.version, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, fontWeight = if (picked) FontWeight.Bold else FontWeight.Medium, color = if (picked) G.accent else G.ink, modifier = Modifier.weight(1f))
                                if (opt.channel.isNotBlank()) TagPill(opt.channel, fg = if (opt.channel.contains("LTS") || opt.channel == "stable") G.success else G.inkMuted, bg = if (opt.channel.contains("LTS") || opt.channel == "stable") G.successTint else G.surfaceSubtle)
                                if (already) TagPill("已装", fg = G.accent, bg = G.accentContainer)
                                if (opt.sizeBytes > 0) Text("${(opt.sizeBytes / 1048576L).coerceAtLeast(1)}MB", fontFamily = Mono, fontSize = 10.sp, color = G.inkMuted)
                            }
                            if (idx < filtered.take(24).lastIndex) Hairline()
                        }
                    }
                }
            }

            OutlinedTextField(
                value = customVersion,
                onValueChange = { customVersion = it.trim() },
                label = { Text("手动输入版本号") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )

            if (tc.id == "rust") {
                Text("其他交叉编译目标", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("wasm32-unknown-unknown", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl").forEach { tgt ->
                        val on = rustTargets.contains(tgt)
                        SelectableChip(tgt, selected = on, onClick = { if (on) rustTargets.remove(tgt) else rustTargets.add(tgt) })
                    }
                }
            }
            if (tc.id == "android-sdk") {
                Text("同步安装的 Android SDK 组件", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val opts = buildList {
                        add("build-tools" to "Build-Tools (${selectedOption?.buildToolsVersion?.ifBlank { "匹配版本" } ?: "匹配版本"})")
                        add("platform-tools" to "Platform-Tools")
                        if (tcSnap.arch == "arm64") add("arm64-aapt2" to "ARM64 原生 aapt2")
                        add("ndk;27.2.12479018" to "NDK 27 LTS")
                        add("cmake;3.31.5" to "CMake 3.31")
                    }
                    opts.forEach { (key, label) ->
                        val on = androidExtras.contains(key)
                        SelectableChip(label, selected = on, onClick = { if (on) androidExtras.remove(key) else androidExtras.add(key) })
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("安装后设为默认版本", style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(1f))
                Switch(checked = setDefault, onCheckedChange = { setDefault = it }, colors = SwitchDefaults.colors(checkedTrackColor = G.accent))
            }

            // 透明安装摘要（落盘路径 / 架构 / 完整性校验）
            CrispCard(background = G.surfaceSubtle, padding = PaddingValues(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("安装信息", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = G.ink)
                    val targetDir = if (tc.id == "android-sdk") "~/android-sdk/platforms/${targetVer.ifBlank { "<api>" }}" else "~/.galiais/toolchains/${tc.id}/versions/${targetVer.ifBlank { "<ver>" }}"
                    val verifyDesc = when {
                        selectedOption?.sha512?.isNotBlank() == true -> "SHA-512 强制校验 (${selectedOption.sha512.take(12)}…) + 可执行冒烟验证"
                        selectedOption?.sha256?.isNotBlank() == true -> "SHA-256 强制校验 (${selectedOption.sha256.take(12)}…) + 可执行冒烟验证"
                        selectedOption?.checksumUrl?.isNotBlank() == true -> "官方校验文件实时核验 + 可执行冒烟验证"
                        tc.id == "android-sdk" -> "使用 sdkmanager 安装所选组件"
                        else -> "按组件安装方式检查下载或安装结果"
                    }
                    Text(
                        "版本：${targetVer.ifBlank { "未选择" }} (${tcSnap.platform}/${tcSnap.arch})\n目录：$targetDir\n校验：$verifyDesc",
                        fontFamily = Mono, fontSize = 10.sp, lineHeight = 14.sp, color = G.inkMuted, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    pendingRemoveVer?.let { ver ->
        GaliaisConfirmDialog(
            title = "移除指定版本？",
            message = "确定删除 ${tc.name} $ver 吗？不影响其余已安装版本。",
            confirmText = "确认移除",
            danger = true,
            onConfirm = { pendingRemoveVer = null; onUninstallVersion(ver) },
            onDismiss = { pendingRemoveVer = null },
        )
    }
}
