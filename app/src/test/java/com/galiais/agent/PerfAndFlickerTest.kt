package com.galiais.agent

import com.galiais.agent.engine.FileDiff
import com.galiais.agent.engine.ItemStatus
import com.galiais.agent.engine.TimelineItem
import com.galiais.agent.ui.TurnBlockIncrementalCache
import com.galiais.agent.ui.diffStats
import com.galiais.agent.ui.firstChangedNewLine
import com.galiais.agent.ui.reasoningPreview
import com.galiais.agent.ui.splitMarkdownVirtualChunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfAndFlickerTest {

    @Test
    fun turnBlockIncrementalCacheReusesCompletedTurnsDuringStreaming() {
        val cache = TurnBlockIncrementalCache()
        val u1 = TimelineItem.UserMessage("u1", "第一轮问题")
        val a1 = TimelineItem.AgentMessage("a1", "第一轮完整回答", streaming = false)
        val u2 = TimelineItem.UserMessage("u2", "第二轮问题")
        val a2Part1 = TimelineItem.AgentMessage("a2", "流式输出中…", streaming = true)

        val first = cache.group(listOf(u1, a1, u2, a2Part1), emptyMap(), running = true)
        assertEquals(2, first.size)

        val a2Part2 = TimelineItem.AgentMessage("a2", "流式输出中…继续追加更多内容", streaming = true)
        val second = cache.group(listOf(u1, a1, u2, a2Part2), emptyMap(), running = true)
        assertEquals(2, second.size)
        // 已完成的历史第一轮必须复用完全相同的对象引用，零重复 diffStats / 分组开销
        assertSame(first[0], second[0])
        assertEquals("流式输出中…继续追加更多内容", second[1].finalMessage?.text)
    }

    @Test
    fun turnBlockIncrementalCacheReusesExistingTurnsWhenPrependingHistory() {
        val cache = TurnBlockIncrementalCache()
        val u3 = TimelineItem.UserMessage("u3", "第三轮问题")
        val a3 = TimelineItem.AgentMessage("a3", "第三轮回答", streaming = false)
        val u4 = TimelineItem.UserMessage("u4", "第四轮问题")
        val a4 = TimelineItem.AgentMessage("a4", "第四轮回答", streaming = false)

        val initial = cache.group(listOf(u3, a3, u4, a4), emptyMap(), running = false)
        assertEquals(2, initial.size)

        val u1 = TimelineItem.UserMessage("u1", "第一轮问题")
        val a1 = TimelineItem.AgentMessage("a1", "第一轮回答", streaming = false)
        val u2 = TimelineItem.UserMessage("u2", "第二轮问题")
        val a2 = TimelineItem.AgentMessage("a2", "第二轮回答", streaming = false)
        val prepended = cache.group(listOf(u1, a1, u2, a2, u3, a3, u4, a4), emptyMap(), running = false)
        assertEquals(4, prepended.size)
        // 顶部插页后，原有轮次下标虽已右移，但仍必须 100% 命中缓存复用原对象，杜绝主线程重算
        assertSame(initial[0], prepended[2])
        assertSame(initial[1], prepended[3])

        // 无头轮次（如系统提示在首条用户消息前）必须按首条子项 ID 生成稳定 key，避免插页后 key 漂移导致跳顶
        val headNotice = TimelineItem.Notice("notice-boot", "会话恢复", error = false)
        val withHead = cache.group(listOf(headNotice, u1, a1), emptyMap(), running = false)
        assertEquals("turn-head-notice-boot", withHead[0].id)
        val withHeadAgain = cache.group(listOf(headNotice, u1, a1, u2, a2), emptyMap(), running = false)
        assertSame(withHead[0], withHeadAgain[0])
    }

    @Test
    fun splitMarkdownVirtualChunksKeepsFencesAndMathIntact() {
        val para = "这是一段用于测试超长回答块级虚拟化切分的中文自然段内容，确保切分长度达到阈值后仅在安全空行边界断开。\n".repeat(12)
        val fence = "```kotlin\n" + (1..25).joinToString("\n") { "val x$it = $it\n" } + "\n```"
        val math = "$$\n\\sum_{i=1}^{n} x_i^2 = \\int_0^1 f(t)\\,dt\n$$"
        val full = "$para\n$fence\n\n$math\n\n$para"

        val chunks = splitMarkdownVirtualChunks(full, targetChunkChars = 600)
        assertTrue("Expected >= 2 chunks, got ${chunks.size}", chunks.size >= 2)
        // 每个切片内的三反引号数量必须为偶数（绝不腰斩代码块）
        for (c in chunks) {
            val fenceCount = Regex("(?m)^```").findAll(c).count()
            assertEquals("Fence split across chunk boundary: $c", 0, fenceCount % 2)
        }
        assertTrue(chunks.any { it.contains("\\sum_{i=1}^{n}") && it.contains("$$") })
    }

    @Test
    fun reasoningPreviewFastScanExtractsHeadingsAndBoldTitles() {
        val sample = "**初步分析**\n先检查配置。\n\n### 定位核心瓶颈\n继续排查日志…"
        val p = reasoningPreview(sample, streaming = false)
        assertEquals("定位核心瓶颈", p.title)

        val boldSample = "普通思考过程\n**准备修复并发竞态**\n正在编辑"
        val p2 = reasoningPreview(boldSample, streaming = false)
        assertEquals("准备修复并发竞态", p2.title)

        val partial = "**尚未传完"
        val p3 = reasoningPreview(partial, streaming = true)
        assertEquals(null, p3.title)
        assertEquals("", p3.body)
    }

    @Test
    fun cachedDiffStatsAndFirstChangedLineMatchUnifiedDiff() {
        val diff = """
            --- a/Demo.kt
            +++ b/Demo.kt
            @@ -10,4 +10,5 @@
             fun a() {}
            -val old = 1
            +val next = 2
            +val extra = 3
             fun b() {}
        """.trimIndent()
        assertEquals(2 to 1, diffStats(diff))
        assertEquals(11, firstChangedNewLine(diff))
        // 验证二次命中缓存结果一致
        assertEquals(2 to 1, diffStats(diff))
        assertEquals(11, firstChangedNewLine(diff))
    }

    @Test
    fun reasoningSummaryTextCachedAcrossReads() {
        val r = TimelineItem.Reasoning(
            id = "r1",
            text = "",
            summaryParts = mapOf(2 to "第二步", 0 to "第零步", 1 to "第一步"),
        )
        assertEquals("第零步\n\n第一步\n\n第二步", r.summaryText)
        assertSame(r.summaryText, r.summaryText)
    }
}
