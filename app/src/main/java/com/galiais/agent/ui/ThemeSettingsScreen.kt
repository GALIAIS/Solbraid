package com.galiais.agent.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ThemeConfig
import com.galiais.agent.engine.ThemePresetCatalog
import kotlin.math.abs
import kotlin.math.roundToInt

private val seedPresets = listOf(
    0xFF6750A4 to "基准紫", 0xFF0B57D0 to "谷歌蓝", 0xFF006A6A to "青碧", 0xFF386A20 to "森绿",
    0xFF7C5800 to "金穗", 0xFFB3261E to "赤红", 0xFF984061 to "玫瑰", 0xFF4355B9 to "靛青",
    0xFF8B5000 to "琥珀", 0xFF00677F to "深海", 0xFF625B71 to "雾紫", 0xFF5D5F5F to "石墨",
)

private val navBarOptions = listOf(
    "floating_glass" to "悬浮磨砂胶囊",
    "split_fab" to "分体胶囊+圆钮",
    "hardware_slots" to "机械分槽灯珠",
    "editorial_bar" to "纸感贴底",
    "m3_expressive" to "M3 Expressive",
)

private val cardOptions = listOf(
    "glass_hairline" to "顶部微光边",
    "hardware_tactile" to "工业硬影切角",
    "elevated" to "柔影浮卡",
    "grouped_expressive" to "M3 拼接分组",
    "outlined" to "极细描边",
    "filled" to "柔和填充",
)

private val textureOptions = listOf(
    "clean" to "纯净画布",
    "dot_grid" to "工程点阵",
    "paper_grain" to "柔光微渐变",
    "mesh_glow" to "顶部环境光晕",
)

private val buttonOptions = listOf(
    "pill" to "全圆胶囊",
    "squircle" to "超椭圆",
    "chamfer_tactile" to "机械切角",
    "tonal_soft" to "轻盈色块",
)

private val approvalOptions = listOf(
    "top_glow" to "顶部微光灯带",
    "badge_ring" to "图标徽章细框",
    "accent_rail" to "内嵌胶囊光柱",
    "soft_halo" to "柔色光晕浮卡",
    "hazard_header" to "分舱警戒顶栏",
    "tonal_banner" to "M3 柔和容器",
)

private val listLayoutOptions = listOf(
    "cards" to "独立留白卡",
    "tree_guide" to "IDE 纵线树",
    "grouped" to "首尾圆角拼接",
)

private val typoOptions = listOf(
    "editorial" to "编辑精装",
    "mono_tech" to "极客等宽",
    "rounded" to "现代圆润",
)

