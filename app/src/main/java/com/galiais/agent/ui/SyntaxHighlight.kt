package com.galiais.agent.ui

import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * 面向深色代码块与文件预览的轻量多语言语法高亮器（GitHub Dark 风格配色，零第三方依赖，内置 LRU 缓存避免列表滚动重复分词）。
 * 支持 Kotlin/Java/JS/TS/Python/Rust/Go/C/C++/Shell/JSON/YAML/SQL/HTML/CSS。
 */
object SyntaxHighlight {
    private data class CacheEntry(val code: String, val lang: String, val paletteHash: Int, val styled: AnnotatedString)
    private val cache = LruCache<Int, CacheEntry>(64)

    private val commonKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data", "private", "public", "internal", "protected",
        "override", "suspend", "inline", "companion", "import", "package", "return", "if", "else", "when", "for", "while", "do",
        "try", "catch", "finally", "throw", "null", "true", "false", "this", "super", "is", "in", "as", "by", "init", "break", "continue",
        "function", "const", "let", "async", "await", "export", "default", "from", "new", "typeof", "instanceof", "switch", "case",
        "def", "lambda", "with", "yield", "pass", "None", "True", "False", "and", "or", "not", "elif", "except", "raise", "global", "nonlocal",
        "fn", "pub", "mut", "struct", "impl", "trait", "match", "use", "mod", "crate", "self", "Self", "where", "ref", "move",
        "func", "go", "defer", "chan", "select", "range", "type", "map", "make", "static", "void", "final", "abstract", "extends", "implements",
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "INDEX", "JOIN", "LEFT", "ON", "ORDER", "GROUP", "BY", "LIMIT",
        "fi", "then", "elif", "esac", "done", "local", "export", "exec", "test", "echo", "printf", "exit",
    )

    private val hashCommentLangs = setOf(
        "py", "python", "sh", "bash", "zsh", "fish", "shell", "yaml", "yml", "toml",
        "rb", "ruby", "dockerfile", "makefile", "cmake", "ini", "conf", "cfg", "properties", "env", "r", "pl",
    )

    fun languageFromPath(path: String): String? {
        val fileName = path.substringAfterLast('/').lowercase()
        when (fileName) {
            "dockerfile" -> return "dockerfile"
            "makefile", "gnumakefile" -> return "makefile"
            "cmakelists.txt" -> return "cmake"
            "build.prop", "hosts", "fstab", "resolv.conf" -> return "conf"
        }
        return when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "tsx", "jsx" -> "typescript"
            "py" -> "python"
            "rs" -> "rust"
            "go" -> "go"
            "c", "h", "cpp", "cc", "cxx", "hpp" -> "c"
            "cs" -> "csharp"
            "swift" -> "swift"
            "dart" -> "dart"
            "php" -> "php"
            "rb" -> "ruby"
            "lua" -> "lua"
            "groovy", "gradle" -> "groovy"
            "sh", "bash", "zsh", "fish" -> "bash"
            "ps1" -> "powershell"
            "json", "jsonc" -> "json"
            "yml", "yaml" -> "yaml"
            "toml" -> "toml"
            "ini", "conf", "cfg", "properties", "env", "service", "rules" -> "conf"
            "xml", "html", "htm", "svg", "vue", "svelte" -> "xml"
            "css", "scss", "less" -> "css"
            "sql" -> "sql"
            "graphql", "gql" -> "graphql"
            "proto" -> "proto"
            "tex", "bib" -> "latex"
            "diff", "patch" -> "diff"
            "md", "markdown" -> "markdown"
            "log" -> "log"
            else -> null
        }
    }

    /**
     * 窗口化语法高亮（突破 40KB 硬限制至 350KB）：
     * - <= 40KB：全文走缓存高亮；
     * - 40KB..350KB：仅高亮可视区附近行对齐切片（约 36KB），前后区保持纯色，严格保持字符索引 1:1 对应；
     * - > 350KB：退化为纯色以避免低端设备掉帧。
     */
    fun highlightWindowed(
        code: String,
        language: String?,
        palette: CodePalette = CodePalettes.githubDark,
        visibleStartChar: Int = 0,
        visibleEndChar: Int = 36_000,
    ): AnnotatedString {
        if (code.length <= 40_000) return highlight(code, language, palette)
        if (code.length > 350_000) return AnnotatedString(code, SpanStyle(color = palette.ink))
        val maxWindow = 36_000
        val rawStart = visibleStartChar.coerceIn(0, code.length)
        val rawEnd = visibleEndChar.coerceIn(rawStart, (rawStart + maxWindow).coerceAtMost(code.length))
        // 对齐到完整行边界，防止切在注释/字符串中间导致高亮错位
        val winStart = if (rawStart == 0) 0 else code.lastIndexOf('\n', rawStart - 1).let { if (it < 0) 0 else it + 1 }
        val winEnd = if (rawEnd >= code.length) code.length else code.indexOf('\n', rawEnd).let { if (it < 0) code.length else it }
        val clampedEnd = minOf(winEnd, winStart + maxWindow).coerceAtLeast(winStart)
        val slice = code.substring(winStart, clampedEnd)
        val styledSlice = highlight(slice, language, palette)
        return buildAnnotatedString {
            if (winStart > 0) {
                append(code.substring(0, winStart))
                addStyle(SpanStyle(color = palette.ink), 0, winStart)
            }
            append(styledSlice)
            if (clampedEnd < code.length) {
                val tailStart = length
                append(code.substring(clampedEnd))
                addStyle(SpanStyle(color = palette.ink), tailStart, length)
            }
        }
    }

    fun highlight(code: String, language: String?, palette: CodePalette = CodePalettes.githubDark): AnnotatedString {
        val plainColor = palette.ink
        val commentColor = palette.comment
        val stringColor = palette.string
        val keywordColor = palette.keyword
        val numberColor = palette.number
        val typeColor = palette.type
        val functionColor = palette.function
        val keyColor = palette.key
        // 超长文本不做逐词高亮，避免主线程卡顿。
        if (code.length > 40_000) return AnnotatedString(code, SpanStyle(color = plainColor))
        val lang = language?.trim()?.lowercase()?.substringBefore(' ') ?: ""
        val palHash = palette.hashCode()
        val cacheKey = ((code.hashCode() * 31 + code.length) * 31 + lang.hashCode()) * 31 + palHash
        cache.get(cacheKey)?.let { hit ->
            if (hit.lang == lang && hit.paletteHash == palHash && hit.code == code) return hit.styled
        }
        val isJson = lang == "json"
        val isHash = lang in hashCommentLangs
        val result = buildAnnotatedString {
            append(code)
            addStyle(SpanStyle(color = plainColor), 0, code.length)
            var i = 0
            val n = code.length
            while (i < n) {
                val c = code[i]
                // 单行注释 // 或 #
                if ((c == '/' && i + 1 < n && code[i + 1] == '/') || (isHash && c == '#') || (lang == "sql" && c == '-' && i + 1 < n && code[i + 1] == '-')) {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), i, end)
                    i = end
                    continue
                }
                // 块注释 /* ... */
                if (c == '/' && i + 1 < n && code[i + 1] == '*') {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), i, end)
                    i = end
                    continue
                }
                // 字符串 "..." / '...' / `...`
                if (c == '"' || c == '\'' || c == '`') {
                    val start = i++
                    while (i < n) {
                        if (code[i] == '\\' && i + 1 < n) { i += 2; continue }
                        if (code[i] == c) { i++; break }
                        if (c != '`' && code[i] == '\n') break
                        i++
                    }
                    var after = i
                    while (after < n && code[after] == ' ') after++
                    val style = if (isJson && after < n && code[after] == ':') SpanStyle(color = keyColor) else SpanStyle(color = stringColor)
                    addStyle(style, start, i)
                    continue
                }
                // 注解 @Composable
                if (c == '@' && i + 1 < n && code[i + 1].isLetter()) {
                    val start = i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    addStyle(SpanStyle(color = typeColor), start, i)
                    continue
                }
                // 数字
                if (c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_')) {
                    val start = i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++
                    addStyle(SpanStyle(color = numberColor), start, i)
                    continue
                }
                // 标识符 / 关键字 / 类型 / 函数名
                if (c.isLetter() || c == '_') {
                    val start = i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    val word = code.substring(start, i)
                    val style = when {
                        word in commonKeywords -> SpanStyle(color = keywordColor, fontWeight = FontWeight.Medium)
                        i < n && code[i] == '(' -> SpanStyle(color = functionColor)
                        word[0].isUpperCase() && word.any { it.isLowerCase() } -> SpanStyle(color = typeColor)
                        else -> null
                    }
                    if (style != null) addStyle(style, start, i)
                    continue
                }
                i++
            }
        }
        cache.put(cacheKey, CacheEntry(code, lang, palHash, result))
        return result
    }
}
