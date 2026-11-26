package com.galiais.agent

import com.galiais.agent.engine.ssh.ModifierKeyState
import com.galiais.agent.engine.ssh.PtyStreamCodec
import com.galiais.agent.engine.ssh.StickyModifierManager
import com.galiais.agent.engine.ssh.TerminalSnippet
import com.galiais.agent.engine.ssh.parseQuickConnectSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class NativeSshTerminalTest {

    @Test
    fun ptyStreamCodecRoundTripsDataAndResizeFrames() {
        val baos = ByteArrayOutputStream()
        baos.write("OK\n".toByteArray(Charsets.UTF_8))
        val out = DataOutputStream(baos)
        val payload = "echo hello 世界\r\n".toByteArray(Charsets.UTF_8)
        PtyStreamCodec.writeDataFrame(out, payload)
        PtyStreamCodec.writeResizeFrame(out, 118, 34)

        val input = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals("OK", PtyStreamCodec.readHandshakeLine(input))
        val f1 = PtyStreamCodec.readFrame(input)!!
        assertEquals(PtyStreamCodec.FRAME_DATA, f1.first)
        assertArrayEquals(payload, f1.second)

        val f2 = PtyStreamCodec.readFrame(input)!!
        assertEquals(PtyStreamCodec.FRAME_RESIZE, f2.first)
        assertEquals(4, f2.second.size)
        val cols = ((f2.second[0].toInt() and 0xFF) shl 8) or (f2.second[1].toInt() and 0xFF)
        val rows = ((f2.second[2].toInt() and 0xFF) shl 8) or (f2.second[3].toInt() and 0xFF)
        assertEquals(118, cols)
        assertEquals(34, rows)

        assertNull(PtyStreamCodec.readFrame(input))
    }

    @Test
    fun stickyModifierManagerSupportsOneShotAndLockedStates() {
        val mods = StickyModifierManager()
        assertFalse(mods.isCtrlActive())
        assertEquals(ModifierKeyState.OFF, mods.ctrlState)

        // First tap -> TRANSIENT, cleared after one key
        mods.tapCtrl()
        assertTrue(mods.isCtrlActive())
        assertEquals(ModifierKeyState.TRANSIENT, mods.ctrlState)
        mods.clearTransients()
        assertFalse(mods.isCtrlActive())

        // Double tap -> LOCKED, survives clearTransients
        mods.tapCtrl()
        mods.tapCtrl()
        assertEquals(ModifierKeyState.LOCKED, mods.ctrlState)
        mods.tapAlt()
        assertEquals(ModifierKeyState.TRANSIENT, mods.altState)
        mods.clearTransients()
        assertTrue(mods.isCtrlActive())
        assertEquals(ModifierKeyState.LOCKED, mods.ctrlState)
        assertFalse(mods.isAltActive())

        // Third tap -> OFF
        mods.tapCtrl()
        assertFalse(mods.isCtrlActive())
    }

    @Test
    fun parseQuickConnectSpecHandlesStandardSshAndIpv6Formats() {
        val h1 = parseQuickConnectSpec("alice@10.0.0.5:2222")
        assertEquals("alice", h1.user)
        assertEquals("10.0.0.5", h1.host)
        assertEquals(2222, h1.port)

        val h2 = parseQuickConnectSpec("ssh -p 2200 -i ~/.ssh/prod bob@prod.example.com")
        assertEquals("bob", h2.user)
        assertEquals("prod.example.com", h2.host)
        assertEquals(2200, h2.port)

        val h3 = parseQuickConnectSpec("root@[2001:db8::1]:2222")
        assertEquals("root", h3.user)
        assertEquals("2001:db8::1", h3.host)
        assertEquals(2222, h3.port)

        val h4 = parseQuickConnectSpec("dev.local")
        assertEquals("root", h4.user)
        assertEquals("dev.local", h4.host)
        assertEquals(22, h4.port)

        try {
            parseQuickConnectSpec("alice@bad:99999")
            fail("Expected invalid port rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun terminalSnippetsDefaultsAndJsonParse() {
        val defaults = TerminalSnippet.defaults()
        assertTrue(defaults.size >= 6)
        val parsed = TerminalSnippet.parseList(null)
        assertEquals(defaults.size, parsed.size)
        val single = TerminalSnippet.fromJson(defaults.first().toJson())
        assertEquals(defaults.first(), single)
    }

    @Test
    fun jschEd25519KeyGenerationWorksWithBouncyCastle() {
        val jsch = com.jcraft.jsch.JSch()
        val kp = com.jcraft.jsch.KeyPair.genKeyPair(jsch, com.jcraft.jsch.KeyPair.ED25519, 256)
        try {
            val privOut = ByteArrayOutputStream()
            val pubOut = ByteArrayOutputStream()
            kp.writeOpenSSHv1PrivateKey(privOut, null)
            kp.writePublicKey(pubOut, "solbraid-test")
            val privText = privOut.toString("UTF-8")
            val pubText = pubOut.toString("UTF-8")
            assertTrue("Expected OpenSSH v1 private key", privText.contains("BEGIN OPENSSH PRIVATE KEY"))
            assertTrue("Expected ssh-ed25519 public key, got: $pubText", pubText.startsWith("ssh-ed25519 "))
            val reloaded = com.jcraft.jsch.KeyPair.load(jsch, privOut.toByteArray(), pubOut.toByteArray())
            try {
                assertEquals(com.jcraft.jsch.KeyPair.ED25519, reloaded.keyType)
            } finally {
                reloaded.dispose()
            }
        } finally {
            kp.dispose()
        }
    }
}