@Composable
fun ThemeSettingsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val settings by app.settings.state.collectAsState()
    val t = settings.theme
    val customThemes = settings.customThemes
    val set: ((ThemeConfig) -> ThemeConfig) -> Unit = { app.settings.updateTheme(it) }
    var hexTarget by remember { mutableStateOf<String?>(null) }
    var savingCustom by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf("全部") }
    var activeTab by rememberSaveable { mutableStateOf("presets") }
    var showPreview by rememberSaveable { mutableStateOf(true) }

    val matchedPreset = remember(t) { ThemePresetCatalog.matchPreset(t) }
    val categories = remember(customThemes.size) {
        if (customThemes.isNotEmpty()) ThemePresetCatalog.CATEGORIES + "我的收藏" else ThemePresetCatalog.CATEGORIES
    }
    if (selectedCategory !in categories) selectedCategory = "全部"
    val filteredPresets = remember(selectedCategory) {
        if (selectedCategory == "我的收藏") emptyList()
        else ThemePresetCatalog.PRESETS.filter { selectedCategory == "全部" || it.category == selectedCategory }
    }

    SubPageScaffold(
        title = "主题与外观",
        onBack = onBack,
        snackbar = snackbar,
        scrollable = false,
        contentPadding = PaddingValues(0.dp),
        actions = {
            TextButton(onClick = {
                set { ThemePresetCatalog.randomHarmoniousMix(it) }
            }) { Text("随机", color = G.accent, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = { confirmReset = true }) { Text("重置", color = G.inkMuted, fontWeight = FontWeight.Medium) }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = matchedPreset?.let { "预览 · ${it.name}" } ?: "预览 · 自定义混搭",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = G.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TagPill(
                text = if (showPreview) "收起预览" else "展开预览",
                fg = G.inkMuted,
                bg = G.surfaceSubtle,
                onClick = { showPreview = !showPreview },
            )
            TagPill("收藏", fg = G.accent, bg = G.accentContainer, onClick = { savingCustom = true })
        }
        AnimatedExpandable(expanded = showPreview) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ThemePreview()
            }
        }
        SegmentedPillBar(
            options = listOf("presets" to "预设", "color" to "色彩", "style" to "外观", "typo" to "排版"),
            selected = activeTab,
            onSelect = { activeTab = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(D.space(10.dp)),
        ) {
            Crossfade(targetState = activeTab, animationSpec = tween(120), label = "themeTab") { tab ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(D.space(10.dp))) {
                    when (tab) {
                        "presets" -> {
                            SectionLabel("主题预设（24 套${if (customThemes.isNotEmpty()) " + ${customThemes.size} 收藏" else ""}）")
                            GaliaisScrollableTabBar(
                                options = categories.map { it to it },
                                selected = selectedCategory,
                                onSelect = { selectedCategory = it },
                            )
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (selectedCategory == "我的收藏" || selectedCategory == "全部") {
                                    items(customThemes, key = { "custom:${it.id}" }) { slot ->
                                        val dark = slot.config.mode == "dark" || (slot.config.mode == "system" && G.dark)
                                        val previewColors = galiaisColors(slot.config, dark)
                                        val matched = t == slot.config
                                        ThemePresetCard(
                                            title = slot.name,
                                            subtitle = "我的收藏",
                                            bg = previewColors.canvas,
                                            card = previewColors.surface,
                                            accent = previewColors.accent,
                                            selected = matched,
                                            modeTag = when (slot.config.mode) { "dark" -> "深"; "light" -> "浅"; else -> "自" },
                                            onClick = { if (!matched) set { slot.config } },
                                            onDelete = {
                                                app.settings.deleteCustomTheme(slot.id)
                                                app.conversations.notify("已删除「${slot.name}」")
                                            },
                                        )
                                    }
                                }
                                items(filteredPresets, key = { "preset:${it.id}" }) { preset ->
                                    val matched = matchedPreset?.id == preset.id
                                    ThemePresetCard(
                                        title = preset.name,
                                        subtitle = preset.subtitle,
                                        bg = Color(preset.previewCanvas.toInt()),
                                        card = Color(preset.previewSurface.toInt()),
                                        accent = Color(preset.previewAccent.toInt()),
                                        selected = matched,
                                        modeTag = when (preset.config.mode) { "dark" -> "深"; "light" -> "浅"; else -> "自" },
                                        onClick = {
                                            if (!matched) set {
                                                preset.config.copy(fontScale = it.fontScale, codeFontScale = it.codeFontScale)
                                            }
                                        },
                                    )
                                }
                            }
                            SectionLabel("界面风格与明暗")
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                StyleOptionCard(
                                    title = "Solbraid 模块化",
                                    subtitle = "12 套基底 + 7 维自由拼装",
                                    material = false,
                                    selected = !t.isMaterial,
                                    onClick = { set { it.copy(style = ThemeConfig.STYLE_GALIAIS) } },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                                StyleOptionCard(
                                    title = "Material 3",
                                    subtitle = "支持壁纸动态取色（Material You）",
                                    material = true,
                                    selected = t.isMaterial,
                                    onClick = { set { it.copy(style = ThemeConfig.STYLE_MATERIAL) } },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            SegmentedPillBar(
                                options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                                selected = t.mode,
                                onSelect = { mode -> set { it.copy(mode = mode) } },
                            )
                        }
                        "color" -> {
                            SectionLabel("色彩与基底")
                            if (t.isMaterial) MaterialColorSection(t, set, onCustom = { hexTarget = "seed" })
                            else GaliaisColorSection(t, set, onCustom = { hexTarget = "accent" })
                        }
                        "style" -> {
                            SectionLabel("7 大外观维度自由混搭")
                            CrispCard {
                                AxisChipBlock("1. 底部导航形态", "切换 5 种底栏结构，分体模式自带快捷新建钮", navBarOptions, D.navBarStyle) { v -> set { it.copy(navBarStyle = v) } }
                                Hairline(Modifier.padding(vertical = 13.dp))
                                AxisChipBlock("2. 卡片容器材质", "调整卡片微光边、工业硬影、柔影或分组填充", cardOptions, D.cardStyle) { v -> set { it.copy(cardStyle = v) } }
                                Hairline(Modifier.padding(vertical = 13.dp))
                                AxisChipBlock("3. 画布环境纹理", "在纯净底色上叠加点阵、纸感微光或顶部光晕", textureOptions, t.canvasTexture) { v -> set { it.copy(canvasTexture = v) } }
                                Hairline(Modifier.padding(vertical = 13.dp))
                                AxisChipBlock("4. 按钮几何形态", "控制主次按钮的圆角轮廓与色块质感", buttonOptions, t.buttonStyle) { v -> set { it.copy(buttonStyle = v) } }
                                Hairline(Modifier.padding(vertical = 13.dp))
                                AxisChipBlock("5. 审批拦截卡片", "高风险命令确认卡的视觉强调方式", approvalOptions, t.approvalStyle) { v -> set { it.copy(approvalStyle = v) } }
                                Hairline(Modifier.padding(vertical = 13.dp))
                                AxisChipBlock("6. 会话列表布局", "切换独立卡片、IDE 纵线折叠树或首尾拼接", listLayoutOptions, t.listLayout) { v -> set { it.copy(listLayout = v) } }
                                Hairline(Modifier.padding(vertical = 13.dp))
                                AxisChipBlock("7. 字体排印性格", "调整标题与标签的字形风格", typoOptions, t.typographyStyle) { v -> set { it.copy(typographyStyle = v) } }
                            }
                            SectionLabel("圆角与密度")
                            CrispCard {
                                OptionBlock("圆角尺度", "微调所有卡片、输入框与标签的圆润程度") {
                                    SegmentedPillBar(
                                        options = listOf(0.4f to "锐利", 0.75f to "精密", 1f to "标准", 1.3f to "圆润"),
                                        selected = listOf(0.4f, 0.75f, 1f, 1.3f).minBy { abs(it - t.corner) },
                                        onSelect = { v -> set { it.copy(corner = v) } },
                                    )
                                }
                                Hairline(Modifier.padding(vertical = 13.dp))
                                OptionBlock("界面密度", "调整列表、卡片与消息的内外间距") {
                                    SegmentedPillBar(
                                        options = listOf("compact" to "紧凑", "standard" to "标准", "relaxed" to "宽松"),
                                        selected = t.density,
                                        onSelect = { v -> set { it.copy(density = v) } },
                                    )
                                }
                            }
                        }
                        else -> {
                            SectionLabel("文字")
                            CrispCard {
                                ScaleSlider("界面字号", t.fontScale, 0.85f, 1.3f, steps = 8) { v -> set { it.copy(fontScale = v) } }
                                Hairline(Modifier.padding(vertical = 12.dp))
                                ScaleSlider("代码字号", t.codeFontScale, 0.8f, 1.4f, steps = 11) { v -> set { it.copy(codeFontScale = v) } }
                            }
                            SectionLabel("代码与会话")
                            CrispCard {
                                OptionBlock("代码配色", "作用于代码块、终端输出与文件预览") {
                                    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        item(key = "auto") {
                                            CodeThemeCard(CodePalettes.resolve("auto", t.isMaterial, G.dark), "自动", t.codeTheme == "auto") { set { it.copy(codeTheme = "auto") } }
                                        }
                                        items(CodePalettes.all, key = { it.id }) { p ->
                                            CodeThemeCard(p, p.label, t.codeTheme == p.id) { set { it.copy(codeTheme = p.id) } }
                                        }
                                    }
                                }
                                Hairline(Modifier.padding(vertical = 13.dp))
                                SettingsToggleRow(
                                    title = "显示代码行号",
                                    subtitle = "在代码块与文件预览左侧显示行号",
                                    checked = t.codeLineNumbers,
                                    onCheckedChange = { v -> set { it.copy(codeLineNumbers = v) } },
                                )
                                Hairline(Modifier.padding(vertical = 13.dp))
                                OptionBlock("用户消息样式", "自动：Solbraid 使用中性色，Material 3 使用主题色") {
                                    SegmentedPillBar(
                                        options = listOf("auto" to "自动", "neutral" to "中性", "accent" to "强调色"),
                                        selected = t.bubbleStyle,
                                        onSelect = { v -> set { it.copy(bubbleStyle = v) } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) {
        GaliaisConfirmDialog(
            title = "恢复默认外观设置？",
            message = "将重置当前色彩、7 维外观与排版设置，已收藏的主题槽位不受影响。",
            confirmText = "恢复默认",
            danger = true,
            onConfirm = {
                confirmReset = false
                app.settings.resetTheme(keepStyle = true)
                app.conversations.notify("已恢复默认外观设置")
            },
            onDismiss = { confirmReset = false },
        )
    }

    if (savingCustom) {
        TextInputDialog(
            title = "保存为我的主题",
            initial = "我的混搭 #${customThemes.size + 1}",
            onDismiss = { savingCustom = false },
            onConfirm = { name ->
                val saved = app.settings.saveCustomTheme(name, t)
                savingCustom = false
                selectedCategory = "我的收藏"
                app.conversations.notify("已保存「${saved.name}」到我的收藏")
            },
        )
    }

    hexTarget?.let { target ->
        val initial = if (target == "seed") t.seedColor else (t.accent.takeIf { it != 0L } ?: 0xFF6E7BF2)
        HexColorDialog(
            title = if (target == "seed") "自定义主题色" else "自定义强调色",
            initial = initial,
            onDismiss = { hexTarget = null },
        ) { color ->
            if (target == "seed") set { it.copy(seedColor = color, dynamicColor = false) } else set { it.copy(accent = color) }
            hexTarget = null
        }
    }
}

@Composable
private fun AxisChipBlock(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    OptionBlock(title, subtitle) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            options.forEach { (id, label) ->
                SelectableChip(text = label, selected = selected == id, onClick = { onSelect(id) })
            }
        }
    }
}

@Composable
private fun MaterialColorSection(t: ThemeConfig, set: ((ThemeConfig) -> ThemeConfig) -> Unit, onCustom: () -> Unit) {
    val dynamicAvailable = dynamicColorSupported()
    val dynamicActive = dynamicAvailable && t.dynamicColor
    CrispCard {
        SettingsToggleRow(
            title = "动态取色（Material You）",
            subtitle = if (dynamicAvailable) "跟随系统壁纸自动生成配色" else "需要 Android 12 及以上，当前设备使用主题色",
            checked = dynamicActive,
            enabled = dynamicAvailable,
            onCheckedChange = { v -> set { it.copy(dynamicColor = v) } },
        )
        Spacer(Modifier.height(14.dp))
        SchemeRolesStrip()
        Hairline(Modifier.padding(vertical = 14.dp))
        OptionBlock("主题色", if (dynamicActive) "关闭动态取色后生效" else "根据主题色生成界面配色") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                seedPresets.forEach { (color, label) ->
                    ColorSwatch(
                        Color(color.toInt()),
                        selected = !dynamicActive && t.seedColor == color,
                        label = label,
                        onClick = { set { it.copy(seedColor = color, dynamicColor = false) } },
                    )
                }
                val custom = seedPresets.none { it.first == t.seedColor }
                ColorSwatch(
                    if (custom) Color(t.seedColor.toInt()) else null,
                    selected = !dynamicActive && custom,
                    label = if (custom) t.seedColor.toHex() else "自定义",
                    onClick = onCustom,
                )
            }
        }
        Hairline(Modifier.padding(vertical = 14.dp))
        OptionBlock("配色风格", "调整辅助色与背景色") {
            SegmentedPillBar(
                options = listOf("tonal" to "柔和", "vibrant" to "鲜明", "expressive" to "表现", "mono" to "单色"),
                selected = t.paletteStyle,
                enabled = !dynamicActive,
                onSelect = { v -> set { it.copy(paletteStyle = v) } },
            )
        }
    }
}

@Composable
private fun GaliaisColorSection(t: ThemeConfig, set: ((ThemeConfig) -> ThemeConfig) -> Unit, onCustom: () -> Unit) {
    CrispCard {
        OptionBlock("强调色（12 种精选 + 自定义 HEX）", "用于主按钮、选中态、开关、光晕与焦点边框") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemePresetCatalog.ACCENT_SWATCHES.forEach { (color, label) ->
                    ColorSwatch(
                        if (color == 0L) (if (G.dark) Color(0xFFF3F4F8) else Color(0xFF151514)) else Color(color.toInt()),
                        selected = t.accent == color,
                        label = label,
                        onClick = { set { it.copy(accent = color) } },
                    )
                }
                val custom = ThemePresetCatalog.ACCENT_SWATCHES.none { it.first == t.accent }
                ColorSwatch(
                    if (custom) Color(t.accent.toInt()) else null,
                    selected = custom,
                    label = if (custom) t.accent.toHex() else "自定义",
                    onClick = onCustom,
                )
            }
        }
        Hairline(Modifier.padding(vertical = 14.dp))
        OptionBlock("浅色纸基（6 种）", "浅色模式下的背景画布与卡片基底") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneOptionCard("暖宣纸", Color(0xFFF8F6F1), Color.White, Color(0xFFE3DFD6), t.paper == "warm", { set { it.copy(paper = "warm") } }, Modifier.weight(1f))
                    ToneOptionCard("极简白", Color(0xFFF7F8FA), Color.White, Color(0xFFE2E5EC), t.paper == "neutral", { set { it.copy(paper = "neutral") } }, Modifier.weight(1f))
                    ToneOptionCard("冷雾蓝", Color(0xFFF2F6FC), Color.White, Color(0xFFD8E2F0), t.paper == "cool", { set { it.copy(paper = "cool") } }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneOptionCard("硬件灰沙", Color(0xFFEAE8E0), Color(0xFFF7F6F0), Color(0xFFC5C2B6), t.paper == "sand", { set { it.copy(paper = "sand") } }, Modifier.weight(1f))
                    ToneOptionCard("M3 浅薰", Color(0xFFFEF7FF), Color(0xFFF4EEF8), Color(0xFFD9CEE8), t.paper == "lavender", { set { it.copy(paper = "lavender") } }, Modifier.weight(1f))
                    ToneOptionCard("薄荷霜", Color(0xFFF2F8F5), Color.White, Color(0xFFD0E4DC), t.paper == "mint", { set { it.copy(paper = "mint") } }, Modifier.weight(1f))
                }
            }
        }
        Hairline(Modifier.padding(vertical = 14.dp))
        OptionBlock("深色基底（6 种）", "深色模式下的背景画布，纯黑适合 OLED 省电") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneOptionCard("曜石黑", Color(0xFF0B0C10), Color(0xFF14161D), Color(0xFF292D3B), t.darkTone == "obsidian", { set { it.copy(darkTone = "obsidian") } }, Modifier.weight(1f))
                    ToneOptionCard("柔炭灰", Color(0xFF181A20), Color(0xFF22252E), Color(0xFF393E4D), t.darkTone == "dim", { set { it.copy(darkTone = "dim") } }, Modifier.weight(1f))
                    ToneOptionCard("纯黑", Color.Black, Color(0xFF0B0B0E), Color(0xFF24242D), t.darkTone == "amoled", { set { it.copy(darkTone = "amoled") } }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneOptionCard("深海蓝", Color(0xFF0B1120), Color(0xFF131C31), Color(0xFF273759), t.darkTone == "midnight", { set { it.copy(darkTone = "midnight") } }, Modifier.weight(1f))
                    ToneOptionCard("墨绿松", Color(0xFF0B120F), Color(0xFF121C18), Color(0xFF263A33), t.darkTone == "forest", { set { it.copy(darkTone = "forest") } }, Modifier.weight(1f))
                    ToneOptionCard("暖咖炭", Color(0xFF141210), Color(0xFF1E1B18), Color(0xFF3B342E), t.darkTone == "espresso", { set { it.copy(darkTone = "espresso") } }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OptionBlock(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = G.ink)
        subtitle?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun ScaleSlider(title: String, value: Float, min: Float, max: Float, steps: Int, onChange: (Float) -> Unit) {
    val stepSize = (max - min) / (steps + 1)
    fun snap(v: Float) = ((((v - min) / stepSize).roundToInt() * stepSize + min) * 100f).roundToInt() / 100f
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
            TagPill("${(value * 100).roundToInt()}%", fg = G.ink, bg = G.surfaceSubtle)
        }
        Slider(
            value = value,
            onValueChange = { v -> snap(v).let { if (it != value) onChange(it) } },
            valueRange = min..max,
            steps = steps,
            colors = if (D.material) SliderDefaults.colors() else SliderDefaults.colors(
                thumbColor = G.accent,
                activeTrackColor = G.accent,
                inactiveTrackColor = G.surfaceSubtle,
                activeTickColor = G.onAccent.copy(alpha = 0.5f),
                inactiveTickColor = G.inkSubtle,
            ),
        )
        Row {
            Text("${(min * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle, modifier = Modifier.weight(1f))
            Text("${(max * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
        }
    }
}

/** 设置页中「主题与外观」入口卡片使用的当前主题摘要。 */
fun themeSummary(t: ThemeConfig): String {
    val mode = when (t.mode) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }
    ThemePresetCatalog.matchPreset(t)?.let { return "${it.name} · $mode" }
    val nav = navBarOptions.firstOrNull { it.first == t.navBarStyle }?.second
    val card = cardOptions.firstOrNull { it.first == t.cardStyle }?.second
    val style = if (t.isMaterial) "Material 3" else "自定义混搭"
    return listOfNotNull(style, mode, nav, card).take(3).joinToString(" · ")
}
