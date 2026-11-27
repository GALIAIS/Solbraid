package com.galiais.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ProjectDepEcosystem
import com.galiais.agent.engine.ToolchainCmdResult
import com.galiais.agent.engine.ToolchainItem
import kotlinx.coroutines.launch

/** 编译器多版本管理、项目依赖、Ubuntu 系统包与编译工坊。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolchainStudioScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val tcSnap by app.toolchains.snapshot.collectAsState()
    val progress by app.toolchains.progress.collectAsState()
    val logLines by app.toolchains.logLines.collectAsState()
    val projectScan by app.toolchains.projectScan.collectAsState()
    val aptPackages by app.toolchains.aptPackages.collectAsState()
    val cacheStats by app.toolchains.cacheStats.collectAsState()
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf("compilers") } // "compilers" | "deps" | "apt" | "build"
    var categoryFilter by rememberSaveable { mutableStateOf("all") } // "all" | "compiler" | "sdk"
    var busyOp by remember { mutableStateOf(false) }
    var lastOutput by remember { mutableStateOf<ToolchainCmdResult?>(null) }
    var manageTarget by remember { mutableStateOf<Pair<ToolchainItem, String>?>(null) } // item to preselectedVersion
    var addDepEco by remember { mutableStateOf<ProjectDepEcosystem?>(null) }

    val projectPaths = remember(settings.projects, settings.defaultCwd) {
        (listOf(settings.defaultCwd) + settings.projects).filter { it.isNotBlank() }.distinct()
    }
    var selectedCwd by rememberSaveable(projectPaths) { mutableStateOf(projectPaths.firstOrNull() ?: "/home/agent/workspace") }

    LaunchedEffect(runtime.ready) {
        if (runtime.ready) {
            runCatching { app.toolchains.refreshToolchains() }
            runCatching { app.toolchains.scanProject(selectedCwd) }
            runCatching { app.toolchains.refreshCaches() }
        }
    }

    LaunchedEffect(section, selectedCwd, runtime.ready) {
        if (!runtime.ready) return@LaunchedEffect
        if (section == "deps" || section == "build") runCatching { app.toolchains.scanProject(selectedCwd) }
        if (section == "apt" && aptPackages.isEmpty()) runCatching { app.toolchains.queryApt("list", "") }
        if (section == "build") runCatching { app.toolchains.refreshCaches() }
    }

    val installedCount = tcSnap.toolchains.count { it.installed }
    val javaReady = tcSnap.toolchains.any { it.id == "java" && it.installed }
    val sdkCount = tcSnap.toolchains.count { it.category == "sdk" }
    val compilerCount = tcSnap.toolchains.size - sdkCount
    val visibleToolchains = remember(tcSnap.toolchains, categoryFilter) {
        when (categoryFilter) {
            "compiler" -> tcSnap.toolchains.filter { it.category != "sdk" }
            "sdk" -> tcSnap.toolchains.filter { it.category == "sdk" }
            else -> tcSnap.toolchains
        }
    }

    SubPageScaffold(
        title = "工具链中心",
        onBack = onBack,
        snackbar = snackbar,
        loading = busyOp,
        scrollable = false,
        contentPadding = PaddingValues(0.dp),
        actions = {
            if (runtime.ready) {
                TagPill(
                    text = "刷新",
                    fg = G.accent,
                    bg = G.accentContainer,
                    onClick = {
                        scope.launch {
                            runCatching { app.toolchains.refreshToolchains() }
                            if (section == "deps" || section == "build") runCatching { app.toolchains.scanProject(selectedCwd) }
                            if (section == "build") runCatching { app.toolchains.refreshCaches() }
                        }
                    },
                )
            }
        },
    ) {
        SegmentedPillBar(
            options = listOf(
                "compilers" to "工具链 ($installedCount)",
                "deps" to "项目依赖",
                "apt" to "系统包",
                "build" to "编译与源",
            ),
            selected = section,
            onSelect = { section = it },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(D.space(10.dp)),
        ) {
            if (!runtime.ready) {
                CrispCard(background = G.surfaceSubtle, padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("运行环境未启动，启动后可管理多版本编译器、项目依赖与系统包。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.weight(1f))
                        if (!runtime.busy && runtime.bound) TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                    }
                }
            }

            // 实时进度与中止控制
            progress?.let { p ->
                CrispCard(background = G.accentContainer.copy(alpha = 0.4f), borderColor = G.accent.copy(alpha = 0.35f), padding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(p.message.ifBlank { p.stage }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = G.ink, modifier = Modifier.weight(1f))
                            Text("${p.percent.coerceIn(0, 100)}%", fontFamily = Mono, style = MaterialTheme.typography.labelSmall, color = G.accent)
                            if (busyOp) {
                                TagPill("终止", fg = G.danger, bg = G.dangerTint, onClick = { scope.launch { app.toolchains.cancelActive() } })
                            } else {
                                TagPill("收起", fg = G.inkMuted, bg = G.surface, onClick = { app.toolchains.clearProgress() })
                            }
                        }
                        LinearProgressIndicator(
                            progress = { p.percent.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = G.accent,
                            trackColor = G.surface,
                        )
                        if (logLines.isNotEmpty()) {
                            Column(Modifier.fillMaxWidth().clip(D.shape(8.dp)).background(G.surfaceSubtle).padding(8.dp)) {
                                Text(logLines.takeLast(6).joinToString("\n"), fontFamily = Mono, fontSize = 10.sp, lineHeight = 14.sp, color = G.inkMuted)
                            }
                        }
                    }
                }
            }

            when (section) {
                "compilers" -> {
                    CrispCard(background = G.surfaceSubtle, padding = PaddingValues(12.dp)) {
                        Text(
                            "统一管理编译器、运行时与平台 SDK，支持选择官方稳定版与历史版本并存，经 SHA-256 / SHA-512 校验与冒烟自检后原子切换并注入全局环境。",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.inkMuted,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectableChip("全部 (${tcSnap.toolchains.size})", selected = categoryFilter == "all", onClick = { categoryFilter = "all" })
                        SelectableChip("编译器与运行时 ($compilerCount)", selected = categoryFilter == "compiler", onClick = { categoryFilter = "compiler" })
                        SelectableChip("SDK 与构建 ($sdkCount)", selected = categoryFilter == "sdk", onClick = { categoryFilter = "sdk" })
                    }
                    CrispCard(padding = PaddingValues(0.dp)) {
                        visibleToolchains.forEachIndexed { idx, tc ->
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(tc.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (tc.category == "sdk") TagPill("SDK", fg = G.artifact, bg = G.artifactTint)
                                    else if (tc.rootless) TagPill("免 Root", fg = G.success, bg = G.successTint)
                                    if (tc.requiresJdk && !javaReady && !tc.installed) TagPill("需 JDK", fg = G.warning, bg = G.warningTint)
                                    TagPill(
                                        text = if (tc.installed) (tc.activeVersion.ifBlank { "已就绪" }) else "未安装",
                                        fg = if (tc.installed) G.success else G.inkMuted,
                                        bg = if (tc.installed) G.successTint else G.surfaceSubtle,
                                    )
                                }
                                Text(tc.summary, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                if (tc.installed && tc.version.isNotBlank()) {
                                    Text(
                                        buildString {
                                            append(tc.version)
                                            if (tc.diskBytes > 0) append(" · ${String.format("%.1f MB", tc.diskBytes / 1048576.0)}")
                                        },
                                        fontFamily = Mono, fontSize = 11.sp, color = G.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // 多版本快捷切换芯片
                                if (tc.installedVersions.size > 1) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        tc.installedVersions.forEach { ver ->
                                            SelectableChip(ver, selected = ver == tc.activeVersion, onClick = {
                                                if (!busyOp && ver != tc.activeVersion && runtime.ready) {
                                                    busyOp = true
                                                    scope.launch {
                                                        try { app.toolchains.useVersion(tc.id, ver) }
                                                        catch (e: Exception) { app.conversations.notify("切换失败：${e.message}") }
                                                        finally { busyOp = false }
                                                    }
                                                }
                                            })
                                        }
                                    }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TagPill(
                                        text = if (tc.installed) "版本管理" else "选择版本并安装",
                                        fg = if (tc.installed) G.accent else G.onAccent,
                                        bg = if (tc.installed) G.accentContainer else G.accent,
                                        onClick = { if (runtime.ready) manageTarget = tc to "" },
                                    )
                                }
                            }
                            if (idx < visibleToolchains.lastIndex) Hairline()
                        }
                    }
                }

                "deps" -> {
                    ProjectPathPickerCard(projectPaths, selectedCwd, onSelect = { selectedCwd = it })
                    // 项目版本约束体检提示（.tool-versions / .nvmrc / .python-version / go.mod / rust-toolchain.toml）
                    val hints = projectScan?.versionHints.orEmpty()
                    if (hints.isNotEmpty()) {
                        CrispCard(background = G.surfaceSubtle, padding = PaddingValues(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("项目声明版本约束", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = G.ink)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    hints.forEach { h ->
                                        val tc = tcSnap.toolchains.find { it.id == h.toolchainId }
                                        val cleanReq = h.required.replace(Regex("[^0-9A-Za-z._-]"), "")
                                        TagPill(
                                            text = "${h.toolchainId} ${h.required} (${h.source})",
                                            fg = G.accent, bg = G.accentContainer,
                                            onClick = { if (tc != null && runtime.ready) manageTarget = tc to cleanReq },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val ecos = projectScan?.ecosystems.orEmpty()
                    if (ecos.isEmpty()) {
                        CrispCard(background = G.surfaceSubtle) {
                            Text("当前目录未检测到 package.json / go.mod / Cargo.toml / pyproject.toml / requirements.txt / build.gradle / pom.xml / pubspec.yaml / *.csproj / CMakeLists.txt / build.zig。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    } else {
                        ecos.forEach { eco ->
                            val reqTc = tcSnap.toolchains.find { it.id == eco.requiredToolchain }
                            CrispCard(padding = PaddingValues(14.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(eco.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink, modifier = Modifier.weight(1f))
                                        TagPill(eco.manifest, fg = G.inkMuted, bg = G.surfaceSubtle)
                                        if (eco.lockfile.isNotBlank()) TagPill(eco.lockfile, fg = G.success, bg = G.successTint)
                                        TagPill("${eco.depCount} 项", fg = G.accent, bg = G.accentContainer)
                                    }
                                    if (eco.ecosystem == "python") {
                                        Text(
                                            if (eco.envReady) "虚拟环境：已检测到隔离 .venv" else "虚拟环境：同步依赖时将自动创建项目隔离 .venv（兼容 PEP 668）",
                                            style = MaterialTheme.typography.labelSmall, color = G.inkMuted,
                                        )
                                    }
                                    if (reqTc != null && !reqTc.installed) {
                                        TagPill("缺少 ${reqTc.name} · 选择版本安装", fg = G.danger, bg = G.dangerTint, onClick = {
                                            if (runtime.ready) manageTarget = reqTc to ""
                                        })
                                    }
                                    if (eco.ecosystem in setOf("node", "go", "rust", "python", "maven", "dart", "dotnet")) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TagPill("同步依赖", fg = G.onAccent, bg = G.accent, onClick = {
                                                if (!busyOp && runtime.ready) {
                                                    busyOp = true
                                                    scope.launch {
                                                        try { lastOutput = app.toolchains.runProjectDepAction(eco.cwd, eco.ecosystem, "install") }
                                                        catch (e: Exception) { app.conversations.notify("同步依赖失败：${e.message}") }
                                                        finally { busyOp = false }
                                                    }
                                                }
                                            })
                                            if (eco.ecosystem != "maven") {
                                                TagPill("添加依赖", fg = G.accent, bg = G.accentContainer, onClick = { addDepEco = eco })
                                            }
                                            TagPill("检查更新", fg = G.ink, bg = G.surfaceSubtle, onClick = {
                                                if (!busyOp && runtime.ready) {
                                                    busyOp = true
                                                    scope.launch {
                                                        try { lastOutput = app.toolchains.runProjectDepAction(eco.cwd, eco.ecosystem, "outdated") }
                                                        catch (e: Exception) { app.conversations.notify("检查更新失败：${e.message}") }
                                                        finally { busyOp = false }
                                                    }
                                                }
                                            })
                                        }
                                    }
                                    if (eco.deps.isNotEmpty()) {
                                        Hairline()
                                        eco.deps.take(30).forEach { d ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(d.name, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(d.version, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                                                if (d.dev) TagPill("dev", fg = G.inkMuted, bg = G.surfaceSubtle)
                                                if (eco.ecosystem in setOf("node", "rust", "python", "dart", "dotnet")) {
                                                    TagPill("移除", fg = G.danger, bg = G.dangerTint, onClick = {
                                                        if (!busyOp && runtime.ready) {
                                                            busyOp = true
                                                            scope.launch {
                                                                try { lastOutput = app.toolchains.runProjectDepAction(eco.cwd, eco.ecosystem, "remove", listOf(d.name)) }
                                                                catch (e: Exception) { app.conversations.notify("移除失败：${e.message}") }
                                                                finally { busyOp = false }
                                                            }
                                                        }
                                                    })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    lastOutput?.let { ToolchainOutputCard(it, onDismiss = { lastOutput = null }) }
                }

                "apt" -> {
                    ToolchainAptTab(
                        app = app,
                        runtimeReady = runtime.ready,
                        aptWritable = tcSnap.aptWritable,
                        busy = busyOp,
                        onBusyChange = { busyOp = it },
                        packages = aptPackages,
                        lastOutput = lastOutput,
                        onOutput = { lastOutput = it },
                    )
                }

                "build" -> {
                    ToolchainBuildAndMirrorTab(
                        app = app,
                        runtimeReady = runtime.ready,
                        busy = busyOp,
                        onBusyChange = { busyOp = it },
                        projectPaths = projectPaths,
                        selectedCwd = selectedCwd,
                        onSelectCwd = { selectedCwd = it },
                        tcSnap = tcSnap,
                        projectScan = projectScan,
                        cacheStats = cacheStats,
                        lastOutput = lastOutput,
                        onOutput = { lastOutput = it },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    manageTarget?.let { (item, preVer) ->
        val freshItem = tcSnap.toolchains.find { it.id == item.id } ?: item
        ToolchainInstallSheet(
            app = app,
            tc = freshItem,
            tcSnap = tcSnap,
            initialVersion = preVer,
            busy = busyOp,
            onInstall = { ver, setDef, targets, aptComps ->
                manageTarget = null
                busyOp = true
                scope.launch {
                    try { app.toolchains.installToolchain(freshItem.id, ver, setDef, targets, aptComps) }
                    catch (e: Exception) { app.conversations.notify("安装失败：${e.message}") }
                    finally { busyOp = false }
                }
            },
            onUseVersion = { ver ->
                busyOp = true
                scope.launch {
                    try { app.toolchains.useVersion(freshItem.id, ver) }
                    catch (e: Exception) { app.conversations.notify("切换版本失败：${e.message}") }
                    finally { busyOp = false }
                }
            },
            onUninstallVersion = { ver ->
                busyOp = true
                scope.launch {
                    try { app.toolchains.uninstallToolchain(freshItem.id, ver) }
                    catch (e: Exception) { app.conversations.notify("移除版本失败：${e.message}") }
                    finally { busyOp = false }
                }
            },
            onDismiss = { manageTarget = null },
        )
    }

    addDepEco?.let { eco ->
        var pkgText by remember { mutableStateOf("") }
        var isDev by remember { mutableStateOf(false) }
        GaliaisSheetScaffold(
            title = "添加依赖 · ${eco.label}",
            subtitle = "多个包名以空格分隔，遵循全局镜像与代理配置",
            onDismiss = { addDepEco = null },
            footer = {
                SecondaryPillButton("取消", onClick = { addDepEco = null })
                PrimaryPillButton("安装并写入清单", enabled = pkgText.isNotBlank() && !busyOp, modifier = Modifier.weight(1f), onClick = {
                    val pkgs = pkgText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    val targetEco = eco
                    addDepEco = null
                    busyOp = true
                    scope.launch {
                        try { lastOutput = app.toolchains.runProjectDepAction(targetEco.cwd, targetEco.ecosystem, "add", pkgs, isDev) }
                        catch (e: Exception) { app.conversations.notify("添加依赖失败：${e.message}") }
                        finally { busyOp = false }
                    }
                })
            },
        ) {
            OutlinedTextField(
                value = pkgText,
                onValueChange = { pkgText = it },
                label = { Text("包名（如 axios / fastapi / serde）") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (eco.ecosystem !in setOf("go", "dotnet")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("作为开发依赖（devDependencies）安装", style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(1f))
                    GaliaisSwitch(checked = isDev, onCheckedChange = { isDev = it })
                }
            }
        }
    }
}
