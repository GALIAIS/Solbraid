package com.galiais.agent

import com.galiais.agent.engine.CustomProvider
import com.galiais.agent.engine.HostPluginSduiPayload
import com.galiais.agent.engine.HostPluginSnapshot
import com.galiais.agent.engine.HostPluginSourceSnapshot
import com.galiais.agent.engine.HostPluginUiSlot
import com.galiais.agent.engine.ProviderAuthMode
import com.galiais.agent.engine.ProviderProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfEvolvingPluginModelTest {

    @Test
    fun `parses self-evolving plugin snapshot fields accurately`() {
        val raw = JSONObject()
            .put("generationId", "gen-5-abcd1234")
            .put("generationSeq", 5)
            .put("safeMode", false)
            .put("hasLastKnownGood", true)
            .put("agentControlAvailable", true)
            .put("pluginsRoot", "/home/agent/.galiais/plugins")
            .put("hostDescriptor", JSONObject().put("executionTier", "trusted-in-process"))
            .put(
                "composerActions",
                JSONArray().put(
                    JSONObject()
                        .put("pluginId", "solbraid.builtin.workspace-inspector")
                        .put("id", "workspace-inspect")
                        .put("label", "工作区体检看板")
                        .put("description", "打开插件看板")
                        .put("panelId", "overview"),
                ),
            )
            .put(
                "plugins",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "solbraid.core.agent-runtime")
                            .put("name", "Agent 运行时内核")
                            .put("version", "1.0.0")
                            .put("kind", "core")
                            .put("status", "active")
                            .put("enabled", true),
                    )
                    .put(
                        JSONObject()
                            .put("id", "solbraid.builtin.workspace-inspector")
                            .put("name", "工作区体检（Agent 升级版）")
                            .put("version", "2.0.0")
                            .put("kind", "local")
                            .put("overriddenBuiltin", true)
                            .put("snapshotCount", 3)
                            .put("status", "active")
                            .put("enabled", true)
                            .put(
                                "composerActions",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "workspace-inspect")
                                        .put("label", "工作区体检看板")
                                        .put("panelId", "overview"),
                                ),
                            ),
                    ),
            )

        val snap = HostPluginSnapshot.fromJson(raw)
        assertTrue(snap.agentControlAvailable)
        assertEquals(5, snap.generationSeq)
        assertEquals(1, snap.corePlugins.size)
        assertEquals(1, snap.extensionPlugins.size)
        assertEquals(1, snap.composerActions.size)
        assertEquals("overview", snap.composerActions[0].panelId)
        assertNull(snap.composerActions[0].command)

        val ext = snap.extensionPlugins.first()
        assertTrue(ext.overriddenBuiltin)
        assertEquals(3, ext.snapshotCount)
        assertFalse(ext.isRemovable) // 已覆写的内置插件走“恢复原版”，不走“卸载”
        assertEquals("solbraid.builtin.workspace-inspector", ext.composerActions.first().pluginId)
    }

    @Test
    fun `parses source snapshot entry accurately`() {
        val snap = HostPluginSourceSnapshot.fromJson(
            JSONObject()
                .put("id", "1764044000000-ab12cd")
                .put("pluginId", "local.evolve.demo")
                .put("version", "0.2.0")
                .put("createdAt", 1764044000000L)
                .put("note", "升级前自动快照")
                .put("fileCount", 3),
        )
        assertEquals("1764044000000-ab12cd", snap.id)
        assertEquals("local.evolve.demo", snap.pluginId)
        assertEquals("0.2.0", snap.version)
        assertEquals(1764044000000L, snap.createdAt)
        assertEquals("升级前自动快照", snap.note)
        assertEquals(3, snap.fileCount)
    }

    @Test
    fun `parses Phase 4 worker tier, SDUI surfaces, slots, translators, and studio overrides`() {
        val raw = JSONObject()
            .put("generationId", "gen-9-beefcafe")
            .put("generationSeq", 9)
            .put("agentControlAvailable", true)
            .put("studioOverrides", JSONArray().put("artifact-renderer.js"))
            .put(
                "drivers",
                JSONArray().put(
                    JSONObject()
                        .put("id", "local-echo-agent")
                        .put("name", "回声引擎")
                        .put("protocol", "plugin-driver")
                        .put("executable", true)
                        .put("pluginId", "local.worker.suite"),
                ),
            )
            .put(
                "plugins",
                JSONArray().put(
                    JSONObject()
                        .put("id", "local.worker.suite")
                        .put("name", "线程隔离套件")
                        .put("version", "1.0.0")
                        .put("kind", "local")
                        .put("executionTier", "worker-isolated")
                        .put("status", "active")
                        .put("enabled", true)
                        .put("surfaces", JSONArray().put(JSONObject().put("id", "worker-home").put("title", "原生页")))
                        .put("uiSlots", JSONArray().put(JSONObject().put("id", "top-pill").put("slot", "chat.topbar").put("badge", "Worker").put("surfaceId", "worker-home")))
                        .put("translators", JSONArray().put(JSONObject().put("id", "custom-wire").put("name", "自定义协议"))),
                ),
            )

        val snap = HostPluginSnapshot.fromJson(raw)
        assertEquals(listOf("artifact-renderer.js"), snap.studioOverrides)
        assertTrue(snap.drivers.first().executable)
        val p = snap.extensionPlugins.first()
        assertTrue(p.isWorkerIsolated)
        assertEquals("原生页", p.surfaces.first().title)
        assertEquals("chat.topbar", p.uiSlots.first().slot)
        assertEquals("custom-wire", p.translators.first().id)

        val sdui = HostPluginSduiPayload.fromJson(
            JSONObject()
                .put("pluginId", "local.worker.suite")
                .put("surfaceId", "worker-home")
                .put("title", "原生页")
                .put("nodes", JSONArray().put(JSONObject().put("type", "metric").put("label", "模式").put("value", "Worker"))),
        )
        assertFalse(sdui.hidden)
        assertEquals(1, sdui.nodes.size)
        assertEquals("metric", sdui.nodes.first().optString("type"))
    }

    @Test
    fun `round-trips CustomProvider plugin wire protocol accurately`() {
        val original = CustomProvider(
            id = "p_plug_1",
            name = "插件协议上游",
            protocol = ProviderProtocol.OPENAI_CHAT,
            baseUrl = "https://example.com/api",
            authMode = ProviderAuthMode.BEARER,
            customProtocolId = "plugin:custom-wire",
            models = listOf("custom-m1"),
        )
        assertEquals("plugin:custom-wire", original.wireProtocolId)
        assertEquals("插件:custom-wire", original.displayTypeBadge())
        assertEquals("插件:custom-wire", original.supportedProtocolsSummary())

        val json = original.toGatewayJson("sk-test")
        assertEquals("plugin:custom-wire", json.getString("protocol"))
        assertEquals("plugin:custom-wire", json.getString("customProtocolId"))

        val parsed = CustomProvider.fromJson(json, hasApiKey = true)
        assertEquals("plugin:custom-wire", parsed.customProtocolId)
        assertEquals("plugin:custom-wire", parsed.wireProtocolId)
        assertEquals("插件:custom-wire", parsed.displayTypeBadge())
    }

    @Test
    fun `parses global overlay placement metadata and route matching`() {
        val slot = HostPluginUiSlot.fromJson(
            JSONObject()
                .put("pluginId", "local.overlay.kit")
                .put("id", "orb-1")
                .put("slot", "global.overlay")
                .put("label", "悬浮助手")
                .put("badge", "ORB")
                .put("surfaceId", "dash")
                .put("routes", JSONArray().put("home").put("chat"))
                .put("alignment", "bottomStart")
                .put("offsetX", 16)
                .put("offsetY", -110)
                .put("draggable", true)
                .put("displayMode", "fab"),
        )
        assertEquals("global.overlay", slot.slot)
        assertEquals("bottomStart", slot.alignment)
        assertEquals(16, slot.offsetX)
        assertEquals(-110, slot.offsetY)
        assertTrue(slot.draggable)
        assertEquals("fab", slot.displayMode)
        assertTrue(slot.matchesRoute("home"))
        assertTrue(slot.matchesRoute("chat"))
        assertFalse(slot.matchesRoute("files"))

        val wildcardSlot = slot.copy(routes = emptyList())
        assertTrue(wildcardSlot.matchesRoute("files"))
        assertTrue(wildcardSlot.matchesRoute("terminal"))
    }
}
