package com.galiais.agent.ui

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.ThemeConfig

val Mono = FontFamily.Monospace

/** 语义色彩令牌。两种设计语言都解析到同一套令牌，组件只依赖令牌。 */
@Immutable
data class GaliaisColors(
    val dark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceSubtle: Color,
    val surfaceFilled: Color,
    val surfaceElevated: Color,
    val border: Color,
    val borderStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkSubtle: Color,
    val onInk: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val userBubbleBorder: Color,
    val codex: Color,
    val codexTint: Color,
    val claude: Color,
    val claudeTint: Color,
    val success: Color,
    val successTint: Color,
    val warning: Color,
    val warningTint: Color,
    val danger: Color,
    val dangerTint: Color,
    val onDanger: Color,
    val codeBg: Color,
    val codeHeader: Color,
    val codeBorder: Color,
    val codeInk: Color,
    val codeMuted: Color,
    val diffAddedBg: Color,
    val diffAddedInk: Color,
    val diffRemovedBg: Color,
    val diffRemovedInk: Color,
    val diffHunkBg: Color,
    val diffHunkInk: Color,
)

/** 分组列表卡片位置（用于首尾大圆角 + 中间小圆角的拼接效果）。 */
enum class CardGroupPos { SINGLE, FIRST, MIDDLE, LAST }

/** 形状、密度、7 维外观组件令牌与代码排版令牌。 */
@Immutable
data class GaliaisDesign(
    val material: Boolean,
    val corner: Float,
    val cardStyle: String,
    val navBarStyle: String,
    val canvasTexture: String,
    val buttonStyle: String,
    val approvalStyle: String,
    val listLayout: String,
    val typographyStyle: String,
    val density: Float,
    val codeFontScale: Float,
    val codeLineNumbers: Boolean,
    val code: CodePalette,
) {
    /** 把设计稿圆角映射到当前设计语言与圆角强度；≥100dp 表示胶囊。 */
    fun radius(base: Dp): Dp {
        if (base.value >= 100f) return if (corner >= 0.75f) 999.dp else (20f * corner).coerceAtLeast(3f).dp
        val snapped = if (!material) base.value else when {
            base.value <= 5f -> 4f
            base.value <= 10f -> 8f
            base.value <= 14f -> 12f
            base.value <= 20f -> 16f
            else -> 28f
        }
        return (snapped * corner).dp
    }

    fun shape(base: Dp): RoundedCornerShape = RoundedCornerShape(radius(base))
    fun space(base: Dp): Dp = (base.value * density).dp
    fun codeSize(base: Float) = (base * codeFontScale).sp

    val cardRadius: Dp
        get() = when (cardStyle) {
            "hardware_tactile" -> radius(8.dp).coerceAtMost(8.dp)
            else -> radius(if (material) 14.dp else 16.dp)
        }

    fun cardShapeFor(pos: CardGroupPos = CardGroupPos.SINGLE): RoundedCornerShape {
        val r = cardRadius
        if (pos == CardGroupPos.SINGLE) return RoundedCornerShape(r)
        val big = if (cardStyle == "hardware_tactile") r else (r.value * 1.1f).coerceAtLeast(12f).dp
        val small = if (cardStyle == "hardware_tactile") 3.dp else 5.dp
        return when (pos) {
            CardGroupPos.FIRST -> RoundedCornerShape(topStart = big, topEnd = big, bottomStart = small, bottomEnd = small)
            CardGroupPos.MIDDLE -> RoundedCornerShape(small)
            CardGroupPos.LAST -> RoundedCornerShape(topStart = small, topEnd = small, bottomStart = big, bottomEnd = big)
            CardGroupPos.SINGLE -> RoundedCornerShape(r)
        }
    }

    val cardShape: RoundedCornerShape get() = cardShapeFor(CardGroupPos.SINGLE)
    val chipShape: RoundedCornerShape
        get() = when (buttonStyle) {
            "chamfer_tactile" -> RoundedCornerShape(4.dp)
            "squircle" -> shape(8.dp)
            else -> if (material) shape(8.dp) else shape(999.dp)
        }
    val buttonShape: RoundedCornerShape
        get() = when (buttonStyle) {
            "pill" -> RoundedCornerShape(999.dp)
            "chamfer_tactile" -> RoundedCornerShape(5.dp)
            "squircle" -> shape(11.dp)
            "tonal_soft" -> shape(12.dp)
            else -> shape(999.dp)
        }
    val fieldShape: RoundedCornerShape get() = shape(if (cardStyle == "hardware_tactile") 5.dp else if (material) 8.dp else 12.dp)
    val dialogShape: RoundedCornerShape get() = shape(if (cardStyle == "hardware_tactile") 8.dp else if (material) 28.dp else 20.dp)
    val popupShape: RoundedCornerShape get() = if (cardStyle == "hardware_tactile") RoundedCornerShape(6.dp) else shape(16.dp)
    val codeShape: RoundedCornerShape get() = shape(if (cardStyle == "hardware_tactile") 5.dp else 12.dp)
    val composerShape: RoundedCornerShape get() = shape(if (cardStyle == "hardware_tactile") 8.dp else if (material) 28.dp else 24.dp)
    val fabShape: RoundedCornerShape
        get() = when (buttonStyle) {
            "chamfer_tactile" -> RoundedCornerShape(6.dp)
            "squircle" -> shape(14.dp)
            else -> if (material) shape(16.dp) else shape(999.dp)
        }
    val sheetShape: RoundedCornerShape
        get() = radius(if (material) 28.dp else 24.dp).let { RoundedCornerShape(topStart = it, topEnd = it) }
    val borderWidth: Dp get() = if (cardStyle == "hardware_tactile") 1.25.dp else 1.dp
    val hairline: Dp get() = if (material || cardStyle == "hardware_tactile") 1.dp else 0.75.dp
}

