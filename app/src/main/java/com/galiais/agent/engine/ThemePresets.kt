package com.galiais.agent.engine

import kotlin.math.abs
import kotlin.random.Random

/** 一套可直接套用的大师主题预设（包含全部 7 维外观组合与预览色）。 */
data class ThemePresetSpec(
    val id: String,
    val category: String,
    val name: String,
    val subtitle: String,
    val previewCanvas: Long,
    val previewSurface: Long,
    val previewAccent: Long,
    val config: ThemeConfig,
)

object ThemePresetCatalog {
    val CATEGORIES = listOf("全部", "微光极客", "工业硬件", "液态现代", "纸感书房", "开发者经典")

    val PRESETS: List<ThemePresetSpec> = listOf(
        // ── 微光极客 (5) ──
        ThemePresetSpec(
            "linear_dark", "微光极客", "Linear 暗夜微光", "悬浮胶囊 · 微光边",
            0xFF0B0C10, 0xFF14161D, 0xFF6E7BF2,
            ThemeConfig(
                mode = "dark", accent = 0xFF6E7BF2, paper = "neutral", darkTone = "obsidian",
                navBarStyle = "floating_glass", cardStyle = "glass_hairline", canvasTexture = "mesh_glow",
                buttonStyle = "pill", approvalStyle = "top_glow", listLayout = "cards", typographyStyle = "editorial",
                corner = 1f, density = "standard", codeTheme = "github-dark",
            ),
        ),
        ThemePresetSpec(
            "raycast_ember", "微光极客", "Raycast 熔岩微光", "绯红极光 · 紧凑",
            0xFF0B0C10, 0xFF14161D, 0xFFF43F5E,
            ThemeConfig(
                mode = "dark", accent = 0xFFF43F5E, paper = "neutral", darkTone = "obsidian",
                navBarStyle = "floating_glass", cardStyle = "glass_hairline", canvasTexture = "mesh_glow",
                buttonStyle = "squircle", approvalStyle = "top_glow", listLayout = "cards", typographyStyle = "editorial",
                corner = 0.9f, density = "compact", codeTheme = "one-dark",
            ),
        ),
        ThemePresetSpec(
            "vercel_mono", "微光极客", "Vercel 极简黑白", "AMOLED · 极细框",
            0xFF000000, 0xFF0B0B0D, 0xFFE4E4E7,
            ThemeConfig(
                mode = "dark", accent = 0L, paper = "neutral", darkTone = "amoled",
                navBarStyle = "floating_glass", cardStyle = "outlined", canvasTexture = "clean",
                buttonStyle = "squircle", approvalStyle = "badge_ring", listLayout = "tree_guide", typographyStyle = "mono_tech",
                corner = 0.7f, density = "compact", codeTheme = "github-dark", codeLineNumbers = true,
            ),
        ),
        ThemePresetSpec(
            "geist_light", "微光极客", "Geist 霜白精密", "极简白 · 靛蓝",
            0xFFF7F8FA, 0xFFFFFFFF, 0xFF4F46E5,
            ThemeConfig(
                mode = "light", accent = 0xFF4F46E5, paper = "neutral", darkTone = "obsidian",
                navBarStyle = "floating_glass", cardStyle = "glass_hairline", canvasTexture = "clean",
                buttonStyle = "squircle", approvalStyle = "badge_ring", listLayout = "cards", typographyStyle = "editorial",
                corner = 0.9f, density = "standard", codeTheme = "github-light",
            ),
        ),
        ThemePresetSpec(
            "cyber_cyan", "微光极客", "赛博青光 HUD", "深海点阵 · 等宽",
            0xFF0B1120, 0xFF131C31, 0xFF06B6D4,
            ThemeConfig(
                mode = "dark", accent = 0xFF06B6D4, paper = "cool", darkTone = "midnight",
                navBarStyle = "floating_glass", cardStyle = "glass_hairline", canvasTexture = "dot_grid",
                buttonStyle = "squircle", approvalStyle = "top_glow", listLayout = "tree_guide", typographyStyle = "mono_tech",
                corner = 0.8f, density = "compact", codeTheme = "nord", codeLineNumbers = true,
            ),
        ),
        // ── 工业硬件 (4) ──
        ThemePresetSpec(
            "te_orange", "工业硬件", "TE 工程橙黑", "机械灯珠 · 硬影",
            0xFF000000, 0xFF0B0B0D, 0xFFFF4F1F,
            ThemeConfig(
                mode = "dark", accent = 0xFFFF4F1F, paper = "sand", darkTone = "amoled",
                navBarStyle = "hardware_slots", cardStyle = "hardware_tactile", canvasTexture = "dot_grid",
                buttonStyle = "chamfer_tactile", approvalStyle = "hazard_header", listLayout = "cards", typographyStyle = "mono_tech",
                corner = 0.4f, density = "compact", codeTheme = "one-dark", codeLineNumbers = true,
            ),
        ),
        ThemePresetSpec(
            "nothing_sand", "工业硬件", "Nothing 沙灰硬件", "灰沙点阵 · 切角",
            0xFFEAE8E0, 0xFFF7F6F0, 0xFFE54014,
            ThemeConfig(
                mode = "light", accent = 0xFFE54014, paper = "sand", darkTone = "dim",
                navBarStyle = "hardware_slots", cardStyle = "hardware_tactile", canvasTexture = "dot_grid",
                buttonStyle = "chamfer_tactile", approvalStyle = "hazard_header", listLayout = "cards", typographyStyle = "mono_tech",
                corner = 0.4f, density = "standard", codeTheme = "solarized-light",
            ),
        ),
        ThemePresetSpec(
            "crt_phosphor", "工业硬件", "CRT 荧光绿终端", "墨绿松 · 纵线树",
            0xFF0B120F, 0xFF121C18, 0xFF22C55E,
            ThemeConfig(
                mode = "dark", accent = 0xFF22C55E, paper = "mint", darkTone = "forest",
                navBarStyle = "hardware_slots", cardStyle = "hardware_tactile", canvasTexture = "dot_grid",
                buttonStyle = "chamfer_tactile", approvalStyle = "hazard_header", listLayout = "tree_guide", typographyStyle = "mono_tech",
                corner = 0.35f, density = "compact", codeTheme = "github-dark", codeLineNumbers = true,
            ),
        ),
        ThemePresetSpec(
            "amber_deck", "工业硬件", "琥珀掌机 Deck", "暖咖炭 · 琥珀金",
            0xFF141210, 0xFF1E1B18, 0xFFF59E0B,
            ThemeConfig(
                mode = "dark", accent = 0xFFF59E0B, paper = "sand", darkTone = "espresso",
                navBarStyle = "hardware_slots", cardStyle = "hardware_tactile", canvasTexture = "dot_grid",
                buttonStyle = "chamfer_tactile", approvalStyle = "hazard_header", listLayout = "cards", typographyStyle = "mono_tech",
                corner = 0.45f, density = "compact", codeTheme = "one-dark",
            ),
        ),
        // ── 液态现代 (5) ──
        ThemePresetSpec(
            "arc_spatial", "液态现代", "Arc 液态星海", "分体圆钮 · 柔影",
            0xFF0B1120, 0xFF131C31, 0xFF38BDF8,
            ThemeConfig(
                mode = "dark", accent = 0xFF38BDF8, paper = "cool", darkTone = "midnight",
                navBarStyle = "split_fab", cardStyle = "elevated", canvasTexture = "mesh_glow",
                buttonStyle = "pill", approvalStyle = "soft_halo", listLayout = "cards", typographyStyle = "rounded",
                corner = 1.3f, density = "relaxed", codeTheme = "nord",
            ),
        ),
        ThemePresetSpec(
            "ios_cloud", "液态现代", "云雾清透蓝", "冷雾蓝 · 拼接卡",
            0xFFF2F6FC, 0xFFFFFFFF, 0xFF0284C7,
            ThemeConfig(
                mode = "light", accent = 0xFF0284C7, paper = "cool", darkTone = "midnight",
                navBarStyle = "split_fab", cardStyle = "elevated", canvasTexture = "paper_grain",
                buttonStyle = "pill", approvalStyle = "soft_halo", listLayout = "grouped", typographyStyle = "rounded",
                corner = 1.3f, density = "standard", codeTheme = "github-light",
            ),
        ),
        ThemePresetSpec(
            "m3_exp_dark", "液态现代", "M3 Expressive 暗紫", "M3 · 拼接分组",
            0xFF1B1C20, 0xFF24252A, 0xFFA78BFA,
            ThemeConfig(
                style = ThemeConfig.STYLE_MATERIAL, mode = "dark", dynamicColor = false, seedColor = 0xFF6750A4, paletteStyle = "expressive",
                accent = 0xFFA78BFA, paper = "lavender", darkTone = "dim",
                navBarStyle = "m3_expressive", cardStyle = "grouped_expressive", canvasTexture = "clean",
                buttonStyle = "pill", approvalStyle = "tonal_banner", listLayout = "grouped", typographyStyle = "rounded",
                corner = 1.25f, density = "standard", codeTheme = "dracula",
            ),
        ),
        ThemePresetSpec(
            "m3_exp_light", "液态现代", "Material You 动态", "M3 · 壁纸动态取色",
            0xFFFEF7FF, 0xFFF4EEF8, 0xFF6750A4,
            ThemeConfig(
                style = ThemeConfig.STYLE_MATERIAL, mode = "system", dynamicColor = true, seedColor = 0xFF6750A4, paletteStyle = "tonal",
                accent = 0xFF6750A4, paper = "lavender", darkTone = "dim",
                navBarStyle = "m3_expressive", cardStyle = "grouped_expressive", canvasTexture = "clean",
                buttonStyle = "pill", approvalStyle = "tonal_banner", listLayout = "grouped", typographyStyle = "rounded",
                corner = 1.25f, density = "standard", codeTheme = "auto",
            ),
        ),
        ThemePresetSpec(
            "mint_breeze", "液态现代", "薄荷浮光氧", "薄荷霜 · 轻盈钮",
            0xFFF2F8F5, 0xFFFFFFFF, 0xFF059669,
            ThemeConfig(
                mode = "light", accent = 0xFF059669, paper = "mint", darkTone = "forest",
                navBarStyle = "split_fab", cardStyle = "elevated", canvasTexture = "mesh_glow",
                buttonStyle = "tonal_soft", approvalStyle = "soft_halo", listLayout = "cards", typographyStyle = "rounded",
                corner = 1.2f, density = "relaxed", codeTheme = "github-light",
            ),
        ),
        // ── 纸感书房 (4) ──
        ThemePresetSpec(
            "claude_paper", "纸感书房", "Claude 暖纸陶土", "暖宣纸 · 纵线树",
            0xFFF8F6F1, 0xFFFFFFFF, 0xFFC96442,
            ThemeConfig(
                mode = "light", accent = 0xFFC96442, paper = "warm", darkTone = "espresso",
                navBarStyle = "editorial_bar", cardStyle = "outlined", canvasTexture = "paper_grain",
                buttonStyle = "pill", approvalStyle = "badge_ring", listLayout = "tree_guide", typographyStyle = "editorial",
                corner = 1f, density = "standard", codeTheme = "solarized-light",
            ),
        ),
        ThemePresetSpec(
            "claude_night", "纸感书房", "炭黑陶土书房", "暖咖炭 · 陶土",
            0xFF141210, 0xFF1E1B18, 0xFFE07D5A,
            ThemeConfig(
                mode = "dark", accent = 0xFFE07D5A, paper = "warm", darkTone = "espresso",
                navBarStyle = "editorial_bar", cardStyle = "outlined", canvasTexture = "clean",
                buttonStyle = "pill", approvalStyle = "accent_rail", listLayout = "tree_guide", typographyStyle = "editorial",
                corner = 1f, density = "standard", codeTheme = "one-dark",
            ),
        ),
        ThemePresetSpec(
            "emerald_craft", "纸感书房", "祖母绿工坊", "暖纸 · 翡翠绿",
            0xFFF8F6F1, 0xFFFFFFFF, 0xFF0F8A68,
            ThemeConfig(
                mode = "system", accent = 0xFF0F8A68, paper = "warm", darkTone = "obsidian",
                navBarStyle = "editorial_bar", cardStyle = "outlined", canvasTexture = "clean",
                buttonStyle = "squircle", approvalStyle = "badge_ring", listLayout = "tree_guide", typographyStyle = "editorial",
                corner = 0.9f, density = "standard", codeTheme = "auto",
            ),
        ),
        ThemePresetSpec(
            "ink_paper", "纸感书房", "纯墨宣纸极简", "暖宣纸 · 纯墨",
            0xFFF8F6F1, 0xFFFFFFFF, 0xFF181715,
            ThemeConfig(
                mode = "system", accent = 0L, paper = "warm", darkTone = "obsidian",
                navBarStyle = "editorial_bar", cardStyle = "outlined", canvasTexture = "paper_grain",
                buttonStyle = "pill", approvalStyle = "badge_ring", listLayout = "tree_guide", typographyStyle = "editorial",
                corner = 0.9f, density = "relaxed", codeTheme = "auto",
            ),
        ),
        // ── 开发者经典 (6) ──
        ThemePresetSpec(
            "tokyo_night", "开发者经典", "Tokyo Night", "深海蓝 · 霓虹蓝",
            0xFF0B1120, 0xFF131C31, 0xFF7AA2F7,
            ThemeConfig(
                mode = "dark", accent = 0xFF7AA2F7, paper = "cool", darkTone = "midnight",
                navBarStyle = "floating_glass", cardStyle = "glass_hairline", canvasTexture = "mesh_glow",
                buttonStyle = "squircle", approvalStyle = "top_glow", listLayout = "tree_guide", typographyStyle = "mono_tech",
                corner = 0.9f, density = "compact", codeTheme = "nord", codeLineNumbers = true,
            ),
        ),
        ThemePresetSpec(
            "catppuccin", "开发者经典", "Catppuccin 摩卡", "柔炭灰 · 淡紫",
            0xFF1B1C20, 0xFF24252A, 0xFFCBA6F7,
            ThemeConfig(
                mode = "dark", accent = 0xFFCBA6F7, paper = "lavender", darkTone = "dim",
                navBarStyle = "split_fab", cardStyle = "filled", canvasTexture = "clean",
                buttonStyle = "pill", approvalStyle = "tonal_banner", listLayout = "grouped", typographyStyle = "rounded",
                corner = 1.15f, density = "standard", codeTheme = "dracula",
            ),
        ),
        ThemePresetSpec(
            "nord_frost", "开发者经典", "Nord 北欧冰原", "极夜蓝 · 冰青",
            0xFF0B1120, 0xFF131C31, 0xFF88C0D0,
            ThemeConfig(
                mode = "dark", accent = 0xFF88C0D0, paper = "cool", darkTone = "midnight",
                navBarStyle = "editorial_bar", cardStyle = "outlined", canvasTexture = "clean",
                buttonStyle = "squircle", approvalStyle = "badge_ring", listLayout = "tree_guide", typographyStyle = "editorial",
                corner = 0.8f, density = "standard", codeTheme = "nord",
            ),
        ),
        ThemePresetSpec(
            "dracula_glow", "开发者经典", "Dracula 霓虹紫", "柔炭 · 玫瑰粉",
            0xFF1B1C20, 0xFF24252A, 0xFFEC4899,
            ThemeConfig(
                mode = "dark", accent = 0xFFEC4899, paper = "lavender", darkTone = "dim",
                navBarStyle = "floating_glass", cardStyle = "glass_hairline", canvasTexture = "mesh_glow",
                buttonStyle = "pill", approvalStyle = "soft_halo", listLayout = "cards", typographyStyle = "editorial",
                corner = 1f, density = "standard", codeTheme = "dracula",
            ),
        ),
        ThemePresetSpec(
            "gruvbox_warm", "开发者经典", "Gruvbox 复古暖", "暖咖 · 琥珀点阵",
            0xFF141210, 0xFF1E1B18, 0xFFF59E0B,
            ThemeConfig(
                mode = "dark", accent = 0xFFF59E0B, paper = "sand", darkTone = "espresso",
                navBarStyle = "editorial_bar", cardStyle = "filled", canvasTexture = "dot_grid",
                buttonStyle = "chamfer_tactile", approvalStyle = "hazard_header", listLayout = "tree_guide", typographyStyle = "mono_tech",
                corner = 0.6f, density = "compact", codeTheme = "one-dark",
            ),
        ),
        ThemePresetSpec(
            "amoled_jade", "开发者经典", "AMOLED 翡翠极夜", "纯黑省电 · 拼接",
            0xFF000000, 0xFF0B0B0D, 0xFF10B981,
            ThemeConfig(
                mode = "dark", accent = 0xFF10B981, paper = "mint", darkTone = "amoled",
                navBarStyle = "m3_expressive", cardStyle = "grouped_expressive", canvasTexture = "clean",
                buttonStyle = "pill", approvalStyle = "accent_rail", listLayout = "grouped", typographyStyle = "editorial",
                corner = 1.1f, density = "compact", codeTheme = "github-dark", codeLineNumbers = true,
            ),
        ),
    )

