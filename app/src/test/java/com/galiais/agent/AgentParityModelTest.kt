package com.galiais.agent

import com.galiais.agent.engine.AgentCapabilities
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.BackgroundTaskEntry
import com.galiais.agent.engine.BuiltInAgents
import com.galiais.agent.engine.ContextCategoryRow
import com.galiais.agent.engine.ContextGridCell
import com.galiais.agent.engine.ContextUsageBreakdown
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.ConversationOptions
import com.galiais.agent.engine.ConversationState
import com.galiais.agent.engine.ConversationSummary
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.GuardianDeniedAction
import com.galiais.agent.ui.builtInSlashCommands
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentParityModelTest {
    @Test
    fun capabilitiesReflectThreeAgentParity() {
        assertTrue(AgentCapabilities.CODEX.modes)
        assertTrue(AgentCapabilities.CLAUDE.archive)
        assertTrue(BuiltInAgents.ANTIGRAVITY.capabilities.fork)
        assertTrue(BuiltInAgents.ANTIGRAVITY.capabilities.archive)
        assertTrue(AgentCatalog.capabilitiesOf(EngineKind.of("antigravity")).fork)
    }

    @Test
    fun unifiedSlashCommandsCoverAllThreeEngines() {
        val codexCmds = builtInSlashCommands(EngineKind.CODEX).map { it.name }.toSet()
        assertTrue(setOf("usage", "context", "fork", "compact", "queue", "tasks", "memory", "plan", "add-dir", "doctor", "tui", "archive").all { it in codexCmds })

        val claudeCmds = builtInSlashCommands(EngineKind.CLAUDE).map { it.name }.toSet()
        assertTrue(setOf("usage", "context", "fork", "compact", "tasks", "agents", "reload", "plan", "add-dir", "doctor", "tui", "archive").all { it in claudeCmds })

        val agyCmds = builtInSlashCommands(EngineKind.of("antigravity")).map { it.name }.toSet()
        assertTrue(setOf("usage", "context", "fork", "compact", "plan", "add-dir", "doctor", "tui", "archive").all { it in agyCmds })
    }

    @Test
    fun parityStateModelsSupportDynamicModesTasksGridAndGuardian() {
        val opts = ConversationOptions.fromJson(
            JSONObject()
                .put("version", 1)
                .put("collaborationMode", "pair-review")
                .put("permissionMode", "plan"),
        )
        assertEquals("pair-review", opts.collaborationMode)

        val breakdown = ContextUsageBreakdown(
            engine = EngineKind.CLAUDE,
            model = "claude-sonnet-4-6",
            totalTokens = 45_000,
            maxTokens = 200_000,
            percentage = 22.5,
            categories = listOf(
                ContextCategoryRow("system_prompt", "系统提示词", 8_000, "used", "#6366F1"),
                ContextCategoryRow("free_space", "剩余可用窗口", 155_000, "free"),
            ),
            gridRows = listOf(listOf(ContextGridCell("system_prompt", "#6366F1", "used"))),
        )
        assertEquals(1, breakdown.gridRows.size)

        val st = ConversationState(
            summary = ConversationSummary(
                key = ConversationKey(EngineKind.CLAUDE, "s1"),
                title = "t",
                preview = "",
                cwd = "/home/agent/workspace",
                updatedAt = 1L,
                tag = "release",
                gitBranch = "main",
            ),
            backgroundTasks = listOf(BackgroundTaskEntry("task-1", "Run build", "gradle", "running")),
            guardianDenied = GuardianDeniedAction("rev-1", "rm -rf", "high", "destructive"),
            claudeAccountSummary = "dev@example.com / MAX",
            fastModeDisabledReason = "plan unsupported",
        )
        assertEquals("release", st.summary.tag)
        assertEquals("main", st.summary.gitBranch)
        assertEquals(1, st.backgroundTasks.size)
        assertEquals("rev-1", st.guardianDenied?.reviewId)
    }
}
