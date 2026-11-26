package com.galiais.agent

import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.ItemStatus
import com.galiais.agent.engine.PlanStep
import com.galiais.agent.engine.TimelineItem
import com.galiais.agent.engine.computeStats
import com.galiais.agent.engine.isAcpMetaToolTitle
import com.galiais.agent.engine.normalizeAcpMetaToolName
import com.galiais.agent.engine.parseElicitationSchema
import com.galiais.agent.engine.parseRecommendedLabel
import com.galiais.agent.ui.ToolCallKind
import com.galiais.agent.ui.describeToolCall
import com.galiais.agent.ui.extractTaskListMarker
import com.galiais.agent.ui.groupIntoTurnBlocks
import com.galiais.agent.ui.parseFileLinkTarget
import org.commonmark.node.BulletList
import org.commonmark.node.ListItem
import org.commonmark.parser.Parser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInteractionSuiteTest {

    @Test
    fun `plan step stats treats skipped as settled terminal state`() {
        val steps = listOf(
            PlanStep("分析现状", "done"),
            PlanStep("编写计划", "completed"),
            PlanStep("执行测试", "skipped"),
            PlanStep("清理环境", "completed"),
        )
        val stats = steps.computeStats()
        assertEquals(4, stats.total)
        assertEquals(3, stats.completed)
        assertEquals(1, stats.skipped)
        assertEquals(4, stats.settled)
        assertTrue(stats.allSettled)
        assertEquals(1.0f, stats.progress, 0.001f)

        val withFailure = steps + PlanStep("发布版本", "failed")
        val failStats = withFailure.computeStats()
        assertFalse(failStats.allSettled)
        assertEquals(1, failStats.failed)
    }

    @Test
    fun `parseRecommendedLabel strips tag cleanly while flagging recommendation`() {
        val (label1, rec1) = parseRecommendedLabel("(Recommended) Markdown 优先方案")
        assertTrue(rec1)
        assertEquals("Markdown 优先方案", label1)

        val (label2, rec2) = parseRecommendedLabel("标准模式 (Recommended)")
        assertTrue(rec2)
        assertEquals("标准模式", label2)

        val (label3, rec3) = parseRecommendedLabel("纯文本模式")
        assertFalse(rec3)
        assertEquals("纯文本模式", label3)
    }

    @Test
    fun `parseElicitationSchema supports nullable multi_enum oneOf descriptions and string_array`() {
        val raw = JSONObject(
            """
            {
              "requestedSchema": {
                "type": "object",
                "required": ["features", "mode"],
                "properties": {
                  "features": {
                    "title": "启用哪些能力",
                    "anyOf": [
                      {
                        "type": "array",
                        "items": {
                          "oneOf": [
                            {"const": "plan", "title": "(Recommended) 计划卡片", "description": "展示实施计划与审批按钮"},
                            {"const": "steps", "title": "实时步骤清单", "description": "跟踪任务进度"}
                          ]
                        }
                      },
                      {"type": "null"}
                    ],
                    "default": ["plan"]
                  },
                  "mode": {
                    "title": "部署策略",
                    "type": ["string", "null"],
                    "oneOf": [
                      {"const": "canary", "title": "灰度发布 (Recommended)", "description": "先放量 10%"},
                      {"const": "full", "title": "全量发布"}
                    ]
                  },
                  "tags": {
                    "title": "附加标签",
                    "type": "array",
                    "items": {"type": "string"}
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val fields = parseElicitationSchema(raw)
        assertEquals(3, fields.size)

        val features = fields.first { it.name == "features" }
        assertEquals("multi_enum", features.type)
        assertTrue(features.required)
        assertEquals(listOf("plan", "steps"), features.enumValues)
        assertEquals(listOf("(Recommended) 计划卡片", "实时步骤清单"), features.enumNames)
        assertEquals(listOf("展示实施计划与审批按钮", "跟踪任务进度"), features.enumDescriptions)
        assertEquals(listOf("plan"), features.defaultMultiValues)

        val mode = fields.first { it.name == "mode" }
        assertEquals("enum", mode.type)
        assertEquals(listOf("canary", "full"), mode.enumValues)
        assertEquals(listOf("先放量 10%", ""), mode.enumDescriptions)

        val tags = fields.first { it.name == "tags" }
        assertEquals("string_array", tags.type)
        assertFalse(tags.required)
    }

    @Test
    fun `parseFileLinkTarget decodes utf8 path and line fragment`() {
        val parsed = parseFileLinkTarget("file:///home/agent/workspace/%E6%96%87%E6%A1%A3.md#L10-L24")
        assertNotNull(parsed)
        assertEquals("/home/agent/workspace/文档.md", parsed!!.first)
        assertEquals("L10-L24", parsed.second)

        assertNull(parseFileLinkTarget("https://example.com/test.md"))
    }

    @Test
    fun `extractTaskListMarker detects unchecked and checked gfm task items`() {
        val parser = Parser.builder().build()
        val doc = parser.parse("- [x] 已完成步骤\n- [ ] 待完成步骤\n- 普通列表项")
        val list = doc.firstChild as BulletList
        val items = generateSequence(list.firstChild) { it.next }.filterIsInstance<ListItem>().toList()
        assertEquals(3, items.size)
        assertEquals(true to 4, extractTaskListMarker(items[0]))
        assertEquals(false to 4, extractTaskListMarker(items[1]))
        assertNull(extractTaskListMarker(items[2]))
    }

    @Test
    fun `groupIntoTurnBlocks surfaces interaction receipts and plans outside collapsed trace`() {
        val items = listOf(
            TimelineItem.UserMessage("u1", "请先制定计划"),
            TimelineItem.Reasoning("r1", "思考中"),
            TimelineItem.InteractionReceipt("rc1", "question", "已回答 2 项问题", listOf("格式" to "Markdown")),
            TimelineItem.Plan("p1", "# 实施计划\n- [ ] 步骤 1", title = "实施计划", requestFeedback = true, status = ItemStatus.RUNNING),
            TimelineItem.AgentMessage("a1", "请审阅计划"),
        )
        val blocks = groupIntoTurnBlocks(items, emptyMap(), running = false)
        assertEquals(1, blocks.size)
        assertEquals(1, blocks[0].receipts.size)
        assertEquals(1, blocks[0].planItems.size)
        assertEquals(1, blocks[0].traceItems.size)
        assertEquals("a1", blocks[0].finalMessage?.id)
    }

    @Test
    fun `all built-in agents advertise revert capability for edit-and-resend`() {
        assertTrue(AgentCatalog.capabilitiesOf(EngineKind.CODEX).revert)
        assertTrue(AgentCatalog.capabilitiesOf(EngineKind.CLAUDE).revert)
        assertTrue(AgentCatalog.capabilitiesOf(EngineKind.of("antigravity")).revert)
        assertTrue(AgentCatalog.capabilitiesOf(EngineKind.of("custom-external")).revert)
    }

    @Test
    fun `groupIntoTurnBlocks preserves trailing error notice on failed turn for resend actions`() {
        val items = listOf(
            TimelineItem.UserMessage("u1", "第一轮问题"),
            TimelineItem.AgentMessage("a1", "第一轮答复"),
            TimelineItem.UserMessage("u2", "第二轮提问"),
            TimelineItem.Notice("err-u2", "网络连接超时", error = true),
        )
        val blocks = groupIntoTurnBlocks(items, emptyMap(), running = false)
        assertEquals(2, blocks.size)
        assertEquals("u2", blocks[1].userMessage?.id)
        assertNull(blocks[1].finalMessage)
        assertEquals(1, blocks[1].trailingNotices.size)
        assertTrue(blocks[1].trailingNotices[0].error)
    }

    @Test
    fun `describeToolCall does not misclassify Antigravity file reads as MCP`() {
        val readWithKind = describeToolCall("File Solbraid/runtime/agent-hub.mjs", "", kindHint = "read")
        assertEquals(ToolCallKind.READ_FILE, readWithKind.kind)
        assertEquals("读取文件", readWithKind.badge)
        assertEquals("agent-hub.mjs", readWithKind.summary)

        val legacyReadWithoutKind = describeToolCall("File Solbraid/app/src/main/java/com/galiais/agent/engine/AppSettings.kt", "")
        assertEquals(ToolCallKind.READ_FILE, legacyReadWithoutKind.kind)
        assertEquals("AppSettings.kt", legacyReadWithoutKind.summary)

        val editWithKind = describeToolCall("File Solbraid/runtime/acp-driver.mjs", "", kindHint = "edit")
        assertEquals(ToolCallKind.EDIT_FILE, editWithKind.kind)
        assertEquals("编辑文件", editWithKind.badge)

        val trueAgyMcp = describeToolCall("Run mcp_solbraid_plugin_ctl_solbraid_self_build", """{"action":"status"}""", kindHint = "other")
        assertEquals(ToolCallKind.MCP, trueAgyMcp.kind)
        assertEquals("MCP", trueAgyMcp.badge)
        assertTrue(trueAgyMcp.summary.contains("solbraid"))

        val trueClaudeMcp = describeToolCall("mcp__github__search_code", """{"query":"test"}""")
        assertEquals(ToolCallKind.MCP, trueClaudeMcp.kind)
        assertEquals("MCP", trueClaudeMcp.badge)
    }

    @Test
    fun `normalizeAcpMetaToolName strips Run prefix for Antigravity steps and focus`() {
        assertEquals("update_steps", normalizeAcpMetaToolName("Run update_steps"))
        assertEquals("update_focus", normalizeAcpMetaToolName("Run update_focus"))
        assertEquals("task_boundary", normalizeAcpMetaToolName("Run task_boundary"))
        assertTrue(isAcpMetaToolTitle("Run update_steps"))
        assertTrue(isAcpMetaToolTitle("Run update_focus"))
        assertFalse(isAcpMetaToolTitle("File Solbraid/runtime/agent-hub.mjs"))
        assertFalse(isAcpMetaToolTitle("Run mcp_solbraid_plugin_ctl_solbraid_self_build"))
    }

    @Test
    fun `unifiedDiff handles 4000-line files with minimal hunks instead of full-file replacement`() {
        val baseLines = (1..4000).map { "line_$it = value_$it" }
        val before = baseLines.joinToString("\n") + "\n"

        // 单处修改（靠近尾部 3950 行）：旧算法会因 4000*4000 > 4_000_000 直接退化为 -4000/+4000
        val singleMod = baseLines.toMutableList().apply { this[3949] = "line_3950 = UPDATED" }.joinToString("\n") + "\n"
        val diffSingle = com.galiais.agent.engine.unifiedDiff("big.kt", before, singleMod)
        val (add1, del1) = com.galiais.agent.ui.diffStats(diffSingle)
        assertEquals(1, add1)
        assertEquals(1, del1)
        assertTrue(diffSingle.contains("@@ -3947,7 +3947,7 @@"))

        // 首尾双处远距修改（第 15 行 + 第 3980 行）：验证 Myers / 锚点不会整文件误报
        val dualMod = baseLines.toMutableList().apply {
            this[14] = "line_15 = TOP_EDIT"
            this[3979] = "line_3980 = BOTTOM_EDIT"
        }.joinToString("\n") + "\n"
        val diffDual = com.galiais.agent.engine.unifiedDiff("big.kt", before, dualMod)
        val (add2, del2) = com.galiais.agent.ui.diffStats(diffDual)
        assertEquals(2, add2)
        assertEquals(2, del2)

        // CRLF 与 LF 混用不产生假性全文件差异
        val crlfCopy = baseLines.joinToString("\r\n") + "\r\n"
        val diffEol = com.galiais.agent.engine.unifiedDiff("big.kt", before, crlfCopy)
        assertEquals(0 to 0, com.galiais.agent.ui.diffStats(diffEol))
    }

    @Test
    fun `computeMyersLineDiff stays aligned across insertions larger than 6 lines`() {
        val beforeLines = (1..120).map { "row-$it" }
        val inserted = (1..15).map { "new-inserted-$it" }
        val afterLines = beforeLines.take(30) + inserted + beforeLines.drop(30)
        val ops = com.galiais.agent.engine.computeMyersLineDiff(
            beforeLines.joinToString("\n"),
            afterLines.joinToString("\n"),
            maxLines = 400,
        )
        assertEquals(15, ops.count { it.first == '+' })
        assertEquals(0, ops.count { it.first == '-' })
    }
}
