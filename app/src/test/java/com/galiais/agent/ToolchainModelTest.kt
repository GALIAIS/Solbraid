package com.galiais.agent

import com.galiais.agent.engine.AptPackageItem
import com.galiais.agent.engine.CacheStats
import com.galiais.agent.engine.ProjectScanResult
import com.galiais.agent.engine.ToolchainCmdResult
import com.galiais.agent.engine.ToolchainSnapshot
import com.galiais.agent.engine.ToolchainVersionOption
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainModelTest {

    @Test
    fun `parses ToolchainSnapshot, ProjectScanResult, AptPackageItem and CacheStats cleanly`() {
        val snap = ToolchainSnapshot.fromJson(
            JSONObject()
                .put("platform", "linux")
                .put("arch", "arm64")
                .put("aptWritable", true)
                .put("aptViaHostRoot", true)
                .put("mirrorPreset", "cn")
                .put("mirrors", JSONObject().put("npmRegistry", "https://registry.npmmirror.com").put("goProxy", "https://goproxy.cn,direct"))
                .put(
                    "toolchains",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "zig")
                            .put("name", "Zig (含 C/C++ 交叉编译器)")
                            .put("category", "compiler")
                            .put("rootless", true)
                            .put("multiVersion", true)
                            .put("installed", true)
                            .put("version", "0.14.0")
                            .put("activeVersion", "0.14.0")
                            .put("installedVersions", JSONArray().put("0.14.0").put("0.13.0"))
                            .put("managed", true)
                            .put("diskBytes", 50_000_000L)
                            .put("canUninstall", true),
                    ),
                ),
        )
        assertEquals("cn", snap.mirrorPreset)
        assertTrue(snap.aptWritable)
        assertEquals("https://goproxy.cn,direct", snap.goProxy)
        assertEquals(1, snap.toolchains.size)
        assertTrue(snap.toolchains[0].rootless)
        assertEquals("0.14.0", snap.toolchains[0].activeVersion)
        assertEquals(listOf("0.14.0", "0.13.0"), snap.toolchains[0].installedVersions)
        assertTrue(snap.toolchains[0].canUninstall)

        val verOpt = ToolchainVersionOption.fromJson(
            JSONObject()
                .put("version", "10.0.404")
                .put("channel", "LTS (.NET 10.0)")
                .put("sha512", "cfa248bb1377606e868602fb5b4d66fa")
                .put("checksumUrl", "https://example.invalid/checksum.sha512")
                .put("buildToolsVersion", "36.0.0")
                .put("sizeBytes", 215_000_000L),
        )
        assertEquals("10.0.404", verOpt.version)
        assertEquals("LTS (.NET 10.0)", verOpt.channel)
        assertEquals("cfa248bb1377606e868602fb5b4d66fa", verOpt.sha512)
        assertEquals("36.0.0", verOpt.buildToolsVersion)

        val scan = ProjectScanResult.fromJson(
            JSONObject()
                .put("cwd", "/home/agent/workspace/demo")
                .put(
                    "ecosystems",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "go:.")
                            .put("ecosystem", "go")
                            .put("label", "Go")
                            .put("manager", "go")
                            .put("cwd", "/home/agent/workspace/demo")
                            .put("manifest", "go.mod")
                            .put("requiredToolchain", "go")
                            .put("depCount", 1)
                            .put("deps", JSONArray().put(JSONObject().put("name", "golang.org/x/sys").put("version", "v0.30.0").put("dev", false))),
                    ),
                )
                .put(
                    "compileTargets",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "go:.:build")
                            .put("label", "Go 编译")
                            .put("ecosystem", "go")
                            .put("cwd", "/home/agent/workspace/demo")
                            .put("command", "go")
                            .put("args", JSONArray().put("build").put("."))
                            .put("requiredToolchain", "go")
                            .put("crossCompile", true),
                    ),
                ),
        )
        assertEquals(1, scan.ecosystems.size)
        assertEquals("golang.org/x/sys", scan.ecosystems[0].deps[0].name)
        assertTrue(scan.compileTargets[0].crossCompile)

        val apt = AptPackageItem.fromJson(
            JSONObject().put("name", "cmake").put("version", "3.28.3").put("sizeKB", 18400).put("summary", "cross-platform make").put("installed", true),
        )
        assertEquals("cmake", apt.name)
        assertTrue(apt.installed)

        val cs = CacheStats.fromJson(
            JSONObject().put("totalBytes", 2_097_152L).put("entries", JSONArray().put(JSONObject().put("id", "npm").put("label", "npm 缓存").put("bytes", 2_097_152L))),
        )
        assertEquals(2_097_152L, cs.totalBytes)

        val cmdRes = ToolchainCmdResult.fromJson(
            JSONObject().put("ok", true).put("exitCode", 0).put("ms", 420L).put("command", "go build .").put("artifactPath", "/tmp/demo").put("artifactSize", 2_500_000L),
        )
        assertTrue(cmdRes.ok)
        assertEquals("/tmp/demo", cmdRes.artifactPath)
    }
}
