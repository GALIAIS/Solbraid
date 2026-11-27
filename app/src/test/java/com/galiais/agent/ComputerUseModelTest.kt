package com.galiais.agent

import com.galiais.agent.engine.PcAuditEntry
import com.galiais.agent.engine.PcBuildStatus
import com.galiais.agent.engine.PcDiscoveredHost
import com.galiais.agent.engine.PcHostDraft
import com.galiais.agent.engine.PcHostInfo
import com.galiais.agent.engine.PcProcessItem
import com.galiais.agent.engine.PcRemoteFileEntry
import com.galiais.agent.engine.PcSystemInfo
import com.galiais.agent.engine.PcWindowItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerUseModelTest {

    @Test
    fun `parses pc-agent pairing JSON banner accurately`() {
        val raw = """
            手机端配对参数:
            {"action":"add","id":"galiais-win","name":"GALIAIS","transport":"usb","address":"127.0.0.1","port":29840,"mac":"AA:BB:CC:DD:EE:FF","permissionLevel":"operate","token":"secret-token-1234"}
        """.trimIndent()
        val draft = PcHostDraft.parsePairingJson(raw)
        assertEquals("galiais-win", draft.id)
        assertEquals("GALIAIS", draft.name)
        assertEquals("usb", draft.transport)
        assertEquals("127.0.0.1", draft.address)
        assertEquals(29840, draft.port)
        assertEquals("AA:BB:CC:DD:EE:FF", draft.mac)
        assertEquals("operate", draft.permissionLevel)
        assertEquals("secret-token-1234", draft.token)
        val rpc = draft.toRpcParams("add")
        assertEquals("add", rpc.getString("action"))
        assertEquals("secret-token-1234", rpc.getString("token"))
    }

    @Test
    fun `parses PcHostInfo, PcWindowItem and PcAuditEntry cleanly`() {
        val host = PcHostInfo.fromJson(
            JSONObject()
                .put("id", "office-mac")
                .put("name", "公司 Mac")
                .put("os", "darwin")
                .put("transport", "relay")
                .put("relayUrl", "https://relay.example.com")
                .put("pairId", "mac-01")
                .put("permissionLevel", "admin")
                .put("hasToken", true)
                .put("isDefault", true),
        )
        assertEquals("macOS", host.osLabel)
        assertEquals("公网中继", host.transportLabel)
        assertEquals("完全管理", host.permissionLabel)
        assertTrue(host.endpointSummary.contains("relay.example.com"))
        assertTrue(host.isDefault)

        val win = PcWindowItem.fromJson(
            JSONObject()
                .put("hwnd", "12345")
                .put("title", "Solbraid IDE")
                .put("process", "Code")
                .put("pid", 888)
                .put("active", true)
                .put("width", 1920)
                .put("height", 1080),
        )
        assertEquals("Solbraid IDE", win.title)
        assertTrue(win.active)

        val blocked = PcAuditEntry.fromJson(
            JSONObject()
                .put("ts", 1795700000000L)
                .put("action", "system")
                .put("hostId", "office-mac")
                .put("blocked", "high_risk_denied")
                .put("reason", "用户拒绝"),
        )
        assertFalse(blocked.ok)
        assertEquals("high_risk_denied", blocked.blocked)
    }

    @Test
    fun `parses LAN discovery, build matrix, remote files, processes and relaySecret`() {
        val relayDraft = PcHostDraft.parsePairingJson(
            """{"transport":"relay","relayUrl":"https://r.example.com","pairId":"p1","relaySecret":"rsec-99","tls":true,"tlsFingerprint":"PC_ONLY","relayTlsFingerprint":"ABCD","token":"tok-1"}""",
        )
        assertEquals("relay", relayDraft.transport)
        assertEquals("rsec-99", relayDraft.relaySecret)
        assertEquals("ABCD", relayDraft.tlsFingerprint)
        assertEquals("rsec-99", relayDraft.toRpcParams().getString("relaySecret"))
        val relayWithoutRelayFp = PcHostDraft.parsePairingJson(
            """{"transport":"relay","relayUrl":"https://r.example.com","pairId":"p1","relaySecret":"rsec-99","tls":true,"tlsFingerprint":"PC_ONLY","token":"tok-1"}""",
        )
        assertEquals("", relayWithoutRelayFp.tlsFingerprint)

        val disc = PcDiscoveredHost.fromJson(
            JSONObject()
                .put("address", "192.168.1.55")
                .put("port", 29840)
                .put("name", "Studio-Win")
                .put("os", "windows")
                .put("arch", "amd64")
                .put("tls", true)
                .put("tlsFingerprint", "FF00")
                .put("engine", "native-go"),
        )
        val fromDisc = disc.toDraft("my-tok")
        assertEquals("lan", fromDisc.transport)
        assertEquals("192.168.1.55", fromDisc.address)
        assertEquals("FF00", fromDisc.tlsFingerprint)
        assertEquals("my-tok", fromDisc.token)

        val bs = PcBuildStatus.fromJson(
            JSONObject()
                .put("toolchain", JSONObject().put("installed", true).put("goVersion", "go1.26.0 linux/arm64").put("goBin", "/home/agent/.galiais/toolchains/go/bin/go"))
                .put("sourceReady", true)
                .put("sourceHash", "abc123def456")
                .put("targets", JSONArray().put(
                    JSONObject().put("os", "windows").put("arch", "amd64").put("label", "Windows x64")
                        .put("filename", "solbraid-desktop-windows-amd64.exe").put("built", true).put("size", 7_600_000L).put("upToDate", true),
                )),
        )
        assertTrue(bs.toolchainInstalled)
        assertEquals(1, bs.targets.size)
        assertTrue(bs.targets[0].upToDate)

        val rf = PcRemoteFileEntry.fromJson(JSONObject().put("name", "report.pdf").put("path", "/tmp/report.pdf").put("isDir", false).put("size", 2048L))
        assertEquals("report.pdf", rf.name)
        val pr = PcProcessItem.fromJson(JSONObject().put("pid", 1024).put("name", "chrome.exe").put("cpu", 3.5))
        assertEquals(1024, pr.pid)
        val si = PcSystemInfo.fromJson(JSONObject().put("hostname", "GALIAIS").put("platform", "windows").put("arch", "amd64").put("cpus", 16).put("engine", "native-go"))
        assertEquals(16, si.cpus)
        assertEquals("native-go", si.engine)
    }
}