val LocalGaliaisColors = staticCompositionLocalOf { galiaisColors(ThemeConfig(), false) }
val LocalDesign = staticCompositionLocalOf { designOf(ThemeConfig(), false) }
val LocalRenderPrefs = staticCompositionLocalOf { com.galiais.agent.engine.SettingsState() }

val G: GaliaisColors
    @Composable get() = LocalGaliaisColors.current

val D: GaliaisDesign
    @Composable get() = LocalDesign.current

val RPrefs: com.galiais.agent.engine.SettingsState
    @Composable get() = LocalRenderPrefs.current

private val GaliaisTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

/** Material 3 官方字阶（Roboto / 系统字体），只把正文行高稍放宽以适合长文阅读。 */
private val MaterialTypography = Typography().let { t ->
    t.copy(bodyLarge = t.bodyLarge.copy(lineHeight = 24.sp))
}

/** 极客等宽排印：标题与标签采用等宽字体并微增字距，正文保持舒适易读。 */
private val MonoTechTypography = GaliaisTypography.copy(
    headlineSmall = GaliaisTypography.headlineSmall.copy(fontFamily = Mono, letterSpacing = (-0.2).sp),
    titleLarge = GaliaisTypography.titleLarge.copy(fontFamily = Mono, letterSpacing = (-0.2).sp),
    titleMedium = GaliaisTypography.titleMedium.copy(fontFamily = Mono, letterSpacing = 0.sp),
    titleSmall = GaliaisTypography.titleSmall.copy(fontFamily = Mono, letterSpacing = 0.sp),
    labelLarge = GaliaisTypography.labelLarge.copy(fontFamily = Mono, letterSpacing = 0.2.sp),
    labelMedium = GaliaisTypography.labelMedium.copy(fontFamily = Mono, letterSpacing = 0.2.sp),
    labelSmall = GaliaisTypography.labelSmall.copy(fontFamily = Mono, letterSpacing = 0.25.sp),
)

