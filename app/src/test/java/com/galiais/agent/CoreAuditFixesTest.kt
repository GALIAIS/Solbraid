package com.galiais.agent

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.galiais.agent.engine.BuiltInAgents
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.CustomProvider
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.ProviderAuthMode
import com.galiais.agent.engine.ProviderProtocol
import com.galiais.agent.engine.TokenUsage
import com.galiais.agent.engine.inferModelContextWindow
import com.galiais.agent.engine.maskApiKey
import com.galiais.agent.engine.parseDotEnvLines
import com.galiais.agent.engine.providerPresets
import com.galiais.agent.engine.splitMultiFileDiff
import com.galiais.agent.engine.suggestProtocolForUrl
import com.galiais.agent.engine.validateOptionalEndpointUrl
import com.galiais.agent.engine.validateProviderBaseUrl
import com.galiais.agent.ui.InlineMathFormatter
import com.galiais.agent.ui.applyAutoPairEdit
import com.galiais.agent.ui.diffStats
import com.galiais.agent.ui.hasRichRenderableContent
import com.galiais.agent.ui.wrapComposerSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreAuditFixesTest {

    @Test
    fun conversationKeyRouteEncodesAndDecodesSpecialCharacters() {
        val rawIds = listOf(
            "session/with/slashes",
            "id?query=1&b=2#frag",
            "space and %20 + 中文ID",
            "simple-uuid-1234",
        )
        for (raw in rawIds) {
            val key = ConversationKey(EngineKind.CLAUDE, raw)
            val route = key.route
            assertTrue(route.startsWith("claude/"))
            val encodedSegment = route.removePrefix("claude/")
            assertFalse("Encoded segment must not contain raw slash", encodedSegment.contains('/'))
            assertFalse("Encoded segment must not contain raw ?", encodedSegment.contains('?'))
            assertFalse("Encoded segment must not contain raw #", encodedSegment.contains('#'))
            assertEquals(raw, ConversationKey.decodeRouteId(encodedSegment))
        }
    }

    @Test
    fun parseDotEnvLinesPreservesHashInUnquotedValuesAndStripsSpacedComments() {
        val sample = """
            # leading comment
            COLOR=#ff0000
            URL=https://example.com/docs#section-2 # trailing comment
            export QUOTED="hello # world" # after quote
            PLAIN=abc123
        """.trimIndent()
        val parsed = parseDotEnvLines(sample).toMap()
        assertEquals("#ff0000", parsed["COLOR"])
        assertEquals("https://example.com/docs#section-2", parsed["URL"])
        assertEquals("hello # world", parsed["QUOTED"])
        assertEquals("abc123", parsed["PLAIN"])
    }

    @Test
    fun splitMultiFileDiffHandlesQuotedAndUnicodePaths() {
        val diff = """
            diff --git a/src/Main.kt b/src/Main.kt
            --- a/src/Main.kt
            +++ b/src/Main.kt
            @@ -1 +1 @@
            -old
            +new
            diff --git "a/文档/说明.md" "b/文档/说明.md"
            new file mode 100644
            --- /dev/null
            +++ "b/文档/说明.md"
            @@ -0,0 +1 @@
            +# 标题
        """.trimIndent()
        val parsed = splitMultiFileDiff(diff)
        assertEquals(2, parsed.size)
        assertEquals("src/Main.kt", parsed[0].path)
        assertEquals("update", parsed[0].kind)
        assertEquals("文档/说明.md", parsed[1].path)
        assertEquals("add", parsed[1].kind)
    }

    @Test
    fun autoPairEditHandlesPairsSkipConsecutiveAndForwardDelete() {
        // 1. Typing '(' inserts '()'
        val old1 = TextFieldValue("", TextRange(0))
        val typed1 = TextFieldValue("(", TextRange(1))
        val res1 = applyAutoPairEdit(old1, typed1)
        assertEquals("()", res1.text)
        assertEquals(TextRange(1), res1.selection)

        // 2. Typing ')' when cursor is right before ')' skips instead of doubling
        val typedClose = TextFieldValue("())", TextRange(2))
        val res2 = applyAutoPairEdit(res1, typedClose)
        assertEquals("()", res2.text)
        assertEquals(TextRange(2), res2.selection)

        // 3. Typing second '$' right after '$' does not auto-double into '$$$'
        val oldDollar = TextFieldValue("$", TextRange(1))
        val typedSecond = TextFieldValue("$$", TextRange(2))
        val res3 = applyAutoPairEdit(oldDollar, typedSecond)
        assertEquals("$$", res3.text)

        // 4. Backspace inside empty '()' deletes both chars
        val afterBs = TextFieldValue(")", TextRange(0))
        val res4 = applyAutoPairEdit(res1, afterBs)
        assertEquals("", res4.text)
        assertEquals(TextRange(0), res4.selection)

        // 5. Forward-delete at index 1 in '()' keeps text without eating extra char
        val oldFwd = TextFieldValue("()", TextRange(1))
        val afterFwd = TextFieldValue("(", TextRange(1))
        val res5 = applyAutoPairEdit(oldFwd, afterFwd)
        assertEquals("(", res5.text)
        assertEquals(TextRange(1), res5.selection)
    }

    @Test
    fun wrapComposerSelectionSupportsNestedFormattingWithoutEatingSuffix() {
        val initial = TextFieldValue("hello world", TextRange(0, 5))
        val bolded = wrapComposerSelection(initial, "**", "**")
        assertEquals("**hello** world", bolded.text)
        assertEquals(TextRange(2, 7), bolded.selection)

        val boldItalic = wrapComposerSelection(bolded, "*", "*")
        assertEquals("***hello*** world", boldItalic.text)
        assertEquals(TextRange(3, 8), boldItalic.selection)
    }

    @Test
    fun richRenderableDetectionAndInlineMathFormatter() {
        assertFalse(hasRichRenderableContent("你好，帮我看下代码"))
        assertTrue(hasRichRenderableContent("请看公式 \$E = mc^2\$"))
        assertFalse(hasRichRenderableContent("请看公式 \$E = mc^2\$", renderMath = false))
        assertTrue(hasRichRenderableContent("```kotlin\nprintln(1)\n```"))

        val formatted = InlineMathFormatter.toPrettyUnicode("\\alpha + \\frac{1}{2} \\le \\infty")
        assertTrue(formatted.contains("α"))
        assertTrue(formatted.contains("≤"))
        assertTrue(formatted.contains("∞"))
    }

    @Test
    fun contextWindowInferenceAndUsageRatioSeparation() {
        assertEquals(1_048_576L, inferModelContextWindow("gemini-4-neon-high"))
        assertEquals(1_000_000L, inferModelContextWindow("claude-opus-4-6[1m]"))
        assertEquals(200_000L, inferModelContextWindow("claude-sonnet-4-6"))
        assertEquals(272_000L, inferModelContextWindow("gpt-5.4"))
        assertEquals(128_000L, inferModelContextWindow("deepseek-chat"))
        assertEquals(262_144L, inferModelContextWindow("qwen3-coder-plus"))

        // 即使会话累计已消耗 520k，只要当前上下文窗口占用是 40k/200k，占比必须是 20% 而非 100% 爆表
        val usage = TokenUsage(
            totalTokens = 40_000L,
            inputTokens = 38_000L,
            cachedInputTokens = 30_000L,
            outputTokens = 2_000L,
            contextWindow = 200_000L,
            sessionTotalTokens = 520_000L,
        )
        assertEquals(0.20f, usage.ratio() ?: -1f, 0.001f)
        assertTrue(usage.compactLabel().contains("20%"))
    }

    @Test
    fun providerUrlValidationMaskingProtocolSuggestionAndPresetsIntegrity() {
        // 本机、局域网、.local mDNS 与 Tailscale 100.64.0.0/10 允许 http://
        assertEquals("http://127.0.0.1:11434/v1", validateProviderBaseUrl("http://127.0.0.1:11434/v1/"))
        assertEquals("http://mac-studio.local:11434/v1", validateProviderBaseUrl("http://mac-studio.local:11434/v1"))
        assertEquals("http://100.88.12.34:8080/v1", validateProviderBaseUrl("http://100.88.12.34:8080/v1"))
        assertEquals("https://api.openai.com/v1", validateProviderBaseUrl("https://api.openai.com/v1"))

        // 公网明文 http:// 必须拒绝
        try {
            validateProviderBaseUrl("http://api.openai.com/v1")
            fail("Expected public http URL to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            validateProviderBaseUrl("http://100.128.0.1/v1")
            fail("Expected non-CGNAT 100.128.x.x http URL to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        // 脱敏规则
        assertEquals("", maskApiKey("   "))
        assertEquals("••••5678", maskApiKey("12345678"))
        assertEquals("sk-a••••wxyz", maskApiKey("sk-ant-1234567890wxyz"))

        // 智能协议推断
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, suggestProtocolForUrl("https://api.anthropic.com"))
        assertEquals(ProviderProtocol.GOOGLE_GEMINI, suggestProtocolForUrl("https://generativelanguage.googleapis.com/v1beta"))
        assertEquals(ProviderProtocol.OPENAI_CHAT, suggestProtocolForUrl("http://127.0.0.1:11434/v1"))
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, suggestProtocolForUrl("https://api.openai.com/v1"))
        assertNull(suggestProtocolForUrl("https://my-custom-relay.example.com/v1"))

        // 路由组虚拟服务商标识
        val virtualGroup = CustomProvider(
            id = "${CustomProvider.GROUP_PREFIX}ha1",
            name = "主力高可用",
            protocol = ProviderProtocol.OPENAI_RESPONSES,
            baseUrl = "http://127.0.0.1:1/group",
            authMode = ProviderAuthMode.NONE,
            models = listOf("auto"),
        )
        assertTrue(virtualGroup.isRoutingGroup)
        assertEquals("路由组", virtualGroup.displayTypeBadge())
        assertEquals("路由组", virtualGroup.supportedProtocolsSummary())

        // 校验所有内置服务商预设及其区域端点均合法
        assertTrue(providerPresets.isNotEmpty())
        providerPresets.forEach { preset ->
            validateProviderBaseUrl(preset.baseUrl)
            validateOptionalEndpointUrl(preset.chatUrl)
            validateOptionalEndpointUrl(preset.responsesUrl)
            validateOptionalEndpointUrl(preset.anthropicUrl)
            validateOptionalEndpointUrl(preset.geminiUrl)
            preset.regions.forEach { region ->
                validateOptionalEndpointUrl(region.chatUrl)
                validateOptionalEndpointUrl(region.responsesUrl)
                validateOptionalEndpointUrl(region.anthropicUrl)
                validateOptionalEndpointUrl(region.geminiUrl)
            }
        }
    }

    @Test
    fun tokenUsagePrecisionDiffStatsAndAntigravityFirstClassNaming() {
        val usage = TokenUsage(
            totalTokens = 52_000L,
            inputTokens = 50_000L,
            cachedInputTokens = 35_000L,
            cacheWriteTokens = 5_000L,
            outputTokens = 2_000L,
            reasoningTokens = 600L,
            contextWindow = 1_048_576L,
            sessionTotalTokens = 180_000L,
        )
        assertEquals(10_000L, usage.uncachedInputTokens)
        assertEquals(70.0, usage.cacheHitPercent() ?: -1.0, 0.01)
        assertTrue(usage.compactLabel().contains("1.05M"))

        // diffStats 不跳过 hunk 内以 "+++" 或 "---" 开头的正文改动行，同时忽略文件头
        val trickyDiff = """
            diff --git a/doc.md b/doc.md
            --- a/doc.md
            +++ b/doc.md
            @@ -1,2 +1,3 @@
             normal
            ----old-divider
            ++++new-divider
            +regular-add
        """.trimIndent()
        val (added, removed) = diffStats(trickyDiff)
        assertEquals(2, added)
        assertEquals(1, removed)

        // Antigravity 统一去掉 CLI 后缀并与 Codex / Claude Code 同列三大内置 Agent
        assertEquals("Antigravity", BuiltInAgents.ANTIGRAVITY.name)
        assertEquals(listOf("codex", "claude", "antigravity"), BuiltInAgents.ALL.map { it.id })
        assertTrue(BuiltInAgents.ALL.all { it.builtIn && it.preinstalled })
    }
}
