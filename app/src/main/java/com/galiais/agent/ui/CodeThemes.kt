package com.galiais.agent.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** 代码配色（代码块、终端输出、文件预览、语法高亮共用）。 */
@Immutable
data class CodePalette(
    val id: String,
    val label: String,
    val dark: Boolean,
    val bg: Color,
    val header: Color,
    val border: Color,
    val ink: Color,
    val muted: Color,
    val comment: Color,
    val string: Color,
    val keyword: Color,
    val number: Color,
    val type: Color,
    val function: Color,
    val key: Color,
)

object CodePalettes {
    val githubDark = CodePalette(
        "github-dark", "GitHub Dark", true,
        bg = Color(0xFF0D1117), header = Color(0xFF161B22), border = Color(0xFF30363D),
        ink = Color(0xFFE6EDF3), muted = Color(0xFF8B949E),
        comment = Color(0xFF8B949E), string = Color(0xFFA5D6FF), keyword = Color(0xFFFF7B72), number = Color(0xFF79C0FF),
        type = Color(0xFFFFA657), function = Color(0xFFD2A8FF), key = Color(0xFF7EE787),
    )
    val oneDark = CodePalette(
        "one-dark", "One Dark", true,
        bg = Color(0xFF282C34), header = Color(0xFF21252B), border = Color(0xFF3E4451),
        ink = Color(0xFFABB2BF), muted = Color(0xFF7F848E),
        comment = Color(0xFF7F848E), string = Color(0xFF98C379), keyword = Color(0xFFC678DD), number = Color(0xFFD19A66),
        type = Color(0xFFE5C07B), function = Color(0xFF61AFEF), key = Color(0xFFE06C75),
    )
    val dracula = CodePalette(
        "dracula", "Dracula", true,
        bg = Color(0xFF282A36), header = Color(0xFF21222C), border = Color(0xFF44475A),
        ink = Color(0xFFF8F8F2), muted = Color(0xFF9AA0C0),
        comment = Color(0xFF6272A4), string = Color(0xFFF1FA8C), keyword = Color(0xFFFF79C6), number = Color(0xFFBD93F9),
        type = Color(0xFF8BE9FD), function = Color(0xFF50FA7B), key = Color(0xFFFFB86C),
    )
    val nord = CodePalette(
        "nord", "Nord", true,
        bg = Color(0xFF2E3440), header = Color(0xFF3B4252), border = Color(0xFF434C5E),
        ink = Color(0xFFD8DEE9), muted = Color(0xFF8E99AE),
        comment = Color(0xFF616E88), string = Color(0xFFA3BE8C), keyword = Color(0xFF81A1C1), number = Color(0xFFB48EAD),
        type = Color(0xFF8FBCBB), function = Color(0xFF88C0D0), key = Color(0xFFEBCB8B),
    )
    val githubLight = CodePalette(
        "github-light", "GitHub Light", false,
        bg = Color(0xFFF6F8FA), header = Color(0xFFEEF1F4), border = Color(0xFFD0D7DE),
        ink = Color(0xFF1F2328), muted = Color(0xFF656D76),
        comment = Color(0xFF6E7781), string = Color(0xFF0A3069), keyword = Color(0xFFCF222E), number = Color(0xFF0550AE),
        type = Color(0xFF953800), function = Color(0xFF8250DF), key = Color(0xFF116329),
    )
    val solarizedLight = CodePalette(
        "solarized-light", "Solarized Light", false,
        bg = Color(0xFFFDF6E3), header = Color(0xFFEEE8D5), border = Color(0xFFE3DCC7),
        ink = Color(0xFF586E75), muted = Color(0xFF93A1A1),
        comment = Color(0xFF93A1A1), string = Color(0xFF2AA198), keyword = Color(0xFF859900), number = Color(0xFFD33682),
        type = Color(0xFFB58900), function = Color(0xFF268BD2), key = Color(0xFFCB4B16),
    )

    /** GALIAIS 编辑风默认：低饱和曜石底 + GitHub Dark 语法色。 */
    private val galiaisLight = githubDark.copy(id = "auto", label = "自动", bg = Color(0xFF15161A), header = Color(0xFF1E2026), border = Color(0xFF2B2E36), ink = Color(0xFFE6E9EF), muted = Color(0xFF8B92A0))
    private val galiaisDark = githubDark.copy(id = "auto", label = "自动", bg = Color(0xFF101115), header = Color(0xFF181A20), border = Color(0xFF272A33), ink = Color(0xFFE6E9EF), muted = Color(0xFF88909E))

    val all = listOf(githubDark, oneDark, dracula, nord, githubLight, solarizedLight)

    fun resolve(id: String, material: Boolean, dark: Boolean): CodePalette = all.firstOrNull { it.id == id } ?: when {
        material && dark -> githubDark.copy(id = "auto", label = "自动")
        material -> githubLight.copy(id = "auto", label = "自动")
        dark -> galiaisDark
        else -> galiaisLight
    }
}