/** 现代圆润排印：柔和的字距与稍舒展的行高。 */
private val RoundedTypography = GaliaisTypography.copy(
    headlineSmall = GaliaisTypography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleLarge = GaliaisTypography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleMedium = GaliaisTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = GaliaisTypography.bodyLarge.copy(lineHeight = 24.sp),
    labelLarge = GaliaisTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

fun designOf(config: ThemeConfig, dark: Boolean): GaliaisDesign = GaliaisDesign(
    material = config.isMaterial,
    corner = config.corner,
    cardStyle = when (config.cardStyle) {
        "auto" -> if (config.isMaterial) "filled" else "glass_hairline"
        else -> config.cardStyle
    },
    navBarStyle = when (config.navBarStyle) {
        "auto" -> if (config.isMaterial) "m3_expressive" else "floating_glass"
        else -> config.navBarStyle
    },
    canvasTexture = config.canvasTexture,
    buttonStyle = config.buttonStyle,
    approvalStyle = config.approvalStyle,
    listLayout = config.listLayout,
    typographyStyle = config.typographyStyle,
    density = when (config.density) { "compact" -> 0.82f; "relaxed" -> 1.18f; else -> 1f },
    codeFontScale = config.codeFontScale,
    codeLineNumbers = config.codeLineNumbers,
    code = CodePalettes.resolve(config.codeTheme, config.isMaterial, dark),
)

/** 双设计语言共用的状态与引擎色（与背景解耦，保证在任意主题下语义一致）。 */
private data class FixedTones(
    val codex: Color, val codexTint: Color, val claude: Color, val claudeTint: Color,
    val success: Color, val successTint: Color, val warning: Color, val warningTint: Color,
    val diffAddedBg: Color, val diffAddedInk: Color, val diffRemovedBg: Color, val diffRemovedInk: Color,
    val diffHunkBg: Color, val diffHunkInk: Color,
)

private val LightFixed = FixedTones(
    Color(0xFF0F8A68), Color(0xFFE7F5F0), Color(0xFFC86442), Color(0xFFFBEDE7),
    Color(0xFF15803D), Color(0xFFE9F7EE), Color(0xFFB45309), Color(0xFFFEF4E6),
    Color(0xFFE6F6EC), Color(0xFF166534), Color(0xFFFCEAEA), Color(0xFF991B1B), Color(0xFFEDF2FA), Color(0xFF365687),
)
private val DarkFixed = FixedTones(
    Color(0xFF34D399), Color(0xFF132C24), Color(0xFFE28462), Color(0xFF2F1D17),
    Color(0xFF4ADE80), Color(0xFF142A1E), Color(0xFFFBBF24), Color(0xFF2D2312),
    Color(0xFF132D20), Color(0xFF6EE7B7), Color(0xFF37181D), Color(0xFFFCA5A5), Color(0xFF1B2638), Color(0xFF93C5FD),
)

/** 中性基底：6 种浅色纸张色调与 6 种深色基调。 */
private data class Neutrals(
    val canvas: Color, val surface: Color, val subtle: Color, val border: Color, val borderStrong: Color,
    val ink: Color, val inkMuted: Color, val inkSubtle: Color, val onInk: Color,
)

private fun neutrals(config: ThemeConfig, dark: Boolean): Neutrals = if (!dark) when (config.paper) {
    "neutral" -> Neutrals(Color(0xFFF7F8FA), Color.White, Color(0xFFEFF1F5), Color(0xFFE2E5EC), Color(0xFFCCD1DC),
        Color(0xFF111318), Color(0xFF5D6475), Color(0xFF8E95A6), Color.White)
    "cool" -> Neutrals(Color(0xFFF2F6FC), Color.White, Color(0xFFE7EFFA), Color(0xFFD8E2F0), Color(0xFFBECDE0),
        Color(0xFF101928), Color(0xFF596980), Color(0xFF8A9AB0), Color.White)
    "sand" -> Neutrals(Color(0xFFEAE8E0), Color(0xFFF7F6F0), Color(0xFFDFDDD4), Color(0xFFC5C2B6), Color(0xFF9E9B8F),
        Color(0xFF141412), Color(0xFF595850), Color(0xFF848279), Color.White)
    "lavender" -> Neutrals(Color(0xFFFEF7FF), Color(0xFFF4EEF8), Color(0xFFE9DFF6), Color(0xFFD9CEE8), Color(0xFFC1B3D6),
        Color(0xFF1D1B20), Color(0xFF49454F), Color(0xFF7A757F), Color.White)
    "mint" -> Neutrals(Color(0xFFF2F8F5), Color.White, Color(0xFFE4F1EC), Color(0xFFD0E4DC), Color(0xFFB3CFC4),
        Color(0xFF10211B), Color(0xFF506B61), Color(0xFF819A90), Color.White)
    else -> Neutrals(Color(0xFFF8F6F1), Color.White, Color(0xFFEFECE4), Color(0xFFE3DFD6), Color(0xFFCEC9BE),
        Color(0xFF181715), Color(0xFF69655C), Color(0xFF9A9489), Color.White)
} else when (config.darkTone) {
    "dim" -> Neutrals(Color(0xFF181A20), Color(0xFF22252E), Color(0xFF2C303C), Color(0xFF393E4D), Color(0xFF4C5264),
        Color(0xFFECEEF5), Color(0xFF9EA5B8), Color(0xFF6E7588), Color(0xFF16171A))
    "amoled" -> Neutrals(Color.Black, Color(0xFF0B0B0E), Color(0xFF16161C), Color(0xFF24242D), Color(0xFF373744),
        Color(0xFFF7F7FA), Color(0xFF9A9AA8), Color(0xFF636370), Color.Black)
    "midnight" -> Neutrals(Color(0xFF0B1120), Color(0xFF131C31), Color(0xFF1C2944), Color(0xFF273759), Color(0xFF3A4F7A),
        Color(0xFFEEF4FF), Color(0xFF92A6CC), Color(0xFF5C7096), Color(0xFF0B1120))
    "forest" -> Neutrals(Color(0xFF0B120F), Color(0xFF121C18), Color(0xFF1A2823), Color(0xFF263A33), Color(0xFF38544A),
        Color(0xFFECF7F1), Color(0xFF8FAE9E), Color(0xFF5B786A), Color(0xFF0B120F))
    "espresso" -> Neutrals(Color(0xFF141210), Color(0xFF1E1B18), Color(0xFF292420), Color(0xFF3B342E), Color(0xFF524940),
        Color(0xFFF4EFE8), Color(0xFFA89E91), Color(0xFF736A5F), Color(0xFF141210))
    else -> Neutrals(Color(0xFF0B0C10), Color(0xFF14161D), Color(0xFF1D202B), Color(0xFF292D3B), Color(0xFF3C4154),
        Color(0xFFF3F4F8), Color(0xFF9298AC), Color(0xFF60667A), Color(0xFF0B0C10))
}

private fun readableOn(bg: Color): Color =
    if (ColorEngine.relativeLuminance(bg) > 0.36) Color(0xFF111114) else Color.White

/** GALIAIS 编辑风：中性纸张/深色基调 + 可选强调色（默认墨色）。 */
fun galiaisColors(config: ThemeConfig, dark: Boolean): GaliaisColors {
    val n = neutrals(config, dark)
    val f = if (dark) DarkFixed else LightFixed
    val code = CodePalettes.resolve(config.codeTheme, false, dark)
    val accent: Color
    val accentContainer: Color
    val onAccentContainer: Color
    if (config.accent == 0L) {
        accent = n.ink; accentContainer = n.subtle; onAccentContainer = n.ink
    } else {
        val lch = ColorEngine.toOklch(config.accent)
        val exact = Color(config.accent.toInt())
        val lum = ColorEngine.relativeLuminance(exact)
        accent = if (dark) {
            if (lum >= 0.16) exact else ColorEngine.tone(lch.h, lch.c.coerceAtMost(0.22), 68.0)
        } else {
            if (lum in 0.03..0.28) exact else ColorEngine.tone(lch.h, lch.c, 45.0)
        }
        accentContainer = if (dark) ColorEngine.tone(lch.h, lch.c * 0.45, 24.0) else ColorEngine.tone(lch.h, lch.c * 0.3, 94.0)
        onAccentContainer = if (dark) ColorEngine.tone(lch.h, lch.c, 88.0) else ColorEngine.tone(lch.h, lch.c, 28.0)
    }
    val accentBubble = config.bubbleStyle == "accent"
    return GaliaisColors(
        dark = dark,
        canvas = n.canvas, surface = n.surface, surfaceSubtle = n.subtle,
        surfaceFilled = n.subtle, surfaceElevated = n.surface,
        border = n.border, borderStrong = n.borderStrong,
        ink = n.ink, inkMuted = n.inkMuted, inkSubtle = n.inkSubtle, onInk = n.onInk,
        accent = accent, onAccent = readableOn(accent),
        accentContainer = accentContainer, onAccentContainer = onAccentContainer,
        inverseSurface = n.ink, inverseOnSurface = n.onInk,
        userBubble = if (accentBubble) (if (config.accent == 0L) n.ink else accentContainer) else n.subtle,
        onUserBubble = if (accentBubble) (if (config.accent == 0L) n.onInk else onAccentContainer) else n.ink,
        userBubbleBorder = if (accentBubble) Color.Transparent else n.border,
        codex = f.codex, codexTint = f.codexTint, claude = f.claude, claudeTint = f.claudeTint,
        success = f.success, successTint = f.successTint, warning = f.warning, warningTint = f.warningTint,
        danger = if (dark) Color(0xFFF87171) else Color(0xFFC63636),
        dangerTint = if (dark) Color(0xFF31181A) else Color(0xFFFDECEC),
        onDanger = if (dark) Color(0xFF1A0B0B) else Color.White,
        codeBg = code.bg, codeHeader = code.header, codeBorder = code.border, codeInk = code.ink, codeMuted = code.muted,
        diffAddedBg = f.diffAddedBg, diffAddedInk = f.diffAddedInk, diffRemovedBg = f.diffRemovedBg,
        diffRemovedInk = f.diffRemovedInk, diffHunkBg = f.diffHunkBg, diffHunkInk = f.diffHunkInk,
    )
}

/** Material 3：把 ColorScheme 的标准角色映射到语义令牌。 */
fun materialColors(scheme: ColorScheme, config: ThemeConfig, dark: Boolean): GaliaisColors {
    val f = if (dark) DarkFixed else LightFixed
    val code = CodePalettes.resolve(config.codeTheme, true, dark)
    val neutralBubble = config.bubbleStyle == "neutral"
    return GaliaisColors(
        dark = dark,
        canvas = scheme.surface,
        surface = scheme.surfaceContainerLow,
        surfaceSubtle = scheme.surfaceContainerHighest,
        surfaceFilled = scheme.surfaceContainer,
        surfaceElevated = scheme.surfaceContainerLow,
        border = scheme.outlineVariant,
        borderStrong = scheme.outline,
        ink = scheme.onSurface,
        inkMuted = scheme.onSurfaceVariant,
        inkSubtle = scheme.onSurfaceVariant.copy(alpha = 0.72f),
        onInk = scheme.surface,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        accentContainer = scheme.secondaryContainer,
        onAccentContainer = scheme.onSecondaryContainer,
        inverseSurface = scheme.inverseSurface,
        inverseOnSurface = scheme.inverseOnSurface,
        userBubble = if (neutralBubble) scheme.surfaceContainerHigh else scheme.primaryContainer,
        onUserBubble = if (neutralBubble) scheme.onSurface else scheme.onPrimaryContainer,
        userBubbleBorder = Color.Transparent,
        codex = f.codex, codexTint = f.codexTint, claude = f.claude, claudeTint = f.claudeTint,
        success = f.success, successTint = f.successTint, warning = f.warning, warningTint = f.warningTint,
        danger = scheme.error, dangerTint = scheme.errorContainer, onDanger = scheme.onError,
        codeBg = code.bg, codeHeader = code.header, codeBorder = code.border, codeInk = code.ink, codeMuted = code.muted,
        diffAddedBg = f.diffAddedBg, diffAddedInk = f.diffAddedInk, diffRemovedBg = f.diffRemovedBg,
        diffRemovedInk = f.diffRemovedInk, diffHunkBg = f.diffHunkBg, diffHunkInk = f.diffHunkInk,
    )
}

/** GALIAIS 风格下也提供一致的 M3 ColorScheme，让菜单、对话框等系统组件与令牌保持一致。 */
private fun schemeFromGaliais(p: GaliaisColors): ColorScheme {
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = p.accentContainer, onPrimaryContainer = p.onAccentContainer,
        secondary = p.codex, onSecondary = Color.White, secondaryContainer = p.accentContainer, onSecondaryContainer = p.onAccentContainer,
        tertiary = p.claude, onTertiary = Color.White, tertiaryContainer = p.claudeTint, onTertiaryContainer = p.claude,
        background = p.canvas, onBackground = p.ink,
        surface = p.surface, onSurface = p.ink, surfaceVariant = p.surfaceSubtle, onSurfaceVariant = p.inkMuted,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surfaceSubtle,
        surfaceBright = p.surface, surfaceDim = p.canvas,
        inverseSurface = p.inverseSurface, inverseOnSurface = p.inverseOnSurface, inversePrimary = p.accentContainer,
        outline = p.borderStrong, outlineVariant = p.border,
        error = p.danger, onError = p.onDanger, errorContainer = p.dangerTint, onErrorContainer = p.danger,
    )
}

