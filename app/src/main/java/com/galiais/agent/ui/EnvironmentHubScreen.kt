package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.EnvPresetTemplate
import com.galiais.agent.engine.EnvVarEntry
import com.galiais.agent.engine.GitIdentityConfig
import com.galiais.agent.engine.RuntimeEnvEntry
import com.galiais.agent.engine.ShellAlias
import com.galiais.agent.engine.curatedEnvPresets
import com.galiais.agent.newId
import kotlinx.coroutines.launch

/**
 * 统一环境中心：全局/项目环境变量（含 Keystore 加密 Secret）、.env 导入导出、
 * 自定义 PATH、Shell 别名与启动脚本、Git 全局身份，以及运行时生效环境快照。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnvironmentHubScreen(
    app: GaliaisApplication,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val envConfig by app.envSsh.envConfig.collectAsState()
    val syncing by app.envSsh.syncing.collectAsState()
    val syncError by app.envSsh.envSyncError.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf("vars") }
    var scopeFilter by remember { mutableStateOf("all") }
    var varSearch by remember { mutableStateOf("") }
    var editingVar by remember { mutableStateOf<EnvVarEntry?>(null) }
    var deletingVar by remember { mutableStateOf<EnvVarEntry?>(null) }
    var creatingVar by remember { mutableStateOf(false) }
    var presetSeed by remember { mutableStateOf<EnvPresetTemplate?>(null) }
    var showImportDotEnv by remember { mutableStateOf(false) }
    var showSnapshot by remember { mutableStateOf(false) }

    val projectPaths = remember(settings.projects, settings.defaultCwd) {
        (listOf(settings.defaultCwd) + settings.projects).distinct()
    }

    // Shell & Git 表单草稿状态
    var extraPathsText by remember(envConfig.extraPaths) { mutableStateOf(envConfig.extraPaths.joinToString("\n")) }
    var aliasesText by remember(envConfig.aliases) { mutableStateOf(envConfig.aliases.joinToString("\n") { "${it.name}=${it.command}" }) }
    var shellSnippet by remember(envConfig.shellSnippet) { mutableStateOf(envConfig.shellSnippet) }
    var gitName by remember(envConfig.git.userName) { mutableStateOf(envConfig.git.userName) }
    var gitEmail by remember(envConfig.git.userEmail) { mutableStateOf(envConfig.git.userEmail) }
    var gitBranch by remember(envConfig.git.defaultBranch) { mutableStateOf(envConfig.git.defaultBranch) }
    var gitAutoCrlf by remember(envConfig.git.autoCrlf) { mutableStateOf(envConfig.git.autoCrlf) }
    var shellSaving by remember { mutableStateOf(false) }
    var shellError by remember { mutableStateOf<String?>(null) }

    // 项目 .env 在线编辑状态
    var selectedDotEnvCwd by remember(projectPaths) { mutableStateOf(projectPaths.firstOrNull() ?: settings.defaultCwd) }
    var dotEnvExists by remember { mutableStateOf(false) }
    var dotEnvContent by remember { mutableStateOf("") }
    var dotEnvLoading by remember { mutableStateOf(false) }
    var dotEnvError by remember { mutableStateOf<String?>(null) }
    var dotEnvLoadedCwd by remember { mutableStateOf<String?>(null) }
    var dotEnvRevision by remember { mutableStateOf<String?>(null) }
    var dotEnvOriginal by remember { mutableStateOf("") }
    var dotEnvSaving by remember { mutableStateOf(false) }
    var dotEnvReload by remember { mutableStateOf(0) }
    var dotEnvLoadedReload by remember { mutableStateOf(-1) }
    var pendingDotEnvRead by remember { mutableStateOf<String?>(null) }

    fun requestDotEnvRead(cwd: String) {
        if (dotEnvContent != dotEnvOriginal && dotEnvLoadedCwd != null) pendingDotEnvRead = cwd
        else { selectedDotEnvCwd = cwd; dotEnvReload++ }
    }

    LaunchedEffect(section, selectedDotEnvCwd, runtime.ready, dotEnvReload) {
        if (section == "dotenv" && runtime.ready && (dotEnvLoadedCwd != selectedDotEnvCwd || dotEnvLoadedReload != dotEnvReload)) {
            val readingCwd = selectedDotEnvCwd
            dotEnvLoading = true
            dotEnvError = null
            dotEnvLoadedCwd = null
            dotEnvRevision = null
            dotEnvContent = ""
            dotEnvOriginal = ""
            dotEnvExists = false
            try {
                val document = app.envSsh.readProjectDotEnv(readingCwd)
                dotEnvExists = document.exists
                dotEnvContent = document.content
                dotEnvOriginal = document.content
                dotEnvRevision = document.revision
                dotEnvLoadedCwd = readingCwd
                dotEnvLoadedReload = dotEnvReload
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                dotEnvError = e.message ?: "读取 .env 失败"
            } finally {
                dotEnvLoading = false
            }
        }
    }

    val enabledCount = envConfig.vars.count { it.enabled }
    val secretCount = envConfig.vars.count { it.secret }

    SubPageScaffold(
        title = "环境中心",
        onBack = onBack,
        snackbar = snackbar,
        loading = syncing || dotEnvLoading,
        actions = {
            if (runtime.ready) {
                CompactActionButton("快照", onClick = { showSnapshot = true })
                CompactActionButton(
                    text = if (syncing) "同步中…" else "同步",
                    primary = true,
                    enabled = !syncing,
                    onClick = {
                        scope.launch {
                            try {
                                app.envSsh.syncEnvironmentNow(force = true)
                                app.conversations.notify("环境配置已同步到 Linux 运行时")
                            } catch (e: Exception) {
                                app.conversations.notify("同步失败：${e.message}")
                            }
                        }
                    },
                )
            }
        },
    ) {
        if (!runtime.ready || syncError != null) {
            CrispCard(padding = PaddingValues(12.dp), background = if (syncError != null) G.dangerTint else G.surfaceSubtle) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (syncError != null) "配置尚未生效：$syncError" else "本地运行环境未就绪",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (syncError != null) G.danger else G.ink,
                        )
                        if (!runtime.ready) {
                            Text("启动后将自动把环境变量与 Shell 配置同步至 Linux 运行时。", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        }
                    }
                    if (!runtime.ready) {
                        PrimaryPillButton(
                            text = if (runtime.busy) "启动中…" else "启动",
                            enabled = runtime.bound && !runtime.busy,
                            onClick = app.runtime::start,
                        )
                    }
                }
            }
        } else {
            Text(
                "启用 $enabledCount 条（加密 $secretCount）· PATH +${envConfig.extraPaths.size} · 别名 ${envConfig.aliases.size} · 适用于新启动的 Agent 与终端",
                style = MaterialTheme.typography.labelSmall,
                color = G.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        SegmentedPillBar(
            options = listOf(
                "vars" to "环境变量 (${envConfig.vars.size})",
                "shell" to "Shell · Git",
                "dotenv" to "项目 .env",
            ),
            selected = section,
            onSelect = { section = it },
        )

        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 280.dp)) {
            Crossfade(targetState = section, animationSpec = tween(120), label = "envSection") { activeSection ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(D.space(10.dp))) {
                    when (activeSection) {
            "vars" -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectableChip("全部", selected = scopeFilter == "all", onClick = { scopeFilter = "all" })
                    SelectableChip("全局", selected = scopeFilter == "global", onClick = { scopeFilter = "global" })
                    projectPaths.forEach { p ->
                        val tag = "project:$p"
                        SelectableChip(p.substringAfterLast('/').ifBlank { p }, selected = scopeFilter == tag, onClick = { scopeFilter = tag })
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrimaryPillButton("+ 添加变量", onClick = { presetSeed = null; creatingVar = true })
                    SecondaryPillButton(".env 导入", onClick = { showImportDotEnv = true })
                    if (envConfig.vars.isNotEmpty()) {
                        TagPill("复制导出", fg = G.accent, bg = G.accentContainer, onClick = {
                            val text = app.envSsh.exportNonSecretDotEnv(scopeFilter.takeUnless { it == "all" })
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("env", text).toClipEntry())
                                app.conversations.notify("已复制非敏感环境变量（Secret 已脱敏占位）")
                            }
                        })
                    }
                }

                if (envConfig.vars.size > 3) {
                    SearchBar(value = varSearch, onValueChange = { varSearch = it }, placeholder = "搜索变量名、值或备注…")
                }

                val q = varSearch.trim().lowercase()
                val filtered = envConfig.vars.filter { v ->
                    (scopeFilter == "all" || v.scope == scopeFilter) &&
                        (q.isEmpty() || v.key.lowercase().contains(q) || (!v.secret && v.value.lowercase().contains(q)) || v.note.lowercase().contains(q))
                }
                if (filtered.isEmpty()) {
                    CrispCard {
                        Text("当前分组暂无环境变量。可点「+ 添加变量」、「.env 导入」，或从下方常用模板一键添加。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                } else {
                    filtered.forEach { item ->
                        CrispCard(
                            padding = PaddingValues(start = 13.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
                            onClick = { editingVar = item },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            item.key,
                                            fontFamily = Mono,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = G.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (item.secret) TagPill("Secret", fg = G.warning, bg = G.warningTint)
                                        TagPill(
                                            if (item.scope == "global") "全局" else item.scope.removePrefix("project:").substringAfterLast('/'),
                                            fg = G.inkMuted,
                                            bg = G.surfaceSubtle,
                                        )
                                    }
                                    Text(
                                        if (item.secret) {
                                            if (app.envSsh.hasEnvSecret(item.id)) "••••••••（Android Keystore 加密存储）" else "（未填写 Secret 值）"
                                        } else item.value.ifBlank { "(空值)" },
                                        fontFamily = Mono,
                                        fontSize = 11.5.sp,
                                        color = G.inkMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (item.note.isNotBlank()) {
                                        Text(item.note, style = MaterialTheme.typography.bodySmall, color = G.inkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                GaliaisSwitch(checked = item.enabled, onCheckedChange = { app.envSsh.toggleEnvVar(item.id, it) })
                                IconButton(onClick = { deletingVar = item }, modifier = Modifier.size(34.dp)) {
                                    Icon(GaliaisIcons.Trash, contentDescription = "删除变量", tint = G.danger, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                SectionLabel("常用开发预设（点按快速添加）")
                CrispCard(padding = PaddingValues(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        curatedEnvPresets.forEach { preset ->
                            val exists = envConfig.vars.any { it.key == preset.key }
                            TagPill(
                                text = if (exists) "✓ ${preset.title}" else "+ ${preset.title} (${preset.key})",
                                fg = if (exists) G.success else G.ink,
                                bg = if (exists) G.successTint else G.surfaceSubtle,
                                border = if (exists) null else G.border,
                                onClick = {
                                    presetSeed = preset
                                    creatingVar = true
                                },
                            )
                        }
                    }
                }
            }

            "shell" -> {
                SectionLabel("自定义 PATH 与 Shell 初始化")
                CrispCard(padding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = extraPathsText,
                            onValueChange = { extraPathsText = it },
                            label = { Text("前置 PATH 目录（每行一条）") },
                            placeholder = { Text("/home/agent/.local/bin\n/home/agent/.cargo/bin") },
                            minLines = 2,
                            maxLines = 5,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = aliasesText,
                            onValueChange = { aliasesText = it },
                            label = { Text("Shell 别名（每行格式：alias=command）") },
                            placeholder = { Text("ll=ls -la\ngs=git status -sb") },
                            minLines = 2,
                            maxLines = 6,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = shellSnippet,
                            onValueChange = { shellSnippet = it },
                            label = { Text("Shell 启动脚本片段（写入 ~/.galiais/env/profile.sh）") },
                            placeholder = { Text("# 例如：umask 022 或自定义导出逻辑") },
                            minLines = 3,
                            maxLines = 8,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                SectionLabel("Git 全局身份与行为")
                CrispCard(padding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = gitName,
                            onValueChange = { gitName = it },
                            label = { Text("user.name（提交作者名）") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = gitEmail,
                            onValueChange = { gitEmail = it.trim() },
                            label = { Text("user.email（提交邮箱）") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = gitBranch,
                                onValueChange = { gitBranch = it.trim() },
                                label = { Text("默认分支名") },
                                placeholder = { Text("main") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = gitAutoCrlf,
                                onValueChange = { gitAutoCrlf = it.trim() },
                                label = { Text("core.autocrlf") },
                                placeholder = { Text("input / false") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        shellError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            PrimaryPillButton(if (shellSaving) "正在保存" else "保存并同步", enabled = runtime.ready && !shellSaving, onClick = {
                                shellSaving = true
                                shellError = null
                                scope.launch {
                                try {
                                val paths = extraPathsText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                                val aliases = aliasesText.lineSequence().mapNotNull { line ->
                                    val t = line.trim()
                                    val eq = t.indexOf('=')
                                    if (t.isEmpty() || t.startsWith("#")) null
                                    else {
                                        require(eq > 0) { "别名格式应为 name=command" }
                                        ShellAlias(t.substring(0, eq).trim(), t.substring(eq + 1).trim())
                                    }
                                }.toList()
                                app.envSsh.updateShellAndGit(
                                    extraPaths = paths,
                                    aliases = aliases,
                                    shellSnippet = shellSnippet,
                                    git = GitIdentityConfig(
                                        userName = gitName.trim(),
                                        userEmail = gitEmail.trim(),
                                        defaultBranch = gitBranch.trim(),
                                        autoCrlf = gitAutoCrlf.trim(),
                                    ),
                                )
                                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                catch (e: Exception) { shellError = e.message ?: "保存失败，请重试" }
                                finally { shellSaving = false }
                                }
                            })
                        }
                    }
                }
            }

            else -> {
                SectionLabel("选择工作区项目编辑 .env 文件")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    projectPaths.forEach { p ->
                        SelectableChip(
                            text = p.substringAfterLast('/').ifBlank { p },
                            selected = selectedDotEnvCwd == p,
                            onClick = { if (p != selectedDotEnvCwd && !dotEnvSaving) requestDotEnvRead(p) },
                        )
                    }
                }
                CrispCard(padding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("$selectedDotEnvCwd/.env", fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TagPill(if (dotEnvExists) "已存在" else "新建", fg = if (dotEnvExists) G.success else G.inkMuted, bg = if (dotEnvExists) G.successTint else G.surfaceSubtle)
                            TagPill("重新读取", fg = G.accent, bg = G.accentContainer,
                                onClick = if (runtime.ready && !dotEnvLoading && !dotEnvSaving) ({ requestDotEnvRead(selectedDotEnvCwd) }) else null)
                        }
                        if (!runtime.ready) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    if (runtime.busy) "正在启动本地运行环境，就绪后自动读取 .env…" else "启动本地运行环境后即可直接读写工作区 .env 文件。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = G.warning,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!runtime.busy && runtime.bound) {
                                    TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                                }
                            }
                        } else {
                            dotEnvError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
                            OutlinedTextField(
                                value = dotEnvContent,
                                onValueChange = { dotEnvContent = it },
                                enabled = !dotEnvLoading && !dotEnvSaving && dotEnvLoadedCwd == selectedDotEnvCwd,
                                label = { Text(if (dotEnvLoading) ".env 内容（读取中…）" else ".env 内容") },
                                placeholder = { Text("API_BASE_URL=https://...\nPORT=3000") },
                                minLines = 7,
                                maxLines = 16,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PrimaryPillButton(if (dotEnvSaving) "保存中…" else "保存 .env", enabled = !dotEnvSaving && dotEnvLoadedCwd == selectedDotEnvCwd && dotEnvRevision != null, onClick = {
                                    val savingCwd = dotEnvLoadedCwd ?: return@PrimaryPillButton
                                    val revision = dotEnvRevision ?: return@PrimaryPillButton
                                    val content = dotEnvContent
                                    dotEnvSaving = true
                                    scope.launch {
                                        try {
                                            val nextRevision = app.envSsh.writeProjectDotEnv(savingCwd, content, revision)
                                            if (dotEnvLoadedCwd == savingCwd) {
                                                dotEnvExists = true
                                                dotEnvRevision = nextRevision
                                                dotEnvOriginal = content
                                                dotEnvError = null
                                            }
                                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                                            throw cancel
                                        } catch (e: Exception) {
                                            dotEnvError = "保存失败：${e.message}"
                                        } finally {
                                            dotEnvSaving = false
                                        }
                                    }
                                })
                                SecondaryPillButton("导入到托管变量", enabled = !dotEnvSaving && dotEnvLoadedCwd == selectedDotEnvCwd && dotEnvContent.isNotBlank(), onClick = {
                                    runCatching {
                                        app.envSsh.importDotEnv(dotEnvContent, scopeTag = "project:$selectedDotEnvCwd")
                                    }.onSuccess { count ->
                                        if (count == 0) app.conversations.notify("未解析到有效 KEY=VALUE 行")
                                    }.onFailure { err ->
                                        app.conversations.notify("导入失败：${err.message ?: "格式无效"}")
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
        }
        Spacer(Modifier.height(16.dp))
    }

    deletingVar?.let { target ->
        GaliaisConfirmDialog(
            title = "删除环境变量？",
            message = "将删除变量「${target.key}」${if (target.secret) "及其加密保存的 Secret 值" else ""}，不会影响已有运行中进程。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                app.envSsh.deleteEnvVar(target.id)
                deletingVar = null
            },
            onDismiss = { deletingVar = null },
        )
    }

    pendingDotEnvRead?.let { target ->
        GaliaisConfirmDialog(
            title = "放弃未保存的修改？",
            message = "将重新读取所选项目的 .env 文件，当前未保存改动会丢失。",
            confirmText = "重新读取",
            danger = true,
            onConfirm = {
                pendingDotEnvRead = null
                selectedDotEnvCwd = target
                dotEnvReload++
            },
            onDismiss = { pendingDotEnvRead = null },
        )
    }

    if (creatingVar || editingVar != null) {
        EnvVarEditorSheet(
            app = app,
            initial = editingVar,
            preset = if (editingVar == null) presetSeed else null,
            projectPaths = projectPaths,
            defaultScope = scopeFilter.takeUnless { it == "all" } ?: "global",
            onDismiss = { creatingVar = false; editingVar = null; presetSeed = null },
        )
    }
    if (showImportDotEnv) {
        DotEnvImportDialog(
            projectPaths = projectPaths,
            defaultScope = scopeFilter.takeUnless { it == "all" } ?: "global",
            onDismiss = { showImportDotEnv = false },
            onImport = { raw, scopeTag, autoSecret ->
                val n = app.envSsh.importDotEnv(raw, scopeTag, autoSecret)
                require(n > 0) { "未找到环境变量" }
                showImportDotEnv = false
            },
        )
    }
    if (showSnapshot) {
        RuntimeEnvSnapshotSheet(
            app = app,
            projectPaths = projectPaths,
            initialCwd = settings.defaultCwd,
            onDismiss = { showSnapshot = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnvVarEditorSheet(
    app: GaliaisApplication,
    initial: EnvVarEntry?,
    preset: EnvPresetTemplate?,
    projectPaths: List<String>,
    defaultScope: String,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf(initial?.key ?: preset?.key ?: "") }
    var value by remember { mutableStateOf(if (initial?.secret == true) "" else (initial?.value ?: preset?.defaultValue ?: "")) }
    var secret by remember { mutableStateOf(initial?.secret ?: preset?.secret ?: false) }
    var clearSecret by remember { mutableStateOf(false) }
    var scopeTag by remember { mutableStateOf(initial?.scope ?: defaultScope) }
    var note by remember { mutableStateOf(initial?.note ?: preset?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (initial == null) "添加环境变量" else "编辑环境变量 · ${initial.key}", style = MaterialTheme.typography.titleLarge, color = G.ink)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("变量名（KEY）") },
                placeholder = { Text("GITHUB_TOKEN") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = {
                    Text(
                        if (secret && initial != null && app.envSsh.hasEnvSecret(initial.id)) "新 Secret 值（留空保持原加密值）"
                        else if (secret) "Secret 值（Keystore 加密存储）"
                        else "变量值（VALUE）",
                    )
                },
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                singleLine = !value.contains('\n'),
                maxLines = 4,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )
            CrispCard(padding = PaddingValues(0.dp)) {
                SettingsToggleRow(
                    title = "加密敏感值（Secret）",
                    subtitle = "使用 Android Keystore AES-GCM 加密，不在快照或导出中明文暴露",
                    checked = secret,
                    onCheckedChange = { secret = it },
                )
            }
            if (secret && initial?.secret == true && app.envSsh.hasEnvSecret(initial.id)) {
                CrispCard(padding = PaddingValues(0.dp)) {
                    SettingsToggleRow(
                        title = "清除已保存的敏感值",
                        subtitle = "",
                        checked = clearSecret,
                        onCheckedChange = { clearSecret = it; if (it) value = "" },
                    )
                }
            }
            Text("生效范围", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectableChip("全局生效", selected = scopeTag == "global", onClick = { scopeTag = "global" })
                projectPaths.forEach { p ->
                    val tag = "project:$p"
                    SelectableChip(p.substringAfterLast('/').ifBlank { p }, selected = scopeTag == tag, onClick = { scopeTag = tag })
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryPillButton("取消", onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                PrimaryPillButton("保存", onClick = {
                    val cleanKey = key.trim()
                    if (!Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$").matches(cleanKey)) {
                        error = "变量名须以字母或下划线开头，仅含字母、数字与下划线"
                        return@PrimaryPillButton
                    }
                    val entry = EnvVarEntry(
                        id = initial?.id ?: newId().take(10),
                        key = cleanKey,
                        value = if (secret) "" else value,
                        secret = secret,
                        enabled = initial?.enabled ?: true,
                        scope = scopeTag,
                        note = note.trim(),
                    )
                    try {
                        app.envSsh.upsertEnvVar(entry, secretPlain = if (clearSecret) "" else value.takeIf { secret && it.isNotEmpty() })
                        onDismiss()
                    } catch (e: Exception) { error = e.message ?: "保存失败" }
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DotEnvImportDialog(
    projectPaths: List<String>,
    defaultScope: String,
    onDismiss: () -> Unit,
    onImport: (raw: String, scopeTag: String, autoSecret: Boolean) -> Unit,
) {
    var raw by remember { mutableStateOf("") }
    var scopeTag by remember { mutableStateOf(defaultScope) }
    var autoSecret by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisAlertDialog(
        onDismissRequest = onDismiss,
        title = "批量导入 .env 变量",
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectableChip("全局", selected = scopeTag == "global", onClick = { scopeTag = "global" })
                    projectPaths.take(4).forEach { p ->
                        val tag = "project:$p"
                        SelectableChip(p.substringAfterLast('/').ifBlank { p }, selected = scopeTag == tag, onClick = { scopeTag = tag })
                    }
                }
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    label = { Text("粘贴 KEY=VALUE 文本") },
                    placeholder = { Text("OPENAI_BASE_URL=https://...\nGITHUB_TOKEN=ghp_xxx") },
                    minLines = 5,
                    maxLines = 10,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                CrispCard(padding = PaddingValues(0.dp)) {
                    SettingsToggleRow(
                        title = "敏感键自动加密",
                        subtitle = "含 TOKEN / SECRET / KEY / PASSWORD 时自动存为 Keystore Secret",
                        checked = autoSecret,
                        onCheckedChange = { autoSecret = it },
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try { onImport(raw, scopeTag, autoSecret) }
                catch (e: Exception) { error = e.message ?: "导入失败" }
            }) { Text("导入", color = G.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = G.inkMuted) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuntimeEnvSnapshotSheet(
    app: GaliaisApplication,
    projectPaths: List<String>,
    initialCwd: String,
    onDismiss: () -> Unit,
) {
    var selectedCwd by remember { mutableStateOf(initialCwd) }
    var entries by remember { mutableStateOf<List<RuntimeEnvEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(selectedCwd) {
        loading = true
        error = null
        try {
            entries = app.envSsh.fetchRuntimeEnvSnapshot(selectedCwd)
        } catch (e: Exception) {
            error = e.message ?: "读取运行时环境失败"
        } finally {
            loading = false
        }
    }

    val filtered = remember(entries, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) entries else entries.filter { it.key.lowercase().contains(q) || it.value.lowercase().contains(q) }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("当前生效运行时环境（已自动脱敏）", style = MaterialTheme.typography.titleMedium, color = G.ink)
            if (projectPaths.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    projectPaths.forEach { p ->
                        SelectableChip(p.substringAfterLast('/').ifBlank { p }, selected = selectedCwd == p, onClick = { selectedCwd = p })
                    }
                }
            }
            SearchBar(value = query, onValueChange = { query = it }, placeholder = "搜索变量名或值…")
            HeaderProgressSlot(loading)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            if (loading) {
                Text("正在读取生效环境变量…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            } else if (filtered.isEmpty()) {
                Text("没有匹配的变量。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered, key = { it.key }) { item ->
                        CrispCard(padding = PaddingValues(horizontal = 11.dp, vertical = 8.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(item.key, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = G.ink, modifier = Modifier.weight(1f))
                                    if (item.masked) TagPill("已脱敏", fg = G.warning, bg = G.warningTint)
                                }
                                Text(item.value, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
