package com.galiais.agent.ui.files

import android.content.ClipData
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.fs.ApkInspectInfo
import com.galiais.agent.engine.fs.ArchiveEngine
import com.galiais.agent.engine.fs.ArchiveItem
import com.galiais.agent.engine.fs.ExternalStorageBridge
import com.galiais.agent.engine.fs.FileHashes
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.TextCodec
import com.galiais.agent.engine.fs.extractArchive
import com.galiais.agent.engine.fs.formatFileSize
import com.galiais.agent.engine.fs.joinVfsPath
import com.galiais.agent.ui.CrispCard
import com.galiais.agent.ui.D
import com.galiais.agent.ui.G
import com.galiais.agent.ui.GaliaisAlertDialog
import com.galiais.agent.ui.GaliaisIcons
import com.galiais.agent.ui.Mono
import com.galiais.agent.ui.PrimaryPillButton
import com.galiais.agent.ui.SearchBar
import com.galiais.agent.ui.SecondaryPillButton
import com.galiais.agent.ui.SelectableChip
import com.galiais.agent.ui.TagPill
import com.galiais.agent.ui.galiaisTextFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

private data class JsonTreeRow(val path: String, val depth: Int, val keyLabel: String, val valuePreview: String, val expandable: Boolean)

/** 1. JSON 交互树状查看器（支持折叠展开、关键词过滤与一键复制 JSONPath）。 */
@Composable
internal fun JsonTreeViewer(app: GaliaisApplication, file: File) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val parsedState by produceState<Pair<Boolean, Any?>>(initialValue = false to null, key1 = file) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val maxBytes = 1_500_000
                val raw = file.inputStream().use { inp ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(32_768)
                    var total = 0
                    while (total < maxBytes) {
                        val n = inp.read(buf, 0, minOf(buf.size, maxBytes - total))
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    out.toByteArray().toString(Charsets.UTF_8)
                }
                JSONTokener(raw).nextValue()
            }.getOrNull()
        }
        value = true to result
    }
    if (!parsedState.first) return
    val parsed = parsedState.second
    if (parsed == null) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            CrispCard { Text("JSON 解析失败，请点击上方「以代码编辑器打开」查看源码。", color = G.danger) }
        }
        return
    }
    val rows = remember(parsed, collapsed.toMap(), query) {
        val out = mutableListOf<JsonTreeRow>()
        val q = query.trim().lowercase()
        fun walk(node: Any?, path: String, key: String, depth: Int) {
            if (out.size >= 1500) return
            when (node) {
                is JSONObject -> {
                    val isCol = collapsed[path] == true && q.isEmpty()
                    out += JsonTreeRow(path, depth, key, "{ ${node.length()} 键 }", true)
                    if (!isCol) {
                        val iter = node.keys()
                        while (iter.hasNext()) {
                            val k = iter.next()
                            walk(node.opt(k), "$path.$k", k, depth + 1)
                        }
                    }
                }
                is JSONArray -> {
                    val isCol = collapsed[path] == true && q.isEmpty()
                    out += JsonTreeRow(path, depth, key, "[ ${node.length()} 项 ]", true)
                    if (!isCol) {
                        for (i in 0 until minOf(node.length(), 400)) {
                            walk(node.opt(i), "$path[$i]", "[$i]", depth + 1)
                        }
                    }
                }
                else -> {
                    val v = node?.toString() ?: "null"
                    if (q.isEmpty() || key.lowercase().contains(q) || v.lowercase().contains(q)) {
                        out += JsonTreeRow(path, depth, key, v.take(240), false)
                    }
                }
            }
        }
        walk(parsed, "$", "$", 0)
        out
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchBar(value = query, onValueChange = { query = it }, placeholder = "搜索 JSON 键或值…")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(rows, key = { it.path }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (row.expandable) collapsed[row.path] = !(collapsed[row.path] == true)
                            else scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("json", "${row.path} = ${row.valuePreview}").toClipEntry())
                                app.conversations.notify("已复制 ${row.path}")
                            }
                        }
                        .padding(start = (minOf(row.depth, 8) * 14).dp, top = 4.dp, bottom = 4.dp, end = 6.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (row.expandable) (if (collapsed[row.path] == true) "▸" else "▾") else "·",
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        color = G.accent,
                    )
                    Text(row.keyLabel, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = G.artifact)
                    Text(row.valuePreview, fontFamily = Mono, fontSize = 12.sp, color = if (row.expandable) G.inkMuted else G.ink, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 2. CSV / TSV 冻结表头二维表格查看器。 */
@Composable
internal fun CsvTableViewer(file: File, isTsv: Boolean) {
    var query by remember { mutableStateOf("") }
    val tableState by produceState<List<List<String>>?>(initialValue = null, key1 = file, key2 = isTsv) {
        value = withContext(Dispatchers.IO) {
            val delim = if (isTsv) '\t' else ','
            runCatching {
                file.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.take(1000).map { line -> parseCsvRow(line, delim) }.toList()
                }
            }.getOrDefault(emptyList())
        }
    }
    val table = tableState ?: return
    val headers = table.firstOrNull().orEmpty()
    val body = remember(table, query) {
        val q = query.trim().lowercase()
        val data = table.drop(1)
        if (q.isEmpty()) data else data.filter { row -> row.any { it.lowercase().contains(q) } }
    }
    val hScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchBar(value = query, onValueChange = { query = it }, placeholder = "筛选表格行（共 ${body.size} 行 · ${headers.size} 列）…")
        if (headers.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(G.surfaceSubtle).horizontalScroll(hScroll).padding(vertical = 8.dp, horizontal = 6.dp)) {
                headers.forEach { col ->
                    Text(col, fontFamily = Mono, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(128.dp).padding(horizontal = 6.dp))
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(body.size) { idx ->
                val row = body[idx]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (idx % 2 == 0) G.surface else G.surfaceSubtle.copy(alpha = 0.5f))
                        .horizontalScroll(hScroll)
                        .padding(vertical = 7.dp, horizontal = 6.dp),
                ) {
                    for (c in 0 until maxOf(headers.size, row.size)) {
                        Text(row.getOrElse(c) { "" }, fontFamily = Mono, fontSize = 11.5.sp, color = G.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(128.dp).padding(horizontal = 6.dp))
                    }
                }
            }
        }
    }
}