fun dynamicColorSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun GaliaisTheme(config: ThemeConfig = ThemeConfig(), dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val (colors, scheme) = remember(config, dark) {
        if (config.isMaterial) {
            val s = if (config.dynamicColor && dynamicColorSupported()) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                ColorEngine.scheme(config.seedColor, config.paletteStyle, dark)
            }
            materialColors(s, config, dark) to s
        } else {
            val p = galiaisColors(config, dark)
            p to schemeFromGaliais(p)
        }
    }
    val design = remember(config, dark) { designOf(config, dark) }
    val shapes = remember(design) {
        Shapes(
            extraSmall = design.shape(4.dp), small = design.shape(8.dp), medium = design.shape(12.dp),
            large = design.shape(16.dp), extraLarge = design.shape(28.dp),
        )
    }
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, config.fontScale) { Density(baseDensity.density, baseDensity.fontScale * config.fontScale) }
    val resolvedTypography = when (design.typographyStyle) {
        "mono_tech" -> MonoTechTypography
        "rounded" -> RoundedTypography
        else -> if (config.isMaterial) MaterialTypography else GaliaisTypography
    }
    CompositionLocalProvider(
        LocalGaliaisColors provides colors,
        LocalDesign provides design,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = resolvedTypography,
            shapes = shapes,
            content = content,
        )
    }
}

