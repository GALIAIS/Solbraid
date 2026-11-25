package com.galiais.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.R
import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import com.galiais.agent.engine.RUNTIME_MODE_ROOT
import kotlinx.coroutines.launch

/**
 * 首次进入模式选择页：自动检测 Root 权限（Root 优先），选择 Root 模式时立即自动发起 su 授权申请。
 */
@Composable
fun ModeSelectionScreen(app: GaliaisApplication) {
    val rootAvailable by app.runtime.rootAvailable.collectAsState()
    val rootAccess by app.runtime.rootAccess.collectAsState()
    val requestingRoot by app.runtime.requestingRoot.collectAsState()
    val env by app.runtime.environment.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(RUNTIME_MODE_ROOT) }
    var userPicked by remember { mutableStateOf(false) }
    var rootDeniedHint by remember { mutableStateOf(false) }

    fun enterWithMode(mode: String) {
        app.settings.setRuntimeMode(mode)
        app.runtime.refreshEnvironment(mode)
        // 用户已明确选定运行模式，离线组件均已内置于 APK 中，直接自动安装并启动，不再要求进入首页后再手动点击一次「安装运行环境」。
        app.runtime.start()
    }

    LaunchedEffect(Unit) {
        app.runtime.probeRoot()
        app.runtime.refreshEnvironment(RUNTIME_MODE_ROOT)
    }
    LaunchedEffect(rootAvailable) {
        if (!userPicked && rootAvailable != null) {
            // Root 优先：检测到 Root 默认推荐 Root 模式，否则自动选中免 Root 模式
            selected = if (rootAvailable == true) RUNTIME_MODE_ROOT else RUNTIME_MODE_PROOT
        }
    }

    Surface(color = G.canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            painter = painterResource(R.drawable.ic_solbraid_brand),
                            contentDescription = "Solbraid",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(36.dp).clip(D.shape(10.dp)).border(0.75.dp, G.border, D.shape(10.dp)),
                        )
                        Text(
                            "Solbraid",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.6).sp,
                            color = G.ink,
                        )
                    }
                    Text(
                        "选择运行模式",
                        style = MaterialTheme.typography.headlineSmall,
                        color = G.ink,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "安装包内含 Ubuntu、Codex 和 Claude Code，环境安装无需下载。使用在线模型仍需联网。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = G.inkMuted,
                    )
                }

                // 实时 Root 权限探测条
                val (probeText, probeDot, probeBg) = when {
                    requestingRoot -> Triple("等待 Root 授权，请在权限管理器中确认", G.warning, G.warningTint)
                    rootAvailable == null -> Triple("正在检查 Root 权限…", G.warning, G.warningTint)
                    rootAvailable == true -> Triple("Root 环境权限可用", G.success, G.successTint)
                    else -> Triple(rootAccess?.status?.label ?: "Root 启动未确认", G.accent, G.accentContainer)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(D.shape(12.dp))
                        .background(probeBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (rootAvailable == null || requestingRoot) PulsingDot(probeDot, size = 7.dp)
                    else Box(Modifier.size(7.dp).background(probeDot, CircleShape))
                    Text(
                        probeText,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (rootAvailable == false && !requestingRoot && D.material) G.onAccentContainer else G.ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (rootAvailable != null && !requestingRoot) {
                        TagPill(
                            text = if (rootAvailable == true) "已授权" else "申请授权",
                            fg = if (rootAvailable == true) G.success else G.accent,
                            bg = G.surface,
                            onClick = if (rootAvailable == true) null else ({ scope.launch { rootDeniedHint = !app.runtime.requestRoot() } }),
                        )
                    }
                }

                ModeOptionCard(
                    title = "Root 模式",
                    badge = if (rootAvailable == true) "已授权" else "需要 Root 权限",
                    badgeHighlight = rootAvailable == true,
                    selected = selected == RUNTIME_MODE_ROOT,
                    existingHint = if (env.rootEnvInstalled) "已有 Root 环境，可继续使用" else null,
                    description = "通过 chroot 运行 Linux，需要设备已配置 Root 权限管理器。",
                    bullets = listOf(
                        "选择后申请 Root 权限",
                        "可在关闭应用后保持运行，实际行为受系统限制",
                        "适合已配置 Magisk、KernelSU 或 APatch 的设备",
                    ),
                    onClick = {
                        selected = RUNTIME_MODE_ROOT
                        userPicked = true
                        rootDeniedHint = false
                        if (rootAvailable != true && !requestingRoot) {
                            scope.launch { rootDeniedHint = !app.runtime.requestRoot() }
                        }
                    },
                )

                ModeOptionCard(
                    title = "免 Root 模式",
                    badge = if (rootAvailable == false) "推荐" else "无需 Root",
                    badgeHighlight = rootAvailable == false,
                    selected = selected == RUNTIME_MODE_PROOT,
                    existingHint = if (env.prootEnvInstalled) "已有免 Root 环境，可继续使用" else null,
                    description = "通过 PRoot 运行 Linux，无需 Root 权限。",
                    bullets = listOf(
                        "无需解锁 Bootloader",
                        "性能与兼容性取决于设备和系统",
                        "后台运行受系统限制，强行停止应用会中断任务",
                    ),
                    onClick = { selected = RUNTIME_MODE_PROOT; userPicked = true },
                )

                Text(
                    "两种模式的工作区和会话分别保存，安装包缓存共享。可在「设置 → 运行环境」切换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.inkSubtle,
                )
            }

            Hairline()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(G.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (rootDeniedHint && selected == RUNTIME_MODE_ROOT && rootAvailable == false) {
                    Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.warningTint) {
                        Text(
                            rootAccess?.message ?: "请检查 Root 管理器中的 Solbraid 授权后重试",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.ink,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                val buttonLabel = when {
                    requestingRoot && selected == RUNTIME_MODE_ROOT -> "等待 Root 授权…"
                    selected == RUNTIME_MODE_ROOT && rootAvailable != true -> "授权并继续"
                    selected == RUNTIME_MODE_ROOT -> "使用 Root 模式"
                    else -> "使用免 Root 模式"
                }
                PrimaryPillButton(
                    text = buttonLabel,
                    enabled = !(requestingRoot && selected == RUNTIME_MODE_ROOT),
                    onClick = {
                        if (selected == RUNTIME_MODE_ROOT && rootAvailable != true) {
                            scope.launch {
                                val granted = app.runtime.requestRoot()
                                if (granted) enterWithMode(RUNTIME_MODE_ROOT)
                                else rootDeniedHint = true
                            }
                        } else {
                            enterWithMode(selected)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeOptionCard(
    title: String,
    badge: String,
    badgeHighlight: Boolean,
    selected: Boolean,
    existingHint: String?,
    description: String,
    bullets: List<String>,
    onClick: () -> Unit,
) {
    CrispCard(
        onClick = onClick,
        borderColor = if (selected) G.accent else Color.Unspecified,
        background = if (selected && D.material) G.accentContainer.copy(alpha = 0.22f) else Color.Unspecified,
        padding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GaliaisRadio(selected = selected)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = G.ink, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TagPill(
                            text = badge,
                            fg = if (badgeHighlight) G.accent else G.inkMuted,
                            bg = if (badgeHighlight) G.accentContainer else G.surfaceSubtle,
                        )
                        if (existingHint != null) {
                            TagPill(text = "已安装", fg = G.success, bg = G.successTint)
                        }
                    }
                }
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                bullets.forEach { item ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", style = MaterialTheme.typography.bodySmall, color = if (selected) G.accent else G.inkSubtle)
                        Text(item, style = MaterialTheme.typography.bodySmall, color = G.ink)
                    }
                }
            }
        }
    }
}