private fun parseCsvRow(line: String, delim: Char): List<String> {
    val res = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch == '"' -> {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ }
                else inQuotes = !inQuotes
            }
            ch == delim && !inQuotes -> { res += cur.toString(); cur.clear() }
            else -> cur.append(ch)
        }
        i++
    }
    res += cur.toString()
    return res
}

/** 3. SQLite 只读数据库浏览器（表清单 + 分页记录 + 自定义只读 SELECT 查询）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SqliteBrowserViewer(file: File) {
    var tables by remember(file) { mutableStateOf<List<String>>(emptyList()) }
    var selectedTable by remember(file) { mutableStateOf<String?>(null) }
    var sqlInput by remember(file) { mutableStateOf("") }
    var columns by remember { mutableStateOf<List<String>>(emptyList()) }
    var rows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun runQuery(sql: String) {
        scope.launch {
            error = null
            try {
                val (cols, data) = withContext(Dispatchers.IO) {
                    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                        db.rawQuery(sql, null).use { c ->
                            val cs = c.columnNames.toList()
                            val rs = mutableListOf<List<String>>()
                            while (c.moveToNext() && rs.size < 300) {
                                rs += List(cs.size) { idx ->
                                    when (c.getType(idx)) {
                                        android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                                        android.database.Cursor.FIELD_TYPE_BLOB -> "<BLOB ${c.getBlob(idx)?.size ?: 0}B>"
                                        else -> c.getString(idx).orEmpty()
                                    }
                                }
                            }
                            cs to rs
                        }
                    }
                }
                columns = cols; rows = data
            } catch (e: Exception) { error = e.message }
        }
    }

    LaunchedEffect(file) {
        runCatching {
            withContext(Dispatchers.IO) {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(0)) }
                    }
                }
            }
        }.onSuccess { list ->
            tables = list
            list.firstOrNull()?.let { first ->
                selectedTable = first
                sqlInput = "SELECT * FROM \"$first\" LIMIT 100"
                runQuery(sqlInput)
            }
        }.onFailure { error = "无法打开 SQLite 数据库：${it.message}" }
    }

    val hScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tables.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                items(tables) { t ->
                    SelectableChip(t, selected = selectedTable == t, onClick = {
                        selectedTable = t
                        sqlInput = "SELECT * FROM \"$t\" LIMIT 100"
                        runQuery(sqlInput)
                    })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = sqlInput,
                onValueChange = { sqlInput = it },
                label = { Text("只读 SQL (SELECT)") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.weight(1f),
            )
            PrimaryPillButton("查询", enabled = sqlInput.isNotBlank(), onClick = { runQuery(sqlInput) })
        }
        error?.let { Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall) }
        if (columns.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(G.surfaceSubtle).horizontalScroll(hScroll).padding(6.dp)) {
                columns.forEach { c ->
                    Text(c, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = G.ink, modifier = Modifier.width(120.dp).padding(horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows.size) { i ->
                    val r = rows[i]
                    Row(Modifier.fillMaxWidth().background(if (i % 2 == 0) G.surface else G.surfaceSubtle.copy(alpha = 0.45f)).horizontalScroll(hScroll).padding(6.dp)) {
                        r.forEach { cell ->
                            Text(cell, fontFamily = Mono, fontSize = 11.sp, color = G.ink, modifier = Modifier.width(120.dp).padding(horizontal = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** 4. 压缩包免解压透视浏览器（支持查看包内文件列表、单文件免解压预览与一键解压）。 */
