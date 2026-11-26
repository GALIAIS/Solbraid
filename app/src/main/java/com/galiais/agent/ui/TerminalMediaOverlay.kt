package com.galiais.agent.ui

import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.terminal.TerminalColorScheme
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** 判断路径或 URL 是否为终端可直接播放的视频/流媒体格式。 */
internal fun isTerminalVideoSource(raw: String): Boolean {
    val clean = raw.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    return clean.endsWith(".mp4") || clean.endsWith(".m4v") || clean.endsWith(".webm") ||
        clean.endsWith(".mkv") || clean.endsWith(".mov") || clean.endsWith(".3gp") ||
        clean.endsWith(".m3u8") || clean.endsWith(".ts")
}

/** 判断本地文件路径是否应优先使用全格式富媒体查看器（图片/动图/音频/PDF/MD/HTML/SVG/JSON/CSV/压缩包等）。 */
internal fun isTerminalRichMediaPath(raw: String): Boolean {
    val ext = raw.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext in setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "avif",
        "mp3", "m4a", "wav", "flac", "ogg", "aac", "opus",
        "pdf", "svg", "md", "markdown", "html", "htm",
        "csv", "tsv", "db", "sqlite", "sqlite3", "zip", "tar", "gz", "tgz", "apk",
    )
}

/** 终端视频播放数据源（支持本地/容器文件与 HTTP/HTTPS/HLS 实时流）。 */
internal data class TerminalVideoSource(
    val title: String,
    val remoteUrl: String? = null,
    val localFileRef: FileRef? = null,
)

