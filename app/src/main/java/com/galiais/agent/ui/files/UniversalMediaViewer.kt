package com.galiais.agent.ui.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.fs.ExternalStorageBridge
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.TextCodec
import com.galiais.agent.engine.fs.ViewerCategory
import com.galiais.agent.engine.fs.formatFileSize
import com.galiais.agent.ui.ArtifactStudioSheet
import com.galiais.agent.ui.CompactActionButton
import com.galiais.agent.ui.CrispCard
import com.galiais.agent.ui.D
import com.galiais.agent.ui.G
import com.galiais.agent.ui.GaliaisDropdownMenu
import com.galiais.agent.ui.GaliaisIcons
import com.galiais.agent.ui.GaliaisMenuRow
import com.galiais.agent.ui.Hairline
import com.galiais.agent.ui.HeaderProgressSlot
import com.galiais.agent.ui.Mono
import com.galiais.agent.ui.PrimaryPillButton
import com.galiais.agent.ui.SecondaryPillButton
import com.galiais.agent.ui.SyncFullscreenDialogWindow
import com.galiais.agent.ui.TagPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 统一全格式文件查看器路由对话框：
 * 支持图片/动图缩放相册、PDF 原生阅读器、视频/音频播放器、JSON 树、CSV 表格、
 * SQLite 浏览器、压缩包免解压透视、APK 解析、字体预览、Hex 查看器及无缝切换代码编辑器。
 */