@Composable
internal fun ArchiveInspectorViewer(app: GaliaisApplication, fileRef: FileRef, file: File, onExtracted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember(file) { mutableStateOf<List<ArchiveItem>>(emptyList()) }
    var previewEntry by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        if (fileRef.name.lowercase().let { it.endsWith(".zip") || it.endsWith(".jar") || it.endsWith(".aar") || it.endsWith(".apk") }) {
            runCatching { withContext(Dispatchers.IO) { ArchiveEngine.listZipEntries(file) } }
                .onSuccess { items = it }
                .onFailure { error = it.message }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("压缩包条目（${items.size}）", style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
            PrimaryPillButton(if (busy) "解压中…" else "解压到同名文件夹", enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    runCatching {
                        val folderName = fileRef.name.substringBeforeLast('.').ifBlank { "extracted" }
                        val dst = joinVfsPath(fileRef.parentPath, folderName)
                        app.fileManager.mkdir(fileRef.scope, dst)
                        val count = app.fileManager.extractArchive(fileRef, fileRef.scope, dst)
                        app.conversations.notify("已解压 $count 个文件到 $folderName/")
                        onExtracted()
                    }.onFailure { error = "解压失败：${it.message}" }
                    busy = false
                }
            })
        }
        error?.let { Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(items, key = { idx, entry -> "${entry.path}#$idx" }) { _, entry ->
                Surface(
                    onClick = {
                        if (!entry.isDirectory) {
                            scope.launch {
                                runCatching {
                                    val bytes = withContext(Dispatchers.IO) { ArchiveEngine.readZipEntryBytes(file, entry.path, 512 * 1024) }
                                    val text = if (TextCodec.looksBinary(bytes)) "（二进制文件，${formatFileSize(bytes.size.toLong())}）" else TextCodec.decodeBytes(bytes).text
                                    previewEntry = entry.path to text
                                }.onFailure { app.conversations.notify("预览失败：${it.message}") }
                            }
                        }
                    },
                    shape = D.shape(8.dp),
                    color = G.surface,
                    border = BorderStroke(0.5.dp, G.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            if (entry.isDirectory) GaliaisIcons.Folder else GaliaisIcons.File,
                            contentDescription = null,
                            tint = if (entry.isDirectory) G.codex else G.inkMuted,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(entry.path, fontFamily = Mono, fontSize = 12.sp, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!entry.isDirectory) Text(formatFileSize(entry.size), fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                    }
                }
            }
        }
    }

    previewEntry?.let { (path, body) ->
        GaliaisAlertDialog(
            onDismissRequest = { previewEntry = null },
            title = path.substringAfterLast('/'),
            text = {
                Box(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(body.take(40_000), fontFamily = Mono, fontSize = 11.5.sp, color = G.ink) }
                }
            },
            confirmButton = { TextButton(onClick = { previewEntry = null }) { Text("关闭", color = G.accent, fontWeight = FontWeight.SemiBold) } },
        )
    }
}

/** 5. APK 静态分析与一键安装面板。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApkInspectorViewer(app: GaliaisApplication, file: File) {
    val context = LocalContext.current
    var info by remember(file) { mutableStateOf<ApkInspectInfo?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        runCatching { withContext(Dispatchers.IO) { ArchiveEngine.inspectApk(context, file) } }
            .onSuccess { info = it }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { CrispCard { Text(it, color = G.danger) } }
        info?.let { apk ->
            CrispCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(apk.appLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink)
                    Text("${apk.packageName} · v${apk.versionName} (${apk.versionCode})", fontFamily = Mono, fontSize = 12.sp, color = G.inkMuted)
                    Text("minSdk ${apk.minSdk} · targetSdk ${apk.targetSdk} · ${formatFileSize(file.length())}", fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted)
                    if (apk.abis.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            apk.abis.forEach { TagPill(it, fg = G.accent, bg = G.accentContainer) }
                        }
                    }
                    apk.signingSha256?.let {
                        SelectionContainer { Text("签名 SHA-256：\n$it", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted) }
                    }
                    PrimaryPillButton("调起系统安装此 APK", onClick = {
                        runCatching { ExternalStorageBridge.openWithExternalApp(context, file, file.name, "application/vnd.android.package-archive") }
                            .onFailure { app.conversations.notify("无法调起安装：${it.message}") }
                    })
                }
            }
            if (apk.permissions.isNotEmpty()) {
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("声明权限 (${apk.permissions.size})", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        apk.permissions.forEach { p -> Text(p, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted) }
                    }
                }
            }
        }
    }
}

/** 6. 字体样本预览器（TTF / OTF）。 */
@Composable
internal fun FontSpecimenViewer(file: File) {
    var sample by remember { mutableStateOf("Solbraid 文件管理器 · The quick brown fox jumps over 0123456789") }
    val fontState by produceState<Pair<Boolean, FontFamily?>>(initialValue = false to null, key1 = file) {
        val ff = withContext(Dispatchers.IO) {
            runCatching { FontFamily(Typeface.createFromFile(file)) }.getOrNull()
        }
        value = true to ff
    }
    if (!fontState.first) return
    val fontFamily = fontState.second
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = sample, onValueChange = { sample = it }, label = { Text("自定义预览文本") }, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth())
        if (fontFamily == null) {
            CrispCard { Text("无法加载该字体格式进行渲染预览。", color = G.danger) }
        } else {
            listOf(14, 20, 28, 40).forEach { spSize ->
                CrispCard {
                    Text("${spSize}sp", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted)
                    Text(sample, fontFamily = fontFamily, fontSize = spSize.sp, lineHeight = (spSize * 1.35f).sp, color = G.ink)
                }
            }
        }
    }
}

