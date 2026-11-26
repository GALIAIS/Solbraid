package com.galiais.agent

import com.galiais.agent.engine.ExtensionRegistry
import com.galiais.agent.engine.HookTestResult
import com.galiais.agent.engine.ManagedHook
import com.galiais.agent.engine.ManagedInstruction
import com.galiais.agent.engine.ManagedMcpServer
import com.galiais.agent.engine.ManagedPlugin
import com.galiais.agent.engine.ManagedSkill
import com.galiais.agent.engine.ManagedSubagent
import com.galiais.agent.engine.McpProbeResult
import com.galiais.agent.engine.McpToolInfo
import com.galiais.agent.engine.PluginCommand
import com.galiais.agent.engine.SkillCompanionFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionResourceSuiteTest {

    @Test
    fun `ManagedInstruction estimates tokens and preserves scope and priority on round-trip`() {
        val inst = ManagedInstruction(
            id = "inst-proj-1",
            title = "项目规范",
            content = "每次修改代码后必须运行测试并保持零编译警告。",
            enabled = true,
            targets = listOf("claude", "codex"),
            scope = "project:/home/agent/workspace/app",
            priority = 2,
        )
        assertTrue(inst.estimatedTokens >= 10)
        val parsed = ManagedInstruction.fromJson(inst.toJson())
        assertEquals(inst.id, parsed.id)
        assertEquals(inst.title, parsed.title)
        assertEquals(inst.scope, parsed.scope)
        assertEquals(2, parsed.priority)
        assertEquals(listOf("claude", "codex"), parsed.targets)
    }

    @Test
    fun `ManagedSubagent builds official YAML Frontmatter preview and round-trips all fields`() {
        val ag = ManagedSubagent(
            id = "code-reviewer",
            name = "code-reviewer",
            description = "只读审查代码变更",
            model = "claude-sonnet-4-5",
            prompt = "逐行检查安全、并发与边界问题。",
            tools = listOf("Read", "Grep", "Glob"),
            disallowedTools = listOf("Write", "Edit", "Bash"),
            permissionMode = "plan",
            maxTurns = 12,
            scope = "project:/home/agent/workspace/repo",
            enabled = true,
            targets = listOf("all"),
        )
        val md = ag.toMarkdownPreview()
        assertTrue(md.contains("name: code-reviewer"))
        assertTrue(md.contains("model: claude-sonnet-4-5"))
        assertTrue(md.contains("tools: Read, Grep, Glob"))
        assertTrue(md.contains("disallowedTools: Write, Edit, Bash"))
        assertTrue(md.contains("permissionMode: plan"))
        assertTrue(md.contains("maxTurns: 12"))
        assertTrue(md.endsWith("逐行检查安全、并发与边界问题。"))

        val parsed = ManagedSubagent.fromJson(ag.toJson())
        assertEquals(ag.tools, parsed.tools)
        assertEquals(ag.disallowedTools, parsed.disallowedTools)
        assertEquals("plan", parsed.permissionMode)
        assertEquals(12, parsed.maxTurns)
        assertEquals("project:/home/agent/workspace/repo", parsed.scope)
    }

    @Test
    fun `ManagedHook and ExtensionRegistry preserve timeout, scope, description and all 6 resource types`() {
        val hook = ManagedHook(
            id = "hook-guard",
            name = "危险命令拦截",
            event = "PreToolUse",
            matcher = "Bash",
            command = "exit 2",
            timeoutSec = 15,
            scope = "project:/home/agent/workspace/demo",
            description = "拦截高危 rm 命令",
            enabled = true,
            targets = listOf("claude"),
        )
        val reg = ExtensionRegistry(
            mcpServers = listOf(
                ManagedMcpServer(
                    id = "gh",
                    name = "GitHub",
                    transport = "stdio",
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-github"),
                    secretEnvKeys = listOf("GITHUB_TOKEN", "GITHUB_HOST"),
                    scope = "project:/home/agent/workspace/demo",
                ),
            ),
            skills = listOf(
                ManagedSkill(
                    id = "review",
                    name = "review",
                    description = "审查",
                    body = "# Review",
                    dirPath = "/home/agent/.galiais/extensions/skills/review",
                    scope = "global",
                ),
            ),
            plugins = listOf(
                ManagedPlugin(
                    id = "pack-team",
                    name = "团队指令包",
                    version = "1.2.0",
                    commands = listOf(PluginCommand("audit", "安全审计", "执行安全审计")),
                ),
            ),
            instructions = listOf(
                ManagedInstruction(id = "i1", title = "规则1", content = "保持简洁", priority = 1),
            ),
            subagents = listOf(
                ManagedSubagent(id = "s1", name = "qa", prompt = "编写单测", maxTurns = 10),
            ),
            hooks = listOf(hook),
        )
        val roundTripped = ExtensionRegistry.fromJson(reg.toJson().toString())
        assertEquals(1, roundTripped.mcpServers.size)
        assertEquals(listOf("GITHUB_TOKEN", "GITHUB_HOST"), roundTripped.mcpServers[0].secretEnvKeys)
        assertEquals("project:/home/agent/workspace/demo", roundTripped.mcpServers[0].scope)
        assertEquals(1, roundTripped.skills.size)
        assertEquals(1, roundTripped.plugins.size)
        assertEquals("audit", roundTripped.plugins[0].commands[0].name)
        assertEquals(1, roundTripped.instructions.size)
        assertEquals(1, roundTripped.subagents.size)
        assertEquals(10, roundTripped.subagents[0].maxTurns)
        assertEquals(1, roundTripped.hooks.size)
        assertEquals(15, roundTripped.hooks[0].timeoutSec)
        assertEquals("拦截高危 rm 命令", roundTripped.hooks[0].description)
        assertEquals("project:/home/agent/workspace/demo", roundTripped.hooks[0].scope)
    }

    @Test
    fun `McpProbeResult, HookTestResult and SkillCompanionFile carry full metadata`() {
        val probe = McpProbeResult(
            ok = true,
            latencyMs = 48,
            tools = listOf("list_issues", "create_pr"),
            toolDetails = listOf(McpToolInfo("list_issues", "列出仓库 Issue"), McpToolInfo("create_pr", "创建 PR")),
            message = "发现 2 个工具",
        )
        assertEquals(2, probe.toolDetails.size)
        assertEquals("列出仓库 Issue", probe.toolDetails[0].description)

        val blocked = HookTestResult(ok = false, exitCode = 2, blocked = true, latencyMs = 12, stderr = "Blocked!")
        assertTrue(blocked.blocked)
        assertFalse(blocked.ok)

        val f = SkillCompanionFile(relPath = "scripts/lint.sh", absPath = "/home/agent/s/scripts/lint.sh", size = 512)
        assertEquals("scripts/lint.sh", f.relPath)
    }
}