@Composable
fun UniversalFileViewerDialog(
    app: GaliaisApplication,
    fileRef: FileRef,
    siblingImages: List<FileRef> = listOf(fileRef),
    onOpenInEditor: (FileRef) -> Unit,
    onSaveExternal: (FileRef) -> Unit,
    onExtracted: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val fm = app.fileManager
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val initialCategory = remember(fileRef) { TextCodec.classifyFile(fileRef.name, ByteArray(0)) }
    var loading by remember(fileRef) { mutableStateOf(true) }
    var error by remember(fileRef) { mutableStateOf<String?>(null) }
    var stagedFile by remember(fileRef) { mutableStateOf<File?>(null) }
    var category by remember(fileRef) { mutableStateOf(initialCategory) }
    var studioText by remember(fileRef) { mutableStateOf<String?>(null) }
    var forceHex by remember(fileRef) { mutableStateOf(false) }
    var activeRef by remember(fileRef) { mutableStateOf(fileRef) }

    LaunchedEffect(fileRef) {
        loading = true; error = null; forceHex = false; activeRef = fileRef; studioText = null
        try {
            val f = fm.stageReadableFile(fileRef)
            val (resolvedCat, loadedText) = withContext(Dispatchers.IO) {
                val head = f.inputStream().use { inp -> ByteArray(512).let { b -> b.copyOf(inp.read(b).coerceAtLeast(0)) } }
                val cat = TextCodec.classifyFile(fileRef.name, head)
                val txt = if (cat in setOf(ViewerCategory.SVG, ViewerCategory.MARKDOWN, ViewerCategory.HTML)) {
                    runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
                } else null
                cat to txt
            }
            stagedFile = f
            category = resolvedCat
            studioText = loadedText
        } catch (e: Exception) {
            error = "加载文件失败：${e.message}"
        } finally {
            loading = false
        }
    }

    // SVG / Markdown / HTML 首帧直接承接为单次 ArtifactStudioSheet，彻底消除先弹空 Dialog 再销毁重建的闪烁，并与文件 IO 并行预热 WebView
    if (!forceHex && category in setOf(ViewerCategory.SVG, ViewerCategory.MARKDOWN, ViewerCategory.HTML)) {
        val kind = when (category) {
            ViewerCategory.SVG -> ArtifactKind.SVG
            ViewerCategory.HTML -> ArtifactKind.HTML
            else -> ArtifactKind.MARKDOWN
        }
        val text = studioText ?: ""
        ArtifactStudioSheet(
            app = app,
            artifacts = listOf(ChatArtifact("view:${fileRef.path}", fileRef.name, fileRef.name, kind, fileRef.name.substringAfterLast('.', ""), text)),
            initialArtifactId = "view:${fileRef.path}",
            cwd = fileRef.parentPath,
            savePath = if (fileRef.scope.id.startsWith("linux")) fileRef.path else null,
            externalLoading = loading || (error == null && studioText == null),
            externalError = error,
            onDismiss = onDismiss,
        )
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        SyncFullscreenDialogWindow()
        var viewerMenu by remember { mutableStateOf(false) }
        Surface(color = G.canvas, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // 单层融合顶栏（56/64dp）：保留 1 个高频动作 + 溢出菜单，彻底去除旧版重复双顶栏
                Row(
                    modifier = Modifier.fillMaxWidth().height(if (D.material) 64.dp else 56.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(GaliaisIcons.ArrowBack, contentDescription = "关闭", tint = G.ink, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(activeRef.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${stagedFile?.let { formatFileSize(it.length()) } ?: "…"} · ${activeRef.scope.shortLabel}",
                            fontFamily = Mono,
                            fontSize = 10.5.sp,
                            color = G.inkMuted,
                            maxLines = 1,
                        )
                    }
                    if (category in setOf(ViewerCategory.JSON_STRUCTURED, ViewerCategory.CSV_TABLE, ViewerCategory.TEXT_CODE, ViewerCategory.BINARY_HEX)) {
                        CompactActionButton("编辑", icon = GaliaisIcons.FilePen, primary = true, onClick = { onDismiss(); onOpenInEditor(activeRef) })
                    } else {
                        CompactActionButton("导出", icon = GaliaisIcons.Download, primary = true, onClick = { onSaveExternal(activeRef) })
                    }
                    Box {
                        IconButton(onClick = { viewerMenu = true }) {
                            Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多操作", tint = G.inkMuted, modifier = Modifier.size(20.dp))
                        }
                        GaliaisDropdownMenu(expanded = viewerMenu, onDismissRequest = { viewerMenu = false }) {
                            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                GaliaisMenuRow("导出 / 另存为", icon = GaliaisIcons.Download, onClick = { viewerMenu = false; onSaveExternal(activeRef) })
                                if (stagedFile != null && category in setOf(ViewerCategory.IMAGE, ViewerCategory.ANIMATED_IMAGE, ViewerCategory.VIDEO)) {
                                    GaliaisMenuRow("保存到相册", icon = GaliaisIcons.Image, onClick = {
                                        viewerMenu = false
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    ExternalStorageBridge.saveToGallery(context, stagedFile!!, activeRef.name, isVideo = category == ViewerCategory.VIDEO)
                                                }
                                            }.onSuccess { app.conversations.notify("已保存到相册：$it") }
                                                .onFailure { app.conversations.notify("保存相册失败：${it.message}") }
                                        }
                                    })
                                }
                                if (stagedFile != null) {
                                    GaliaisMenuRow("系统分享", icon = GaliaisIcons.Share, onClick = {
                                        viewerMenu = false
                                        runCatching { ExternalStorageBridge.shareFile(context, stagedFile!!, activeRef.name) }
                                            .onFailure { app.conversations.notify("分享失败：${it.message}") }
                                    })
                                    GaliaisMenuRow("用其他应用打开", icon = GaliaisIcons.Link, onClick = {
                                        viewerMenu = false
                                        runCatching { ExternalStorageBridge.openWithExternalApp(context, stagedFile!!, activeRef.name) }
                                            .onFailure { app.conversations.notify("无法调起外部应用：${it.message}") }
                                    })
                                }
                                if (category != ViewerCategory.BINARY_HEX) {
                                    Hairline(Modifier.padding(vertical = 2.dp))
                                    GaliaisMenuRow(
                                        title = if (forceHex) "返回默认视图" else "Hex 十六进制透视",
                                        icon = GaliaisIcons.Code,
                                        onClick = { viewerMenu = false; forceHex = !forceHex },
                                    )
                                }
                            }
                        }
                    }
                }
                HeaderProgressSlot(loading)

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        error != null -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                            CrispCard { Text(error!!, color = G.danger, style = MaterialTheme.typography.bodyMedium) }
                        }
                        stagedFile == null -> Unit
                        forceHex || category == ViewerCategory.BINARY_HEX -> HexBinaryInspector(app, activeRef, stagedFile!!)
                        category == ViewerCategory.IMAGE || category == ViewerCategory.ANIMATED_IMAGE -> ImageGalleryViewer(
                            app = app,
                            initialRef = fileRef,
                            initialFile = stagedFile,
                            siblings = siblingImages.ifEmpty { listOf(fileRef) },
                            onActiveChanged = { ref, file -> activeRef = ref; if (file != null) stagedFile = file },
                        )
                        category == ViewerCategory.PDF -> NativePdfViewer(stagedFile!!)
                        category == ViewerCategory.VIDEO -> NativeVideoPlayer(stagedFile!!)
                        category == ViewerCategory.AUDIO -> NativeAudioPlayer(stagedFile!!, activeRef.name)
                        category == ViewerCategory.JSON_STRUCTURED -> JsonTreeViewer(app, stagedFile!!)
                        category == ViewerCategory.CSV_TABLE -> CsvTableViewer(stagedFile!!, isTsv = activeRef.name.lowercase().endsWith(".tsv"))
                        category == ViewerCategory.SQLITE -> SqliteBrowserViewer(stagedFile!!)
                        category == ViewerCategory.ARCHIVE -> ArchiveInspectorViewer(app, activeRef, stagedFile!!, onExtracted = onExtracted)
                        category == ViewerCategory.APK -> ApkInspectorViewer(app, stagedFile!!)
                        category == ViewerCategory.FONT -> FontSpecimenViewer(stagedFile!!)
                        else -> HexBinaryInspector(app, activeRef, stagedFile!!)
                    }
                }
            }
        }
    }
}

