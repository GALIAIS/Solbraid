package com.galiais.agent.ui.files

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.fs.CreateConflictPolicy
import com.galiais.agent.engine.fs.FileAlreadyExistsVfsException
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.FileScope
import com.galiais.agent.engine.fs.LineEnding
import com.galiais.agent.engine.fs.TextCodec
import com.galiais.agent.engine.fs.TextEncoding
import com.galiais.agent.engine.fs.splitValidatedRelativePath
import com.galiais.agent.ui.D
import com.galiais.agent.ui.G
import com.galiais.agent.ui.GaliaisCheckbox
import com.galiais.agent.ui.GaliaisIcons
import com.galiais.agent.ui.GaliaisSheet
import com.galiais.agent.ui.Mono
import com.galiais.agent.ui.PrimaryPillButton
import com.galiais.agent.ui.SecondaryPillButton
import com.galiais.agent.ui.SectionLabel
import com.galiais.agent.ui.SelectableChip
import com.galiais.agent.ui.TagPill
import com.galiais.agent.ui.galiaisTextFieldColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 内置新建文件模板定义。 */
internal enum class FileTemplate(
    val label: String,
    val defaultName: String,
    val defaultMode: String,
    val content: String,
) {
    BLANK("空白", "", "644", ""),
    SHELL("Shell (.sh)", "script.sh", "755", "#!/usr/bin/env bash\nset -euo pipefail\n\n"),
    PYTHON("Python", "main.py", "644", "#!/usr/bin/env python3\n# -*- coding: utf-8 -*-\n\ndef main() -> None:\n    pass\n\nif __name__ == \"__main__\":\n    main()\n"),
    KOTLIN("Kotlin", "Main.kt", "644", "fun main() {\n    println(\"Hello, Solbraid\")\n}\n"),
    JS_ESM("JS (ESM)", "index.mjs", "644", "export function main() {\n  console.log(\"Hello, Solbraid\");\n}\n"),
    JSON("JSON", "config.json", "644", "{\n  \n}\n"),
    MARKDOWN("Markdown", "notes.md", "644", "# 标题\n\n"),
    HTML5("HTML5", "index.html", "644", "<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n  <meta charset=\"utf-8\" />\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n  <title>Document</title>\n</head>\n<body>\n  \n</body>\n</html>\n"),
    DOCKERFILE("Dockerfile", "Dockerfile", "644", "FROM alpine:3.20\nWORKDIR /app\n"),
    ;
}

