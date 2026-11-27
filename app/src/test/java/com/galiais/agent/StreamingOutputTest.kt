package com.galiais.agent

import com.galiais.agent.engine.STREAM_ANIM_BLUR_SLIDE
import com.galiais.agent.engine.STREAM_ANIM_INK_BLOOM
import com.galiais.agent.engine.STREAM_ANIM_INSTANT
import com.galiais.agent.engine.STREAM_ANIM_LUMINOUS_SWEEP
import com.galiais.agent.engine.STREAM_ANIM_SMOOTH_FADE
import com.galiais.agent.engine.STREAM_ANIM_TYPEWRITER
import com.galiais.agent.engine.STREAM_ANIMATION_STYLES
import com.galiais.agent.engine.STREAM_SPEEDS
import com.galiais.agent.engine.STREAM_SPEED_BALANCED
import com.galiais.agent.engine.STREAM_SPEED_FAST
import com.galiais.agent.engine.STREAM_SPEED_GENTLE
import com.galiais.agent.ui.STREAM_ANIMATION_OPTIONS
import com.galiais.agent.ui.StreamRevealProgressRegistry
import com.galiais.agent.ui.computePacerStep
import com.galiais.agent.ui.repairStreamingMarkdownTail
import com.galiais.agent.ui.safeCharBoundary
import com.galiais.agent.ui.splitMarkdownVirtualChunksIndexed
import com.galiais.agent.ui.splitStreamingMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingOutputTest {

    @Test
    fun animationStyleOptionsCoverAllPersistedConstants() {
        val optionIds = STREAM_ANIMATION_OPTIONS.map { it.id }.toSet()
        assertEquals(STREAM_ANIMATION_STYLES, optionIds)
        assertEquals(6, optionIds.size)
        assertEquals(3, STREAM_SPEEDS.size)
    }

    @Test
    fun safeCharBoundaryNeverSplitsSurrogatePairsOrCrLf() {
        val emojiText = "A😀B🚀"
        // '😀' 位于索引 1..2（高代理 1，低代理 2）
        assertEquals(0, safeCharBoundary(emojiText, -3))
        assertEquals(1, safeCharBoundary(emojiText, 1))
        assertEquals(3, safeCharBoundary(emojiText, 2))
        assertEquals(3, safeCharBoundary(emojiText, 3))
        assertEquals(emojiText.length, safeCharBoundary(emojiText, 99))

        val crlfText = "line1\r\nline2"
        // '\r' 在 5，'\n' 在 6；切到 6 时不能把 \r\n 拆成半个
        assertEquals(7, safeCharBoundary(crlfText, 6))
    }

    @Test
    fun computePacerStepRespectsInstantSpeedPresetsAndCatchUp() {
        // 极速直出始终一次性释放全部积压
        assertEquals(120, computePacerStep(120, 16f, STREAM_SPEED_GENTLE, STREAM_ANIM_INSTANT, finishing = false, nextChar = 'a'))
        assertEquals(0, computePacerStep(0, 16f, STREAM_SPEED_BALANCED, STREAM_ANIM_SMOOTH_FADE, finishing = false, nextChar = null))

        // 流速档位单调递增（同一 16ms 帧、同等小积压）
        val gentle = computePacerStep(20, 16f, STREAM_SPEED_GENTLE, STREAM_ANIM_SMOOTH_FADE, finishing = false, nextChar = '中')
        val balanced = computePacerStep(20, 16f, STREAM_SPEED_BALANCED, STREAM_ANIM_SMOOTH_FADE, finishing = false, nextChar = '中')
        val fast = computePacerStep(20, 16f, STREAM_SPEED_FAST, STREAM_ANIM_SMOOTH_FADE, finishing = false, nextChar = '中')
        assertTrue("Expected gentle($gentle) <= balanced($balanced) <= fast($fast)", gentle <= balanced && balanced <= fast)

        // 高积压时自适应加速追赶，不会落队
        val normalBacklog = computePacerStep(10, 16f, STREAM_SPEED_BALANCED, STREAM_ANIM_SMOOTH_FADE, finishing = false, nextChar = 'a')
        val hugeBacklog = computePacerStep(400, 16f, STREAM_SPEED_BALANCED, STREAM_ANIM_SMOOTH_FADE, finishing = false, nextChar = 'a')
        assertTrue("Expected hugeBacklog($hugeBacklog) > normalBacklog($normalBacklog)", hugeBacklog > normalBacklog * 3)

        // 流式结束收尾冲刺快于常规节奏
        val regular = computePacerStep(80, 16f, STREAM_SPEED_GENTLE, STREAM_ANIM_INK_BLOOM, finishing = false, nextChar = '字')
        val sprint = computePacerStep(80, 16f, STREAM_SPEED_GENTLE, STREAM_ANIM_INK_BLOOM, finishing = true, nextChar = '字')
        assertTrue("Expected finishing sprint($sprint) >= regular($regular)", sprint >= regular)

        // 打字机风格在句末标点产生适度微顿
        val twWord = computePacerStep(18, 32f, STREAM_SPEED_BALANCED, STREAM_ANIM_TYPEWRITER, finishing = false, nextChar = 'a')
        val twStop = computePacerStep(18, 32f, STREAM_SPEED_BALANCED, STREAM_ANIM_TYPEWRITER, finishing = false, nextChar = '。')
        assertTrue("Expected punctuation pause twStop($twStop) <= twWord($twWord)", twStop <= twWord)

        // 所有风格均能正常推进且不越界
        for (style in listOf(STREAM_ANIM_SMOOTH_FADE, STREAM_ANIM_TYPEWRITER, STREAM_ANIM_LUMINOUS_SWEEP, STREAM_ANIM_INK_BLOOM, STREAM_ANIM_BLUR_SLIDE)) {
            val step = computePacerStep(5, 16f, STREAM_SPEED_BALANCED, style, finishing = false, nextChar = 'x')
            assertTrue("Step out of bounds for $style: $step", step in 1..5)
        }
    }

    @Test
    fun splitStreamingMarkdownBlocksFreezesCompletedBlocksStably() {
        val block1 = "第一段已完成内容：用于验证块级增量冻结后字符串恒定不变，确保 Compose key 与 CommonMark LRU 缓存完全命中。".repeat(5)
        val block2 = "```kotlin\nfun stream() {\n    println(\"ok\")\n}\n```"
        val block3Part1 = "第三段正在逐字增长中"
        val block3Part2 = "第三段正在逐字增长中，继续追加更多内容并测试增量冻结稳定性。"

        val splitA = splitStreamingMarkdownBlocks("$block1\n\n$block2\n\n$block3Part1", targetFrozenBlockChars = 180)
        val splitB = splitStreamingMarkdownBlocks("$block1\n\n$block2\n\n$block3Part2", targetFrozenBlockChars = 180)

        assertTrue("Expected at least 1 frozen block", splitA.frozenBlocks.isNotEmpty())
        assertEquals(splitA.frozenBlocks, splitB.frozenBlocks)
        // 已冻结代码块不能被腰斩
        for (frozen in splitB.frozenBlocks) {
            val fenceCount = Regex("(?m)^```").findAll(frozen).count()
            assertEquals("Fence split inside frozen block", 0, fenceCount % 2)
        }
        assertTrue(splitB.liveTail.contains("继续追加更多内容"))
    }

    @Test
    fun repairStreamingMarkdownTailClosesUnfinishedSyntaxSafely() {
        // 未闭合代码块自动补齐围栏，避免首行闪现裸反引号
        val openFence = "示例：\n```kotlin\nval x = 1"
        val repairedFence = repairStreamingMarkdownTail(openFence)
        assertTrue(repairedFence.endsWith("\n```"))

        // 已闭合代码块不重复补齐
        val closedFence = "```kotlin\nval x = 1\n```"
        assertEquals(closedFence, repairStreamingMarkdownTail(closedFence))

        // 未闭合块级数学公式自动闭合
        val openMath = "$$\nE = mc^2"
        assertTrue(repairStreamingMarkdownTail(openMath).endsWith("\n$$"))

        // 未闭合行内代码自动补闭合；末尾刚打出的单个反引号直接收敛避免空代码底块
        assertEquals("调用 `render()`", repairStreamingMarkdownTail("调用 `render()"))
        assertEquals("调用 ", repairStreamingMarkdownTail("调用 `"))

        // 未闭合粗体与删除线自动补齐
        assertEquals("这是 **重点结论**", repairStreamingMarkdownTail("这是 **重点结论"))
        assertEquals("这是 ", repairStreamingMarkdownTail("这是 **"))
        assertEquals("旧方案 ~~已废弃~~", repairStreamingMarkdownTail("旧方案 ~~已废弃"))

        // 普通无序列表 `* item` 不被误判为粗体
        val bullet = "* 第一项\n* 第二项"
        assertEquals(bullet, repairStreamingMarkdownTail(bullet))
    }

    @Test
    fun revealRegistryIsMonotonicAndVirtualChunkOffsetsMatchSource() {
        val id = "test-msg-monotonic"
        StreamRevealProgressRegistry.clear(id)
        assertEquals(0, StreamRevealProgressRegistry.maxRevealed(id))
        StreamRevealProgressRegistry.record(id, 420)
        StreamRevealProgressRegistry.record(id, 300) // 不能倒退
        assertEquals(420, StreamRevealProgressRegistry.maxRevealed(id))
        StreamRevealProgressRegistry.clear(id)
        assertEquals(0, StreamRevealProgressRegistry.maxRevealed(id))

        val para = "这是一段用于测试带偏移量虚拟切片的中文段落。".repeat(22)
        val full = "$para\n\n$para\n\n$para"
        val indexed = splitMarkdownVirtualChunksIndexed(full, targetChunkChars = 400)
        assertTrue(indexed.size >= 2)
        for ((offset, slice) in indexed) {
            assertFalse(slice.isEmpty())
            assertTrue("Offset $offset must match slice prefix in full text", full.startsWith(slice, startIndex = offset))
        }
    }
}
