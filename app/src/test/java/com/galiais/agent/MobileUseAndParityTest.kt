package com.galiais.agent

import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import com.galiais.agent.engine.RUNTIME_MODE_ROOT
import com.galiais.agent.engine.mobile.MOBILE_BACKEND_ACCESSIBILITY
import com.galiais.agent.engine.mobile.MOBILE_BACKEND_AUTO
import com.galiais.agent.engine.mobile.MOBILE_BACKEND_ROOT
import com.galiais.agent.engine.mobile.MOBILE_BACKEND_SHIZUKU
import com.galiais.agent.engine.mobile.MOBILE_SAFETY_STRICT
import com.galiais.agent.engine.mobile.MobileBackendKind
import com.galiais.agent.engine.mobile.MobileUseConfig
import com.galiais.agent.engine.mobile.MobileUseLogic
import com.galiais.agent.engine.mobile.UiTreeNode
import com.galiais.agent.ui.ToolCallKind
import com.galiais.agent.ui.describeToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileUseAndParityTest {

    @Test
    fun resolveBackendRoutesCorrectlyAcrossChrootAndProot() {
        // Root chroot 模式下优先 Root 原生驱动，无 Root 时平滑回退无障碍
        assertEquals(
            MobileBackendKind.ROOT,
            MobileUseLogic.resolveBackend(MOBILE_BACKEND_AUTO, RUNTIME_MODE_ROOT, rootAvailable = true, accessibilityReady = true),
        )
        assertEquals(
            MobileBackendKind.ACCESSIBILITY,
            MobileUseLogic.resolveBackend(MOBILE_BACKEND_AUTO, RUNTIME_MODE_ROOT, rootAvailable = false, accessibilityReady = true),
        )
        // 免 Root PRoot 模式下优先无障碍服务，未开无障碍但恰好有 Root 时仍可回退 Root
        assertEquals(
            MobileBackendKind.ACCESSIBILITY,
            MobileUseLogic.resolveBackend(MOBILE_BACKEND_AUTO, RUNTIME_MODE_PROOT, rootAvailable = true, accessibilityReady = true),
        )
        assertEquals(
            MobileBackendKind.ROOT,
            MobileUseLogic.resolveBackend(MOBILE_BACKEND_AUTO, RUNTIME_MODE_PROOT, rootAvailable = true, accessibilityReady = false),
        )
        assertEquals(
            MobileBackendKind.NONE,
            MobileUseLogic.resolveBackend(MOBILE_BACKEND_AUTO, RUNTIME_MODE_PROOT, rootAvailable = false, accessibilityReady = false),
        )
        // 显式强制后端选择与 Shizuku 三级路由
        assertEquals(
            MobileBackendKind.SHIZUKU,
            MobileUseLogic.resolveBackend(MOBILE_BACKEND_AUTO, RUNTIME_MODE_PROOT, rootAvailable = false, accessibilityReady = true, shizukuReady = true),
        )
        assertEquals(
            MobileBackendKind.ROOT,
            MobileUseLogic.resolveBackend(MOBILE_BACKEND_AUTO, RUNTIME_MODE_ROOT, rootAvailable = true, accessibilityReady = true, shizukuReady = true),
        )
        assertEquals(
            MobileBackendKind.SHIZUKU,
            MobileUseLogic.resolveBackend(MOBILE_BACKEND_SHIZUKU, RUNTIME_MODE_PROOT, rootAvailable = true, accessibilityReady = true, shizukuReady = true),
        )
        assertEquals(
            MobileBackendKind.NONE,
            MobileUseLogic.resolveBackend(MOBILE_BACKEND_ROOT, RUNTIME_MODE_ROOT, rootAvailable = false, accessibilityReady = true),
        )
        assertEquals(
            MobileBackendKind.ACCESSIBILITY,
            MobileUseLogic.resolveBackend(MOBILE_BACKEND_ACCESSIBILITY, RUNTIME_MODE_ROOT, rootAvailable = true, accessibilityReady = true),
        )
    }

    @Test
    fun parseUiAutomatorXmlAndFindMatchingNodes() {
        val sampleXml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy rotation="0">
              <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.demo.app" content-desc="" clickable="false" enabled="true" focused="false" scrollable="false" bounds="[0,0][1080,2400]">
                <node index="0" text="搜索 &amp; 发现" resource-id="com.demo.app:id/search_box" class="android.widget.EditText" package="com.demo.app" content-desc="搜索栏" clickable="true" enabled="true" focused="true" scrollable="false" bounds="[40,120][1040,220]" />
                <node index="1" text="设置" resource-id="com.demo.app:id/tab_settings" class="android.widget.TextView" package="com.demo.app" content-desc="" clickable="true" enabled="true" focused="false" scrollable="false" bounds="[800,2200][1040,2360]" />
              </node>
            </hierarchy>
        """.trimIndent()
        val nodes = MobileUseLogic.parseUiAutomatorXml(sampleXml)
        assertEquals(2, nodes.size)
        assertEquals("搜索 & 发现", nodes[0].text)
        assertEquals("search_box", nodes[0].resourceId)
        assertTrue(nodes[0].editable)
        assertEquals(540, nodes[0].centerX)
        assertEquals(170, nodes[0].centerY)

        val settingsHits = MobileUseLogic.findMatchingNodes(nodes, text = "设置")
        assertEquals(1, settingsHits.size)
        assertEquals(920, settingsHits[0].centerX)
        assertEquals(2280, settingsHits[0].centerY)

        val byRes = MobileUseLogic.findMatchingNodes(nodes, resourceId = "search_box")
        assertEquals(1, byRes.size)
        assertEquals("搜索栏", byRes[0].contentDesc)
    }

    @Test
    fun sensitivePackageGuardProtectsPaymentsAndInstallers() {
        assertTrue(MobileUseLogic.isSensitivePackage("com.eg.android.alipaygphone"))
        assertTrue(MobileUseLogic.isSensitivePackage("com.android.packageinstaller"))
        assertTrue(MobileUseLogic.isSensitivePackage("com.google.android.permissioncontroller"))
        assertTrue(MobileUseLogic.isSensitivePackage("me.weishu.kernelsu"))
        assertTrue(MobileUseLogic.isSensitivePackage("com.my.custom.vault", listOf("com.my.custom.vault")))
        assertFalse(MobileUseLogic.isSensitivePackage("com.galiais.agent"))
        assertFalse(MobileUseLogic.isSensitivePackage("com.android.settings"))
    }

    @Test
    fun swipeCoordsAndKeyCodeMapping() {
        val up = MobileUseLogic.computeSwipeCoords("up", 1080, 2400, 0.5f)
        assertEquals(540, up[0])
        assertEquals(540, up[2])
        assertTrue("向上滑动起点 Y 应大于终点 Y", up[1] > up[3])

        assertEquals(4, MobileUseLogic.toAndroidKeyCode("BACK"))
        assertEquals(3, MobileUseLogic.toAndroidKeyCode("KEYCODE_HOME"))
        assertEquals(279, MobileUseLogic.toAndroidKeyCode("paste"))
        assertEquals(66, MobileUseLogic.toAndroidKeyCode("enter"))
    }

    @Test
    fun mobileUseConfigRoundTripsCleanly() {
        val cfg = MobileUseConfig(
            enabled = true,
            preferredBackend = MOBILE_BACKEND_ACCESSIBILITY,
            safetyMode = MOBILE_SAFETY_STRICT,
            showOverlayPill = false,
            screenshotMaxWidth = 1080,
            screenshotQuality = 85,
            customSensitivePackages = listOf("com.example.secret", "  COM.EXAMPLE.SECRET "),
        ).validated()
        assertEquals(listOf("com.example.secret"), cfg.customSensitivePackages)
        val restored = MobileUseConfig.fromJson(cfg.toJson().toString())
        assertEquals(cfg, restored)
    }

    @Test
    fun timelineDescribesMobileUseToolsNativelyNeverAsRawMcp() {
        val cases = listOf(
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_status", "{}", "手机状态"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_screenshot", "{}", "手机截屏"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_ui_tree", """{"query":"设置"}""", "读取界面"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_tap", """{"text":"确认"}""", "点击屏幕"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_tap", """{"x":100,"y":200,"longPress":true}""", "长按屏幕"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_swipe", """{"direction":"up"}""", "滑动屏幕"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_type", """{"text":"你好世界"}""", "输入文本"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_key", """{"key":"back"}""", "系统按键"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_app", """{"operation":"launch","name":"设置"}""", "手机应用"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_clipboard", """{"operation":"read"}""", "剪贴板"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_device", """{"operation":"telemetry"}""", "设备控制"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_intent", """{"operation":"open_url","url":"https://example.com"}""", "跨应用直达"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_notification", """{"operation":"otp"}""", "手机通知"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_files", """{"operation":"list","hostPath":"/sdcard"}""", "手机文件"),
            Triple("mcp__solbraid_plugin_ctl__solbraid_mobile_host", """{"operation":"logcat"}""", "宿主底层"),
        )
        for ((toolName, input, expectedBadge) in cases) {
            val meta = describeToolCall(toolName, input)
            assertEquals("工具 $toolName 应渲染为原生卡片而非 MCP", expectedBadge, meta.badge)
            assertTrue(meta.kind == ToolCallKind.MOBILE || meta.kind == ToolCallKind.IMAGE || meta.kind == ToolCallKind.TERMINAL)
        }
    }

    @Test
    fun otpExtractionSomFilterPinchAndHostCommandGuard() {
        assertEquals("482910", MobileUseLogic.extractOtpCode("【测试平台】您的登录验证码：482910，5分钟内有效"))
        assertEquals("7392", MobileUseLogic.extractOtpCode("Your verification code is 7392."))
        assertNull(MobileUseLogic.extractOtpCode("今天下午 1430 开会讨论架构方案"))

        val somNodes = MobileUseLogic.filterInteractiveNodesForSoM(
            listOf(
                UiTreeNode(index = 0, text = "确认", left = 100, top = 200, right = 300, bottom = 280, clickable = true),
                UiTreeNode(index = 1, text = "确认", left = 102, top = 202, right = 302, bottom = 282, clickable = true),
                UiTreeNode(index = 2, text = "取消", left = 500, top = 200, right = 700, bottom = 280, clickable = true),
            ),
        )
        assertEquals(2, somNodes.size)

        val pinch = MobileUseLogic.computePinchCoords(540, 1200, 200, 600, 1080, 2400)
        assertEquals(2, pinch.size)
        assertTrue(pinch[0][2] < pinch[0][0])
        assertTrue(pinch[1][2] > pinch[1][0])

        assertNotNull(MobileUseLogic.checkDangerousHostCommand("rm -rf /"))
        assertNotNull(MobileUseLogic.checkDangerousHostCommand("dd if=/dev/zero of=/dev/block/sda"))
        assertNotNull(MobileUseLogic.checkDangerousHostCommand("reboot bootloader"))
        assertNull(MobileUseLogic.checkDangerousHostCommand("dumpsys battery"))
    }
}
