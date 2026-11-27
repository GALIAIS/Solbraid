package com.galiais.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.AptPackageItem
import com.galiais.agent.engine.CacheStats
import com.galiais.agent.engine.ProjectScanResult
import com.galiais.agent.engine.ToolchainCmdResult
import com.galiais.agent.engine.ToolchainSnapshot
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectPathPickerCard(projectPaths: List<String>, selectedCwd: String, onSelect: (String) -> Unit) {
    CrispCard(padding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("当前项目目录", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                projectPaths.forEach { p ->
                    val short = p.substringAfterLast('/').ifBlank { p }
                    SelectableChip(short, selected = p == selectedCwd, onClick = { onSelect(p) })
                }
            }
            Text(selectedCwd, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ToolchainOutputCard(res: ToolchainCmdResult, onDismiss: () -> Unit) {
    CrispCard(
        background = if (res.ok) G.surfaceSubtle else G.dangerTint.copy(alpha = 0.55f),
        borderColor = if (res.ok) G.border else G.danger.copy(alpha = 0.5f),
        padding = PaddingValues(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill(if (res.ok) "通过 ${res.ms}ms" else "失败 (${res.exitCode})", fg = if (res.ok) G.success else G.danger, bg = if (res.ok) G.successTint else G.dangerTint)
                Text(res.command, fontFamily = Mono, fontSize = 11.sp, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TagPill("关闭", fg = G.inkMuted, bg = G.surface, onClick = onDismiss)
            }
            if (res.artifactPath.isNotBlank()) {
                Text("产物：${res.artifactPath} (${String.format("%.2f MB", res.artifactSize / 1048576.0)})", fontFamily = Mono, fontSize = 11.sp, color = G.success)
            }
            val text = listOf(res.stdout.trim(), res.stderr.trim()).filter { it.isNotBlank() }.joinToString("\n").takeLast(1400)
            if (text.isNotBlank()) {
                Column(Modifier.fillMaxWidth().clip(D.shape(8.dp)).background(G.surface).padding(8.dp)) {
                    Text(text, fontFamily = Mono, fontSize = 10.sp, lineHeight = 14.sp, color = G.ink)
                }
            }
        }
    }
}

/** Tab 3: Ubuntu 24.04 系统级 APT / DPKG 包管理器（权限透明、安装卸载需确认）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolchainAptTab(
    app: GaliaisApplication,
    runtimeReady: Boolean,
    aptWritable: Boolean,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    packages: List<AptPackageItem>,
    lastOutput: ToolchainCmdResult?,
    onOutput: (ToolchainCmdResult?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf("installed") }
    var pendingConfirm by remember { mutableStateOf<Pair<String, String>?>(null) } // action to pkgName

    val curatedLibs = listOf("build-essential", "cmake", "clang", "ninja-build", "pkg-config", "xz-utils", "libssl-dev", "sqlite3", "ffmpeg", "jq")

    CrispCard(padding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ubuntu APT 权限", style = MaterialTheme.typography.labelMedium, color = G.inkMuted, modifier = Modifier.weight(1f))
                TagPill(
                    text = if (aptWritable) "可读写 (Root)" else "只读查询 (免 Root)",
                    fg = if (aptWritable) G.success else G.inkMuted,
                    bg = if (aptWritable) G.successTint else G.surfaceSubtle,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("过滤已装包或搜索软件源") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                CompactActionButton("已装", primary = searchMode == "installed", enabled = runtimeReady && !busy, onClick = {
                    searchMode = "installed"
                    scope.launch { runCatching { app.toolchains.queryApt("list", query.trim()) }.onFailure { app.conversations.notify("查询失败：${it.message}") } }
                })
                CompactActionButton("搜源", primary = searchMode == "repo", enabled = runtimeReady && !busy && query.isNotBlank(), onClick = {
                    searchMode = "repo"
                    scope.launch { runCatching { app.toolchains.queryApt("search", query.trim()) }.onFailure { app.conversations.notify("搜索失败：${it.message}") } }
                })
            }
            if (aptWritable) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagPill("更新索引", fg = G.accent, bg = G.accentContainer, onClick = {
                        if (!busy && runtimeReady) {
                            onBusyChange(true)
                            scope.launch {
                                try { onOutput(app.toolchains.mutateApt("update")) }
                                catch (e: Exception) { app.conversations.notify("更新索引失败：${e.message}") }
                                finally { onBusyChange(false) }
                            }
                        }
                    })
                    curatedLibs.forEach { pkg ->
                        TagPill(pkg, fg = G.ink, bg = G.surfaceSubtle, onClick = { if (!busy && runtimeReady) pendingConfirm = "install" to pkg })
                    }
                }
            }
        }
    }

    lastOutput?.let { ToolchainOutputCard(it, onDismiss = { onOutput(null) }) }

    CrispCard(padding = PaddingValues(0.dp)) {
        if (packages.isEmpty()) {
            Text("输入关键词点击「已装」或「搜源」查看系统软件包。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.padding(14.dp))
        } else {
            packages.take(60).forEachIndexed { idx, p ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(p.name, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = G.ink)
                            if (p.version.isNotBlank()) Text(p.version.take(24), fontFamily = Mono, fontSize = 10.sp, color = G.inkMuted)
                        }
                        if (p.summary.isNotBlank()) {
                            Text(p.summary, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (aptWritable) {
                        if (p.installed) {
                            TagPill("卸载", fg = G.danger, bg = G.dangerTint, onClick = { if (!busy && runtimeReady) pendingConfirm = "remove" to p.name })
                        } else {
                            TagPill("安装", fg = G.onAccent, bg = G.accent, onClick = { if (!busy && runtimeReady) pendingConfirm = "install" to p.name })
                        }
                    }
                }
                if (idx < packages.take(60).lastIndex) Hairline()
            }
        }
    }

    pendingConfirm?.let { (act, pkg) ->
        GaliaisConfirmDialog(
            title = if (act == "install") "安装系统包 $pkg？" else "卸载系统包 $pkg？",
            message = if (act == "install") "将通过 apt-get install -y --no-install-recommends $pkg 写入 Ubuntu 系统目录。" else "将通过 apt-get remove -y $pkg 移除该系统包。",
            confirmText = if (act == "install") "确认安装" else "确认卸载",
            danger = act == "remove",
            onConfirm = {
                pendingConfirm = null
                onBusyChange(true)
                scope.launch {
                    try {
                        onOutput(app.toolchains.mutateApt(act, listOf(pkg)))
                        runCatching { app.toolchains.queryApt("list", query.trim()) }
                    } catch (e: Exception) {
                        app.conversations.notify("操作失败：${e.message}")
                    } finally { onBusyChange(false) }
                }
            },
            onDismiss = { pendingConfirm = null },
        )
    }
}

/** Tab 4: 本地/交叉编译工坊 + 软件源配置 + 缓存清理。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolchainBuildAndMirrorTab(
    app: GaliaisApplication,
    runtimeReady: Boolean,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    projectPaths: List<String>,
    selectedCwd: String,
    onSelectCwd: (String) -> Unit,
    tcSnap: ToolchainSnapshot,
    projectScan: ProjectScanResult?,
    cacheStats: CacheStats,
    lastOutput: ToolchainCmdResult?,
    onOutput: (ToolchainCmdResult?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var crossOs by remember { mutableStateOf("") }
    var crossArch by remember { mutableStateOf("amd64") }
    var singleFile by remember { mutableStateOf("") }

    ProjectPathPickerCard(projectPaths, selectedCwd, onSelect = onSelectCwd)

    SectionHeader("构建与交叉编译")
    CrispCard(padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("目标架构（Go / Zig 交叉编译生效；留空使用本机架构）", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "本机架构", "linux" to "Linux", "windows" to "Windows", "darwin" to "macOS").forEach { (id, label) ->
                    SelectableChip(label, selected = crossOs == id, onClick = { crossOs = id })
                }
                if (crossOs.isNotBlank()) {
                    SelectableChip("amd64", selected = crossArch == "amd64", onClick = { crossArch = "amd64" })
                    SelectableChip("arm64", selected = crossArch == "arm64", onClick = { crossArch = "arm64" })
                }
            }
            val targets = projectScan?.compileTargets.orEmpty()
            if (targets.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    targets.forEach { t ->
                        TagPill(t.label, fg = G.onAccent, bg = G.accent, onClick = {
                            if (!busy && runtimeReady) {
                                onBusyChange(true)
                                scope.launch {
                                    try {
                                        onOutput(app.toolchains.runCompile(
                                            cwd = t.cwd, ecosystem = t.ecosystem, command = t.command, args = t.args,
                                            targetOs = if (t.crossCompile) crossOs else "",
                                            targetArch = if (t.crossCompile && crossOs.isNotBlank()) crossArch else "",
                                        ))
                                    } catch (e: Exception) { app.conversations.notify("编译失败：${e.message}") }
                                    finally { onBusyChange(false) }
                                }
                            }
                        })
                    }
                }
            }
            Hairline()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = singleFile,
                    onValueChange = { singleFile = it },
                    label = { Text("单文件编译（如 main.c / app.go / main.rs / Main.kt / main.dart）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                CompactActionButton("编译", primary = true, enabled = runtimeReady && !busy && singleFile.isNotBlank(), onClick = {
                    onBusyChange(true)
                    scope.launch {
                        try { onOutput(app.toolchains.runCompile(cwd = selectedCwd, file = singleFile.trim(), targetOs = crossOs, targetArch = if (crossOs.isNotBlank()) crossArch else "")) }
                        catch (e: Exception) { app.conversations.notify("编译失败：${e.message}") }
                        finally { onBusyChange(false) }
                    }
                })
            }
        }
    }

    lastOutput?.let { ToolchainOutputCard(it, onDismiss = { onOutput(null) }) }

    SectionHeader("软件源配置")
    CrispCard(padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedPillBar(
                options = listOf("cn" to "中国大陆镜像源", "official" to "全球官方直连"),
                selected = tcSnap.mirrorPreset,
                onSelect = { preset ->
                    if (!busy && runtimeReady) {
                        onBusyChange(true)
                        scope.launch {
                            try { app.toolchains.applyMirrorPreset(preset) }
                            catch (e: Exception) { app.conversations.notify("切换失败：${e.message}") }
                            finally { onBusyChange(false) }
                        }
                    }
                },
            )
            Text(
                "npm: ${tcSnap.npmRegistry.ifBlank { "官方默认" }}\nPyPI/uv: ${tcSnap.pypiIndexUrl.ifBlank { "官方默认" }}\nGOPROXY: ${tcSnap.goProxy.ifBlank { "默认" }}",
                fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted,
            )
        }
    }

    SectionHeader("缓存占用 (${String.format("%.1f MB", cacheStats.totalBytes / 1048576.0)})") {
        CompactActionButton("清理非 Gradle 缓存", enabled = runtimeReady && !busy, onClick = {
            onBusyChange(true)
            scope.launch {
                try { app.toolchains.cleanCaches() }
                catch (e: Exception) { app.conversations.notify("清理失败：${e.message}") }
                finally { onBusyChange(false) }
            }
        })
    }
    CrispCard(padding = PaddingValues(0.dp)) {
        cacheStats.entries.forEachIndexed { idx, c ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(c.label, style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(1f))
                Text(String.format("%.1f MB", c.bytes / 1048576.0), fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                if (c.bytes > 0L) {
                    TagPill("清理", fg = G.danger, bg = G.dangerTint, onClick = {
                        if (!busy && runtimeReady) {
                            onBusyChange(true)
                            scope.launch {
                                try { app.toolchains.cleanCaches(listOf(c.id)) }
                                catch (e: Exception) { app.conversations.notify("清理失败：${e.message}") }
                                finally { onBusyChange(false) }
                            }
                        }
                    })
                }
            }
            if (idx < cacheStats.entries.lastIndex) Hairline()
        }
    }
}