private fun formatDurationMs(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

/**
 * 终端内置硬件加速视频播放器（支持可拖拽画中画 PiP 悬浮在终端上边看边敲命令，或一键切换全屏）：
 * - 支持本地/Linux 容器视频与 HTTP/HTTPS/HLS (.mp4/.webm/.mkv/.m3u8) 直播；
 * - 内置播放/暂停、进度拖拽、静音、0.75x/1x/1.5x/2x 倍速控制，关闭即释放 MediaPlayer 零泄漏。
 */
@Composable
internal fun TerminalVideoPlayerOverlay(
    app: GaliaisApplication,
    source: TerminalVideoSource,
    scheme: TerminalColorScheme,
    onDismiss: () -> Unit,
) {
    var fullscreen by remember(source) { mutableStateOf(false) }
    var playing by remember(source) { mutableStateOf(true) }
    var muted by remember(source) { mutableStateOf(false) }
    var speed by remember(source) { mutableFloatStateOf(1f) }
    var durationMs by remember(source) { mutableIntStateOf(0) }
    var positionMs by remember(source) { mutableIntStateOf(0) }
    var loading by remember(source) { mutableStateOf(true) }
    var errorMsg by remember(source) { mutableStateOf<String?>(null) }
    var resolvedUri by remember(source) { mutableStateOf<Uri?>(null) }
    var mediaPlayerRef by remember(source) { mutableStateOf<MediaPlayer?>(null) }
    var videoViewRef by remember(source) { mutableStateOf<VideoView?>(null) }
    var pipOffsetX by remember { mutableFloatStateOf(0f) }
    var pipOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(source) {
        loading = true
        errorMsg = null
        resolvedUri = null
        val url = source.remoteUrl
        if (!url.isNullOrBlank()) {
            resolvedUri = Uri.parse(url)
        } else if (source.localFileRef != null) {
            runCatching { app.fileManager.stageReadableFile(source.localFileRef) }
                .onSuccess { staged: File -> resolvedUri = Uri.fromFile(staged) }
                .onFailure { e -> errorMsg = "无法读取视频文件：${e.message}"; loading = false }
        } else {
            errorMsg = "无效的视频地址"
            loading = false
        }
    }

    LaunchedEffect(playing, videoViewRef) {
        while (playing) {
            val vv = videoViewRef
            if (vv != null && vv.isPlaying) {
                positionMs = vv.currentPosition.coerceAtLeast(0)
                if (durationMs <= 0) durationMs = vv.duration.coerceAtLeast(0)
            }
            delay(300L)
        }
    }

    DisposableEffect(source) {
        onDispose {
            runCatching { videoViewRef?.stopPlayback() }
            mediaPlayerRef = null
            videoViewRef = null
        }
    }

    fun applyAudioAndSpeed(mp: MediaPlayer?) {
        if (mp == null) return
        val vol = if (muted) 0f else 1f
        runCatching { mp.setVolume(vol, vol) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
        }
    }

    val playerBody: @Composable (Modifier) -> Unit = { boxMod ->
        Column(
            modifier = boxMod
                .background(Color(0xFF0A0D14)),
        ) {
            // 顶部紧凑控制栏（支持 PiP 拖拽）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(Color(0xFF121722))
                    .let { m ->
                        if (!fullscreen) {
                            m.pointerInput(Unit) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    pipOffsetX += drag.x
                                    pipOffsetY += drag.y
                                }
                            }
                        } else m
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(GaliaisIcons.Play, contentDescription = null, tint = scheme.accentColor, modifier = Modifier.size(13.dp))
                Text(
                    source.title.ifBlank { "终端视频播放" },
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE6EDF3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { fullscreen = !fullscreen }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        if (fullscreen) GaliaisIcons.Minimize else GaliaisIcons.Maximize,
                        contentDescription = if (fullscreen) "切为悬浮窗" else "全屏播放",
                        tint = Color(0xFFB1BAC4),
                        modifier = Modifier.size(14.dp),
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                    Icon(GaliaisIcons.Close, contentDescription = "关闭视频", tint = Color(0xFFB1BAC4), modifier = Modifier.size(14.dp))
                }
            }

            // 视频渲染画布
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val uri = resolvedUri
                if (errorMsg != null) {
                    Text(errorMsg!!, color = TermRed, fontSize = 11.5.sp, modifier = Modifier.padding(12.dp))
                } else if (uri != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setOnPreparedListener { mp ->
                                    mediaPlayerRef = mp
                                    mp.isLooping = true
                                    durationMs = mp.duration.coerceAtLeast(0)
                                    loading = false
                                    applyAudioAndSpeed(mp)
                                    if (playing) start()
                                }
                                setOnErrorListener { _, what, extra ->
                                    loading = false
                                    errorMsg = "视频播放失败 ($what/$extra)"
                                    true
                                }
                                tag = uri.toString()
                                setVideoURI(uri)
                                videoViewRef = this
                            }
                        },
                        update = { vv ->
                            videoViewRef = vv
                            if (vv.tag != uri.toString()) {
                                vv.tag = uri.toString()
                                vv.setVideoURI(uri)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (loading && errorMsg == null) {
                    Text("正在缓冲视频…", fontFamily = Mono, fontSize = 11.sp, color = Color(0xFF8B949E))
                }
            }

            // 底部控制条（播放/暂停 + 进度条 + 倍速 + 静音）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121722))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (durationMs > 0) {
                    Slider(
                        value = positionMs.coerceIn(0, durationMs).toFloat(),
                        onValueChange = { v ->
                            positionMs = v.roundToInt()
                            runCatching { videoViewRef?.seekTo(positionMs) }
                        },
                        valueRange = 0f..durationMs.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = scheme.accentColor,
                            activeTrackColor = scheme.accentColor,
                            inactiveTrackColor = Color(0xFF2B3445),
                        ),
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(scheme.accentColor.copy(alpha = 0.2f))
                            .clickable {
                                val vv = videoViewRef ?: return@clickable
                                if (vv.isPlaying) {
                                    vv.pause()
                                    playing = false
                                } else {
                                    vv.start()
                                    playing = true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (playing) GaliaisIcons.Stop else GaliaisIcons.Play,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = scheme.accentColor,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Text(
                        if (durationMs > 0) "${formatDurationMs(positionMs)} / ${formatDurationMs(durationMs)}" else "LIVE",
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        color = Color(0xFF8B949E),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${speed}x",
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.accentColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1F293A))
                            .clickable {
                                speed = when {
                                    speed < 0.9f -> 1f
                                    speed < 1.3f -> 1.5f
                                    speed < 1.8f -> 2f
                                    else -> 0.75f
                                }
                                applyAudioAndSpeed(mediaPlayerRef)
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(
                        if (muted) "静音" else "有声",
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        color = if (muted) TermAmber else Color(0xFFB1BAC4),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1F293A))
                            .clickable {
                                muted = !muted
                                applyAudioAndSpeed(mediaPlayerRef)
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            SyncFullscreenDialogWindow()
            Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
                playerBody(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding())
            }
        }
    } else {
        // 可拖拽悬浮画中画窗（不阻塞终端下方继续输入命令）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp, end = 10.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            playerBody(
                Modifier
                    .offset { IntOffset(pipOffsetX.roundToInt(), pipOffsetY.roundToInt()) }
                    .width(268.dp)
                    .height(216.dp)
                    .shadow(12.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, scheme.accentColor.copy(alpha = 0.75f), RoundedCornerShape(12.dp)),
            )
        }
    }
}