/** 专业图片与动图查看器：支持同目录左右滑相册、双指缩放平移、双击放大、90° 旋转、透明棋盘格、GIF/WebP 动图与 EXIF 详情。 */
@Composable
private fun ImageGalleryViewer(
    app: GaliaisApplication,
    initialRef: FileRef,
    initialFile: File?,
    siblings: List<FileRef>,
    onActiveChanged: (FileRef, File?) -> Unit,
) {
    val startIdx = remember(initialRef, siblings) { siblings.indexOfFirst { it == initialRef }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIdx, pageCount = { siblings.size })
    var rotation by remember(pager.currentPage) { mutableFloatStateOf(0f) }
    var checkerboard by remember { mutableStateOf(false) }
    var showExif by remember { mutableStateOf(false) }
    var currentFile by remember(initialRef) { mutableStateOf<File?>(initialFile) }

    LaunchedEffect(pager.currentPage) {
        val ref = siblings[pager.currentPage]
        val f = if (ref == initialRef && initialFile != null) initialFile
        else runCatching { app.fileManager.stageReadableFile(ref) }.getOrNull()
        currentFile = f
        onActiveChanged(ref, f)
    }

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            val ref = siblings[page]
            var localFile by remember(ref) { mutableStateOf<File?>(if (ref == initialRef) initialFile else null) }
            var scale by remember(ref) { mutableFloatStateOf(1f) }
            var offset by remember(ref) { mutableStateOf(Offset.Zero) }
            LaunchedEffect(ref) {
                if (localFile == null) {
                    localFile = runCatching { app.fileManager.stageReadableFile(ref) }.getOrNull()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (checkerboard) Color.Transparent else Color(0xFF0E1014))
                    .pointerInput(ref) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1.2f) { scale = 1f; offset = Offset.Zero } else scale = 2.6f
                        })
                    }
                    .pointerInput(ref) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (pressedCount > 1 || scale > 1.02f) {
                                    val nextScale = (scale * zoomChange).coerceIn(1f, 8f)
                                    scale = nextScale
                                    if (nextScale > 1.02f) {
                                        val maxX = (size.width * (nextScale - 1f)) / 2f
                                        val maxY = (size.height * (nextScale - 1f)) / 2f
                                        offset = Offset(
                                            (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                            (offset.y + panChange.y).coerceIn(-maxY, maxY),
                                        )
                                    } else {
                                        offset = Offset.Zero
                                    }
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (checkerboard) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cell = 24f
                        val cols = (size.width / cell).toInt() + 1
                        val rows = (size.height / cell).toInt() + 1
                        for (r in 0 until rows) for (c in 0 until cols) {
                            drawRect(if ((r + c) % 2 == 0) Color(0xFFDCDCDC) else Color(0xFF9E9E9E), Offset(c * cell, r * cell), Size(cell, cell))
                        }
                    }
                }
                val file = localFile
                if (file != null) {
                    val ext = ref.name.substringAfterLast('.', "").lowercase()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ext in setOf("gif", "webp")) {
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                            },
                            update = { iv ->
                                if (iv.tag != file.absolutePath) {
                                    iv.tag = file.absolutePath
                                    runCatching {
                                        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                                        iv.setImageDrawable(drawable)
                                        if (drawable is AnimatedImageDrawable) drawable.start()
                                    }.onFailure {
                                        BitmapFactory.decodeFile(file.absolutePath)?.let { iv.setImageBitmap(it) }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale; scaleY = scale
                                    translationX = offset.x; translationY = offset.y
                                    rotationZ = rotation
                                },
                        )
                    } else {
                        val decoded by produceState<Pair<Boolean, Bitmap?>>(initialValue = false to null, key1 = file) {
                            val bm = withContext(Dispatchers.IO) {
                                runCatching {
                                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(file.absolutePath, opts)
                                    var sample = 1
                                    while (opts.outWidth / sample > 2560 || opts.outHeight / sample > 2560) sample *= 2
                                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                                }.getOrNull()
                            }
                            value = true to bm
                        }
                        val (ready, bmp) = decoded
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = ref.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale; scaleY = scale
                                        translationX = offset.x; translationY = offset.y
                                        rotationZ = rotation
                                    },
                            )
                        } else if (ready) {
                            Text("图片解码失败", color = Color.White)
                        }
                    }
                }
            }
        }
        // 底部控制条
        Row(
            modifier = Modifier.fillMaxWidth().background(G.surface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${pager.currentPage + 1} / ${siblings.size}", fontFamily = Mono, fontSize = 12.sp, color = G.inkMuted)
            Spacer(Modifier.weight(1f))
            TagPill("旋转 90°", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { rotation = (rotation + 90f) % 360f })
            TagPill(if (checkerboard) "深色底" else "透明网格", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { checkerboard = !checkerboard })
            TagPill("EXIF", fg = G.accent, bg = G.accentContainer, onClick = { showExif = !showExif })
        }
        if (showExif && currentFile != null) {
            val exifSummary = remember(currentFile) {
                runCatching {
                    val ex = ExifInterface(currentFile!!.absolutePath)
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(currentFile!!.absolutePath, opts)
                    buildList {
                        add("分辨率：${opts.outWidth} × ${opts.outHeight}")
                        ex.getAttribute(ExifInterface.TAG_MODEL)?.let { add("设备：$it") }
                        ex.getAttribute(ExifInterface.TAG_DATETIME)?.let { add("拍摄时间：$it") }
                        ex.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { add("光圈：f/$it") }
                        ex.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { add("ISO：$it") }
                    }.joinToString(" · ")
                }.getOrDefault("无 EXIF 拍摄信息")
            }
            Text(exifSummary, style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.fillMaxWidth().background(G.surfaceSubtle).padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

/** 原生零依赖 PDF 阅读器（基于 Android `PdfRenderer`，支持懒渲染、缩放、跳页与护眼反色）。 */
@Composable
private fun NativePdfViewer(file: File) {
    var pageCount by remember(file) { mutableIntStateOf(0) }
    var invertDark by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var rendererHolder by remember(file) { mutableStateOf<Pair<ParcelFileDescriptor, PdfRenderer>?>(null) }

    LaunchedEffect(file) {
        error = null
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(pfd)
                pfd to r
            }
        }
        opened.onSuccess { pair ->
            rendererHolder = pair
            pageCount = pair.second.pageCount
        }.onFailure {
            error = "PDF 打开失败（可能加密或损坏）：${it.message}"
        }
    }
    DisposableEffect(rendererHolder) {
        val current = rendererHolder
        onDispose {
            runCatching { current?.second?.close() }
            runCatching { current?.first?.close() }
        }
    }

    if (error != null) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) { CrispCard { Text(error!!, color = G.danger) } }
        return
    }
    val renderer = rendererHolder?.second ?: return

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(G.surfaceSubtle).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("共 $pageCount 页", fontFamily = Mono, fontSize = 12.sp, color = G.inkMuted)
            Spacer(Modifier.weight(1f))
            TagPill("缩放 ${(scale * 100).toInt()}%", fg = G.ink, bg = G.surface, border = G.border, onClick = {
                scale = when { scale < 1.3f -> 1.5f; scale < 1.9f -> 2.0f; else -> 1.0f }
            })
            TagPill(if (invertDark) "原色模式" else "深色反色", fg = G.accent, bg = G.accentContainer, onClick = { invertDark = !invertDark })
        }
        val invertFilter = remember {
            ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(G.surfaceSubtle)) {
            val basePageWidth = (maxWidth - 24.dp).coerceAtLeast(240.dp)
            val scaledWidth = basePageWidth * scale
            val hScroll = rememberScrollState()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pageCount) { idx ->
                    var bmp by remember(file, idx) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(file, idx, scale) {
                        val rendered = withContext(Dispatchers.IO) {
                            synchronized(renderer) {
                                runCatching {
                                    renderer.openPage(idx).use { page ->
                                        val w = (page.width * 2 * scale).toInt().coerceIn(320, 2400)
                                        val h = (page.height * 2 * scale).toInt().coerceIn(320, 3200)
                                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                                            bitmap.eraseColor(android.graphics.Color.WHITE)
                                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        }
                                    }
                                }.getOrNull()
                            }
                        }
                        if (rendered != null) bmp = rendered
                    }
                    Surface(shape = D.shape(8.dp), color = Color.White, border = BorderStroke(0.5.dp, G.border), modifier = Modifier.fillMaxWidth()) {
                        Column {
                            if (bmp != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .let { if (scale > 1.01f) it.horizontalScroll(hScroll) else it },
                                ) {
                                    Image(
                                        bitmap = bmp!!.asImageBitmap(),
                                        contentDescription = "第 ${idx + 1} 页",
                                        contentScale = ContentScale.FillWidth,
                                        colorFilter = if (invertDark) invertFilter else null,
                                        modifier = Modifier.width(scaledWidth),
                                    )
                                }
                            } else {
                                Box(Modifier.fillMaxWidth().aspectRatio(0.72f), contentAlignment = Alignment.Center) {
                                    Text("渲染第 ${idx + 1} 页…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                }
                            }
                            Text("第 ${idx + 1} / $pageCount 页", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted, modifier = Modifier.align(Alignment.End).padding(6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 原生视频播放器（硬件解码 + 播放/暂停 + 进度条 + 倍速 + 静音 + 媒体参数）。 */
@Composable
private fun NativeVideoPlayer(file: File) {
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(true) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var posMs by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var muted by remember { mutableStateOf(false) }

    val meta = remember(file) {
        runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).orEmpty()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).orEmpty()
            val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { "${it / 1000} kbps" }.orEmpty()
            r.release()
            listOfNotNull(if (w.isNotBlank() && h.isNotBlank()) "${w}×$h" else null, br.ifBlank { null }).joinToString(" · ")
        }.getOrDefault("")
    }

    DisposableEffect(file) {
        onDispose { runCatching { videoViewRef?.stopPlayback() } }
    }

    LaunchedEffect(playing, videoViewRef) {
        while (true) {
            val vv = videoViewRef
            if (vv != null && vv.duration > 0) {
                durationMs = vv.duration.toLong()
                posMs = vv.currentPosition.toLong()
            }
            delay(350)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(Uri.fromFile(file))
                        setOnPreparedListener { mp ->
                            mediaPlayerRef = mp
                            mp.isLooping = false
                            durationMs = mp.duration.toLong()
                            start()
                            playing = true
                        }
                        setOnCompletionListener { playing = false }
                        videoViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Surface(color = G.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (meta.isNotBlank()) Text(meta, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                Slider(
                    value = if (durationMs > 0) (posMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { frac ->
                        val target = (frac * durationMs).toInt()
                        videoViewRef?.seekTo(target)
                        posMs = target.toLong()
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${formatMediaMs(posMs)} / ${formatMediaMs(durationMs)}", fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted)
                    Spacer(Modifier.weight(1f))
                    TagPill("${speed}x", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = {
                        speed = when { speed < 0.9f -> 1.0f; speed < 1.2f -> 1.25f; speed < 1.45f -> 1.5f; speed < 1.9f -> 2.0f; else -> 0.5f }
                        runCatching { mediaPlayerRef?.let { it.playbackParams = it.playbackParams.setSpeed(speed) } }
                    })
                    TagPill(if (muted) "取消静音" else "静音", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = {
                        muted = !muted
                        val vol = if (muted) 0f else 1f
                        runCatching { mediaPlayerRef?.setVolume(vol, vol) }
                    })
                    PrimaryPillButton(if (playing) "暂停" else "播放", onClick = {
                        val vv = videoViewRef ?: return@PrimaryPillButton
                        if (vv.isPlaying) { vv.pause(); playing = false } else { vv.start(); playing = true }
                    })
                }
            }
        }
    }
}

/** 原生音频播放器（含元数据提取、进度拖动、快进/快退与倍速）。 */
@Composable
private fun NativeAudioPlayer(file: File, fileName: String) {
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var posMs by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var loop by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val meta = remember(file) {
        runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(file.absolutePath)
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().ifBlank { fileName }
            val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { "${it / 1000} kbps" }.orEmpty()
            r.release()
            Triple(title, artist, br)
        }.getOrDefault(Triple(fileName, "", ""))
    }

    val mp = remember(file) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                durationMs = duration.toLong()
                setOnCompletionListener { if (!isLooping) playing = false }
            }
        }.onFailure { error = "音频解码失败：${it.message}" }.getOrNull()
    }
    DisposableEffect(mp) {
        onDispose { runCatching { mp?.stop(); mp?.release() } }
    }
    LaunchedEffect(mp, playing) {
        while (mp != null) {
            runCatching { posMs = mp.currentPosition.toLong() }
            delay(300)
        }
    }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        CrispCard(padding = PaddingValues(20.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(meta.first, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = G.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (meta.second.isNotBlank() || meta.third.isNotBlank()) {
                    Text(listOf(meta.second, meta.third).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                }
                error?.let { Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall) }
                Slider(
                    value = if (durationMs > 0) (posMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { frac ->
                        val t = (frac * durationMs).toInt()
                        runCatching { mp?.seekTo(t); posMs = t.toLong() }
                    },
                )
                Text("${formatMediaMs(posMs)} / ${formatMediaMs(durationMs)}", fontFamily = Mono, fontSize = 12.sp, color = G.inkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryPillButton("-10s", onClick = { mp?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } })
                    PrimaryPillButton(if (playing) "暂停" else "播放", enabled = mp != null, onClick = {
                        mp?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } }
                    })
                    SecondaryPillButton("+10s", onClick = { mp?.let { it.seekTo((it.currentPosition + 10_000).coerceAtMost(it.duration)) } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagPill("倍速 ${speed}x", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = {
                        speed = when { speed < 0.9f -> 1.0f; speed < 1.2f -> 1.25f; speed < 1.45f -> 1.5f; speed < 1.9f -> 2.0f; else -> 0.75f }
                        runCatching { mp?.let { it.playbackParams = it.playbackParams.setSpeed(speed) } }
                    })
                    TagPill(if (loop) "循环:开" else "循环:关", fg = if (loop) G.accent else G.inkMuted, bg = if (loop) G.accentContainer else G.surfaceSubtle, onClick = {
                        loop = !loop
                        mp?.isLooping = loop
                    })
                }
            }
        }
    }
}

private fun formatMediaMs(ms: Long): String {
    val sec = (ms / 1000L).coerceAtLeast(0L)
    val m = sec / 60L
    val s = sec % 60L
    return "%02d:%02d".format(m, s)
}