/** 7. 十六进制（Hex + ASCII）二进制查看器与文件魔数/哈希摘要校验器。 */
@Composable
internal fun HexBinaryInspector(app: GaliaisApplication, fileRef: FileRef, file: File) {
    val scope = rememberCoroutineScope()
    var pageOffset by remember(file) { mutableLongStateOf(0L) }
    var hexData by remember(file) { mutableStateOf(ByteArray(0)) }
    var totalBytes by remember(file) { mutableLongStateOf(file.length()) }
    var magicDesc by remember(file) { mutableStateOf("") }
    var hashes by remember(file) { mutableStateOf<FileHashes?>(null) }
    var hashing by remember { mutableStateOf(false) }

    LaunchedEffect(file, pageOffset) {
        val (chunk, total) = withContext(Dispatchers.IO) { TextCodec.readHexPage(file, pageOffset, 2048) }
        hexData = chunk; totalBytes = total
        if (pageOffset == 0L) magicDesc = TextCodec.detectMagicDescription(chunk)
    }

    val hexLines = remember(hexData, pageOffset) {
        val out = ArrayList<String>((hexData.size + 15) / 16)
        var off = 0
        while (off < hexData.size) {
            val sb = StringBuilder()
            sb.append("%08X  ".format(pageOffset + off))
            for (i in 0 until 16) {
                if (off + i < hexData.size) sb.append("%02X ".format(hexData[off + i].toInt() and 0xFF))
                else sb.append("   ")
                if (i == 7) sb.append(' ')
            }
            sb.append(" |")
            for (i in 0 until 16) {
                if (off + i < hexData.size) {
                    val b = hexData[off + i].toInt() and 0xFF
                    sb.append(if (b in 32..126) b.toChar() else '.')
                }
            }
            sb.append('|')
            out += sb.toString()
            off += 16
        }
        out
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CrispCard(padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(magicDesc.ifBlank { "二进制文件" }, style = MaterialTheme.typography.titleSmall, color = G.ink)
                    Text("${formatFileSize(totalBytes)} (${totalBytes} bytes)", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                }
                SecondaryPillButton(if (hashing) "计算中…" else "计算校验和", enabled = !hashing, onClick = {
                    scope.launch {
                        hashing = true
                        hashes = runCatching { withContext(Dispatchers.IO) { TextCodec.computeHashes(file) } }.getOrNull()
                        hashing = false
                    }
                })
            }
            hashes?.let { h ->
                SelectionContainer {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("MD5:    ${h.md5}", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted)
                        Text("SHA1:   ${h.sha1}", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted)
                        Text("SHA256: ${h.sha256}", fontFamily = Mono, fontSize = 10.5.sp, color = G.ink)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("偏移 0x%X / 0x%X".format(pageOffset, totalBytes), fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, modifier = Modifier.weight(1f))
            TagPill("上一页", fg = G.ink, bg = G.surfaceSubtle, onClick = if (pageOffset > 0L) ({ pageOffset = (pageOffset - 2048L).coerceAtLeast(0L) }) else null)
            TagPill("下一页", fg = G.ink, bg = G.surfaceSubtle, onClick = if (pageOffset + 2048L < totalBytes) ({ pageOffset += 2048L }) else null)
        }
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = D.codeShape, color = G.codeBg, border = BorderStroke(0.75.dp, G.codeBorder)) {
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(10.dp)) {
                    items(hexLines) { line -> Text(line, fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp, color = G.codeInk) }
                }
            }
        }
    }
}
