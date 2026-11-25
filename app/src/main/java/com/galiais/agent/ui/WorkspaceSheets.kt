package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.CommandResult
import com.galiais.agent.engine.GitCommit
import com.galiais.agent.engine.GitOverview
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.WorkspaceFiles
import kotlinx.coroutines.launch

/** 工作区 Git 工作台抽屉：支持初始化仓库、分支切换/新建、一键暂存提交、Pull/Push、未提交 Diff 与提交历史浏览。 */
@Composable
fun GitOverviewSheet(
    files: WorkspaceFiles,
    cwd: String,
    onNotify: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableIntStateOf(0) } // 0=改动与提交 1=提交历史
    var overview by remember { mutableStateOf<GitOverview?>(null) }
    var commits by remember { mutableStateOf<List<GitCommit>>(emptyList()) }
    var currentBranch by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var commitMessage by remember { mutableStateOf("") }
    var showBranchDialog by remember { mutableStateOf(false) }
    var newBranchInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        loading = true; error = null
        scope.launch {
            try {
                val info = files.gitOverview(cwd)
                overview = info
                if (info.isRepo) {
                    val (cur, all) = files.gitBranches(cwd)
                    currentBranch = cur
                    branches = all
                    commits = files.gitLog(cwd)
                } else {
                    commits = emptyList()
                }
            } catch (e: Exception) {
                error = e.message
            }
            loading = false
        }
    }
    LaunchedEffect(cwd) { reload() }

    fun runGitOp(label: String, op: suspend () -> CommandResult) {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try {
                val res = op()
                if (res.exitCode != 0) {
                    error = "$label 失败：${res.stderr.ifBlank { res.stdout }.trim()}"
                } else {
                    onNotify("$label 成功")
                    commitMessage = ""
                    reload()
                    return@launch
                }
            } catch (e: Exception) {
                error = "$label 失败：${e.message}"
            }
            loading = false
        }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Git 工作台", style = MaterialTheme.typography.titleLarge, color = G.ink)
                    Text(cwd, fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted)
                }
                SecondaryPillButton("刷新", onClick = { reload() }, enabled = !loading)
            }
            HeaderProgressSlot(loading)
            error?.let {
                Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.dangerTint) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger, modifier = Modifier.padding(10.dp))
                }
            }
            overview?.let { info ->
                if (info.isRepo) {
                    SegmentedPillBar(
                        options = listOf(0 to "改动与提交 (${info.diffs.size})", 1 to "提交历史 (${commits.size})"),
                        selected = tab,
                        onSelect = { tab = it },
                        compact = true,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!info.isRepo) {
                        CrispCard {
                            Text(info.statusText.ifBlank { "当前目录尚未初始化为 Git 仓库。" }, style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
                            Spacer(Modifier.height(10.dp))
                            PrimaryPillButton("初始化 Git 仓库", enabled = !loading, onClick = {
                                runGitOp("初始化 Git 仓库") { files.gitInit(cwd) }
                            })
                        }
                    } else if (tab == 1) {
                        if (commits.isEmpty()) {
                            CrispCard {
                                Text("当前分支尚无提交记录。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            }
                        } else {
                            commits.forEach { c ->
                                CrispCard {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TagPill(c.hash, fg = G.accent, bg = G.accentContainer)
                                        Text(c.subject, style = MaterialTheme.typography.bodyMedium, color = G.ink, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("${c.author} · ${c.relTime}", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TagPill(
                                text = "分支：${currentBranch.ifBlank { "HEAD" }} ▾",
                                fg = G.accent,
                                bg = G.accentContainer,
                                onClick = { showBranchDialog = true },
                            )
                            TagPill("拉取", fg = G.ink, bg = G.surface, border = G.borderStrong, onClick = {
                                runGitOp("Git Pull") { files.gitPull(cwd) }
                            })
                            TagPill("推送", fg = G.ink, bg = G.surface, border = G.borderStrong, onClick = {
                                runGitOp("Git Push") { files.gitPush(cwd) }
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = commitMessage,
                                onValueChange = { commitMessage = it },
                                label = { Text("提交说明（包含全部改动）") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                modifier = Modifier.weight(1f),
                            )
                            PrimaryPillButton(
                                "提交",
                                enabled = !loading && commitMessage.isNotBlank(),
                                onClick = { runGitOp("提交改动") { files.gitStageAndCommit(cwd, commitMessage) } },
                            )
                        }
                        Surface(Modifier.fillMaxWidth(), shape = D.codeShape, color = G.codeBg, border = BorderStroke(1.dp, G.codeBorder)) {
                            SelectionContainer {
                                Text(
                                    info.statusText.ifBlank { "暂无未提交改动。" },
                                    fontFamily = Mono,
                                    fontSize = D.codeSize(12f),
                                    lineHeight = D.codeSize(17.5f),
                                    color = G.codeInk,
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                                )
                            }
                        }
                        if (info.diffs.isEmpty()) {
                            Text("暂无文件差异。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        } else {
                            Text("改动文件（${info.diffs.size}）", style = MaterialTheme.typography.titleSmall, color = G.ink)
                            info.diffs.forEach { DiffView(it, inlineCompact = true) }
                        }
                    }
                }
            }
        }
    }

    if (showBranchDialog) {
        AlertDialog(
            onDismissRequest = { showBranchDialog = false },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("切换或新建分支", color = G.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    branches.forEach { b ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                showBranchDialog = false
                                if (b != currentBranch) runGitOp("切换分支 $b") { files.gitCheckout(cwd, b, createNew = false) }
                            },
                            shape = D.shape(8.dp),
                            color = if (b == currentBranch) G.accentContainer else G.surfaceSubtle,
                        ) {
                            Text(
                                (if (b == currentBranch) "● " else "  ") + b,
                                fontFamily = Mono,
                                fontSize = 13.sp,
                                color = if (b == currentBranch) G.accent else G.ink,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = newBranchInput,
                        onValueChange = { newBranchInput = it },
                        label = { Text("新分支名称") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newBranchInput.isNotBlank(),
                    onClick = {
                        val target = newBranchInput.trim()
                        newBranchInput = ""
                        showBranchDialog = false
                        runGitOp("新建分支 $target") { files.gitCheckout(cwd, target, createNew = true) }
                    },
                ) { Text("新建并切换", color = G.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { showBranchDialog = false }) { Text("关闭", color = G.inkMuted) } },
        )
    }
}