/**
 * 画布环境背景：始终先铺满不透明 `G.canvas`（防转场闪白），再按 `D.canvasTexture` 叠加微纹理：
 * - `clean`: 纯净画布
 * - `dot_grid`: 工程点阵（Nothing / TE 硬件风）
 * - `paper_grain`: 柔光微渐变（纸感 / 云雾风）
 * - `mesh_glow`: 顶部环境光晕（Linear / Arc 微光风）
 */
fun Modifier.galiaisCanvasBackground(): Modifier = composed {
    val g = G
    val texture = D.canvasTexture
    drawBehind {
        drawRect(g.canvas)
        when (texture) {
            "dot_grid" -> {
                val step = 16.dp.toPx()
                val r = 1.dp.toPx()
                val dotColor = if (g.dark) Color.White.copy(alpha = 0.065f) else Color.Black.copy(alpha = 0.075f)
                var y = step * 0.5f
                while (y < size.height) {
                    var x = step * 0.5f
                    while (x < size.width) {
                        drawCircle(color = dotColor, radius = r, center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
            "mesh_glow" -> {
                val glowRadius = size.width.coerceAtLeast(1f) * 0.85f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            g.accent.copy(alpha = if (g.dark) 0.16f else 0.11f),
                            g.accent.copy(alpha = if (g.dark) 0.04f else 0.03f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.5f, -size.height * 0.04f),
                        radius = glowRadius,
                    ),
                )
            }
            "paper_grain" -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            g.accent.copy(alpha = if (g.dark) 0.06f else 0.05f),
                            Color.Transparent,
                            g.ink.copy(alpha = if (g.dark) 0.03f else 0.025f),
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
            }
        }
    }
}