    val ACCENT_SWATCHES: List<Pair<Long, String>> = listOf(
        0xFF6E7BF2L to "靛蓝",
        0xFFFF4F1FL to "炽橙",
        0xFF0EA5E9L to "天青",
        0xFFC96442L to "暖陶",
        0xFF0F8A68L to "祖母绿",
        0xFF8B5CF6L to "紫罗兰",
        0xFFF43F5EL to "绯红",
        0xFFF59E0BL to "琥珀",
        0xFF06B6D4L to "赛博青",
        0xFF22C55EL to "荧光绿",
        0xFFEC4899L to "霓虹粉",
        0L to "极简墨",
    )

    /** 判断当前主题是否精确命中某套内置预设（忽略用户自行微调的字号）。 */
    fun matchPreset(c: ThemeConfig): ThemePresetSpec? = PRESETS.firstOrNull { p ->
        val x = p.config
        c.style == x.style &&
            c.accent == x.accent &&
            c.paper == x.paper &&
            c.darkTone == x.darkTone &&
            c.navBarStyle == x.navBarStyle &&
            c.cardStyle == x.cardStyle &&
            c.canvasTexture == x.canvasTexture &&
            c.buttonStyle == x.buttonStyle &&
            c.approvalStyle == x.approvalStyle &&
            c.listLayout == x.listLayout &&
            c.typographyStyle == x.typographyStyle &&
            abs(c.corner - x.corner) < 0.06f &&
            (!c.isMaterial || (c.dynamicColor == x.dynamicColor && c.seedColor == x.seedColor))
    }