/**
 * 智能新建文件弹窗：
 * - 支持 `a/b/c.kt` 多级路径自动级联创建父目录与实时非法字符校验；
 * - 实时检测重名冲突，亮出防覆写预警并提供三选一操作（「打开已有 / 自动编号新建 / 备份后覆盖」）；
 * - 支持常用语言模板（`.sh` 自动预设 `0755` 可执行权限）与「从剪贴板内容新建」；
 * - 可展开调整初始编码、换行符、POSIX 权限与创建后立即打开编辑器。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateFileDialog(
    app: GaliaisApplication,
    fileScope: FileScope,
    currentDir: String,
    onCreated: (createdRef: FileRef, openInEditor: Boolean) -> Unit,
    onOpenExisting: (FileRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val fm = app.fileManager
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var relativePath by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf(FileTemplate.BLANK) }
    var useClipboardContent by remember { mutableStateOf(false) }
    var clipboardSnippet by remember { mutableStateOf<String?>(null) }

    var showAdvanced by remember { mutableStateOf(false) }
    var encoding by remember { mutableStateOf(TextEncoding.UTF_8) }
    var lineEnding by remember { mutableStateOf(LineEnding.LF) }
    var octalMode by remember { mutableStateOf("644") }
    var modeManuallyEdited by remember { mutableStateOf(false) }
    var openAfterCreate by remember { mutableStateOf(true) }

    var existingRef by remember { mutableStateOf<FileRef?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val validatedSegments = remember(relativePath) {
        if (relativePath.isBlank()) null else runCatching { splitValidatedRelativePath(relativePath) }.getOrNull()
    }
    val validationError = remember(relativePath) {
        if (relativePath.isBlank()) null
        else runCatching { splitValidatedRelativePath(relativePath); null }.getOrElse { it.message }
    }
    val parentSubdirs = remember(validatedSegments) {
        if (validatedSegments != null && validatedSegments.size > 1) validatedSegments.dropLast(1).joinToString("/") else null
    }

    // 根据文件名后缀自动预设 .sh -> 0755（除非用户手动改过权限）
    LaunchedEffect(relativePath, selectedTemplate) {
        if (!modeManuallyEdited) {
            val lower = relativePath.trim().lowercase()
            octalMode = when {
                lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh") -> "755"
                else -> selectedTemplate.defaultMode
            }
        }
    }

    // 防抖实时检测目标是否已存在
    LaunchedEffect(relativePath, fileScope, currentDir) {
        existingRef = null
        val segs = validatedSegments
        if (segs.isNullOrEmpty()) return@LaunchedEffect
        delay(180)
        existingRef = fm.findExistingInDirectory(fileScope, currentDir, relativePath)
    }

    fun executeCreate(policy: CreateConflictPolicy) {
        if (relativePath.isBlank() || validationError != null || busy) return
        scope.launch {
            busy = true; error = null
            val rawText = if (useClipboardContent) clipboardSnippet.orEmpty() else selectedTemplate.content
            val bytes = if (rawText.isEmpty()) ByteArray(0) else TextCodec.encodeText(rawText, encoding, lineEnding)
            runCatching {
                fm.createFileInDirectory(
                    scope = fileScope,
                    parentDir = currentDir,
                    relativePath = relativePath,
                    initialBytes = bytes,
                    octalMode = octalMode,
                    conflictPolicy = policy,
                )
            }.onSuccess { created ->
                busy = false
                onCreated(created, openAfterCreate)
            }.onFailure { err ->
                busy = false
                if (err is FileAlreadyExistsVfsException) {
                    existingRef = err.existingRef
                    error = "目标文件已存在，为防清空原文件已拦截，请选择下方处理方式"
                } else {
                    error = err.message ?: "创建文件失败"
                }
            }
        }
    }

    GaliaisSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(GaliaisIcons.FilePlus, contentDescription = null, tint = G.accent, modifier = Modifier.size(20.dp))
                Text("新建文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink, modifier = Modifier.weight(1f))
                TagPill(fileScope.shortLabel, fg = if (fileScope.isRootElevated) G.warning else G.accent, bg = if (fileScope.isRootElevated) G.warningTint else G.accentContainer)
            }

            OutlinedTextField(
                value = relativePath,
                onValueChange = { relativePath = it; error = null },
                label = { Text("文件名或相对路径（如 notes.md、src/utils/cache.kt）") },
                singleLine = true,
                isError = validationError != null,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )

            // 路径校验或级联建目录提示
            if (validationError != null) {
                Text(validationError, style = MaterialTheme.typography.labelSmall, color = G.danger)
            } else if (parentSubdirs != null) {
                Surface(shape = D.shape(8.dp), color = G.accentContainer.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "将自动级联创建中间目录：$parentSubdirs/",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = Mono,
                        color = G.accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            // 实时重名冲突三选一保护卡片（绝不静默清空已有文件）
            existingRef?.let { conflict ->
                Surface(
                    shape = D.shape(10.dp),
                    color = G.warningTint,
                    border = BorderStroke(0.75.dp, G.warning.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "目标「${conflict.name}」已存在，为防清空原文件已阻止覆写。",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = G.ink,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TagPill("打开已有文件", fg = G.onAccent, bg = G.accent, onClick = { onOpenExisting(conflict) })
                            TagPill("自动编号新建", fg = G.accent, bg = G.surface, border = G.accent.copy(alpha = 0.4f), onClick = { executeCreate(CreateConflictPolicy.AUTO_RENAME) })
                            TagPill("备份后覆盖", fg = G.danger, bg = G.surface, border = G.danger.copy(alpha = 0.4f), onClick = { executeCreate(CreateConflictPolicy.OVERWRITE_WITH_BACKUP) })
                        }
                    }
                }
            }

            // 模板与剪贴板骨架选择
            SectionLabel("初始内容模板")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FileTemplate.entries.forEach { tpl ->
                    val selected = !useClipboardContent && selectedTemplate == tpl
                    SelectableChip(tpl.label, selected = selected, onClick = {
                        useClipboardContent = false
                        selectedTemplate = tpl
                        if (relativePath.isBlank() && tpl.defaultName.isNotEmpty()) {
                            relativePath = tpl.defaultName
                        }
                    })
                }
                SelectableChip("从剪贴板新建", selected = useClipboardContent, onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (clip.isNullOrEmpty()) {
                        app.conversations.notify("当前剪贴板为空")
                    } else {
                        clipboardSnippet = clip
                        useClipboardContent = true
                        if (relativePath.isBlank()) relativePath = "clipboard.txt"
                    }
                })
            }

            if (useClipboardContent && !clipboardSnippet.isNullOrEmpty()) {
                val clip = clipboardSnippet!!
                val lines = clip.count { it == '\n' } + 1
                Surface(shape = D.shape(8.dp), color = G.surfaceSubtle, border = BorderStroke(0.5.dp, G.border), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("已载入剪贴板内容（$lines 行 · ${clip.length} 字符）", style = MaterialTheme.typography.labelSmall, color = G.accent)
                        Text(clip.take(180), fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // 高级选项折叠区（编码 / 换行符 / POSIX 权限）
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (showAdvanced) "▾ 高级选项（编码 / 换行符 / 权限）" else "▸ 高级选项（${encoding.label} · ${lineEnding.label} · chmod $octalMode）",
                    style = MaterialTheme.typography.labelMedium,
                    color = G.inkMuted,
                )
            }

            if (showAdvanced) {
                Surface(shape = D.shape(10.dp), color = G.surfaceSubtle, border = BorderStroke(0.5.dp, G.border), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("编码", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(TextEncoding.UTF_8, TextEncoding.UTF_8_BOM, TextEncoding.GB18030, TextEncoding.UTF_16_LE).forEach { enc ->
                                SelectableChip(enc.label, selected = encoding == enc, onClick = { encoding = enc })
                            }
                        }
                        Text("换行符与权限", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LineEnding.entries.forEach { le ->
                                SelectableChip(le.label, selected = lineEnding == le, onClick = { lineEnding = le })
                            }
                            if (!currentDir.startsWith("saf://")) {
                                listOf("644", "755", "600").forEach { mode ->
                                    SelectableChip("chmod $mode", selected = octalMode == mode, onClick = {
                                        octalMode = mode
                                        modeManuallyEdited = true
                                    })
                                }
                            }
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickable { openAfterCreate = !openAfterCreate }.padding(vertical = 2.dp),
            ) {
                GaliaisCheckbox(openAfterCreate)
                Text("创建后立即在代码编辑器打开", style = MaterialTheme.typography.bodySmall, color = G.ink)
            }

            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryPillButton("取消", enabled = !busy, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                PrimaryPillButton(
                    text = if (busy) "创建中…" else if (openAfterCreate) "创建并编辑" else "创建文件",
                    enabled = !busy && relativePath.isNotBlank() && validationError == null && existingRef == null,
                    onClick = { executeCreate(CreateConflictPolicy.FAIL_IF_EXISTS) },
                )
            }
        }
    }
}