    /** 随机生成一套和谐的 7 维混搭配置（保留当前明暗模式与字号）。 */
    fun randomHarmoniousMix(base: ThemeConfig): ThemeConfig {
        val papers = ThemeConfig.PAPERS.toList()
        val darks = ThemeConfig.DARK_TONES.toList()
        val navs = listOf("floating_glass", "split_fab", "hardware_slots", "editorial_bar", "m3_expressive")
        val cards = listOf("glass_hairline", "hardware_tactile", "elevated", "grouped_expressive", "outlined", "filled")
        val textures = ThemeConfig.CANVAS_TEXTURES.toList()
        val buttons = ThemeConfig.BUTTON_STYLES.toList()
        val approvals = ThemeConfig.APPROVAL_STYLES.toList()
        val lists = ThemeConfig.LIST_LAYOUTS.toList()
        val typos = ThemeConfig.TYPOGRAPHY_STYLES.toList()
        val corners = listOf(0.4f, 0.75f, 1.0f, 1.3f)
        val densities = ThemeConfig.DENSITIES.toList()
        val accent = ACCENT_SWATCHES[Random.nextInt(ACCENT_SWATCHES.size)].first
        return base.copy(
            style = ThemeConfig.STYLE_GALIAIS,
            accent = accent,
            paper = papers.random(),
            darkTone = darks.random(),
            navBarStyle = navs.random(),
            cardStyle = cards.random(),
            canvasTexture = textures.random(),
            buttonStyle = buttons.random(),
            approvalStyle = approvals.random(),
            listLayout = lists.random(),
            typographyStyle = typos.random(),
            corner = corners.random(),
            density = densities.random(),
        ).sanitized()
    }
}
