package com.galiais.agent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.BuildConfig

internal const val SOLBRAID_REPO_URL = "https://github.com/GALIAIS/Solbraid"
private const val SOLBRAID_LICENSE_URL = "https://github.com/GALIAIS/Solbraid/blob/main/LICENSE"

internal fun openExternalBrowserUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private data class LegalClause(val title: String, val body: String)

private val USER_AGREEMENT_CLAUSES = listOf(
    LegalClause(
        title = "一、协议适用与开源性质",
        body = "Solbraid 是一款自由开源的 Android 移动开发与多智能体编码客户端，依据 GNU Affero General Public License v3.0（AGPL-3.0）协议开放源代码。您在安装、启动或使用本应用前，应完整阅读并理解本协议全部条款；继续使用即视为同意接受本协议约束。",
    ),
    LegalClause(
        title = "二、本地运行与第三方服务",
        body = "Solbraid 的本地 Linux 运行环境、纯 Kotlin 原生终端、文件管理与 SSH/SFTP 工具均在您的自有设备上执行。应用本身不内置商业模型额度；您自行配置或登录的第三方模型服务商（如 OpenAI、Anthropic、Google、OpenRouter 或自托管接口）及远端服务器均受对应服务商的条款与计费规则约束，相关费用与合规责任由您自行承担。",
    ),
    LegalClause(
        title = "三、系统权限与高风险操作责任",
        body = "当您主动开启 Root 模式、Shizuku、无障碍手机操控（Mobile-Use）、通知监听、SSH 远端命令执行、系统级文件读写或本机 APK 自构建功能时，应用及您授权的智能体可能对设备状态、系统文件或远端主机产生实质性变更。请务必在执行前审阅操作内容与审批提示；因误操作、恶意指令或未审慎授权造成的设备异常、服务中断或数据丢失，由操作者自行承担责任。",
    ),
    LegalClause(
        title = "四、插件、MCP 与社区扩展",
        body = "Solbraid 支持加载本地插件、MCP 服务、技能、子代理与自定义脚本。除项目核心内置组件外，第三方或用户自行引入的扩展不构成官方背书；请在启用前核验来源与能力权限声明，切勿运行不受信任的脚本或插件。",
    ),
    LegalClause(
        title = "五、数据存储与隐私保护",
        body = "Solbraid 遵循本地优先原则：您的会话历史、工作区文件、环境变量、SSH 主机配置、密钥及 API 凭据均仅保存在本机，敏感凭据通过 Android Keystore 与 AES-GCM 加密存储。Solbraid 不设中心化遥测或广告服务器，不会主动收集、上传或出售您的代码与个人隐私数据；仅当您主动发起 AI 请求、SSH 连接或访问外部仓库时，数据才会按您的配置直连对应目标节点。",
    ),
    LegalClause(
        title = "六、免责声明与条款更新",
        body = "依据开源协议原则，本软件按“现状（AS IS）”提供，不附带任何明示或默示的适销性、特定用途适用性或不侵权担保。AI 智能体生成的代码、命令或分析结果可能存在错误，您在生产环境采用前应自行审查与测试。本协议可能随版本迭代更新并在本页面公示。",
    ),
)

/** 用户协议子页面。 */
@Composable
fun UserAgreementScreen(snackbar: SnackbarHostState? = null, onBack: () -> Unit) {
    SubPageScaffold(title = "用户协议", onBack = onBack, snackbar = snackbar) {
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(GaliaisIcons.ShieldCheck, contentDescription = null, tint = G.accent, modifier = Modifier.size(18.dp))
                    Text("Solbraid 用户服务协议", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = G.ink)
                }
                Text(
                    "适用版本：v${BuildConfig.VERSION_NAME} · 更新日期：2026 年 11 月",
                    style = MaterialTheme.typography.labelSmall,
                    color = G.inkMuted,
                )
            }
        }

        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                USER_AGREEMENT_CLAUSES.forEachIndexed { idx, clause ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(clause.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink)
                        Text(clause.body, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, lineHeight = 20.sp)
                    }
                    if (idx < USER_AGREEMENT_CLAUSES.lastIndex) {
                        Hairline()
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private data class OpenSourceEntry(
    val name: String,
    val license: String,
    val copyright: String,
    val url: String,
    val notice: String,
)

private val APACHE_2_SHORT = """
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at:

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
""".trimIndent()

private val BSD_NOTICE = """
Redistribution and use in source and binary forms, with or without modification, are permitted provided that the copyright notice, list of conditions, and disclaimer are retained. THIS SOFTWARE IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND.
""".trimIndent()

private val MIT_NOTICE = """
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files, to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, subject to inclusion of the copyright and permission notice.
""".trimIndent()

private val OPEN_SOURCE_ENTRIES = listOf(
    OpenSourceEntry(
        name = "Jetpack Compose / AndroidX / Material 3",
        license = "Apache-2.0",
        copyright = "Copyright (C) The Android Open Source Project",
        url = "https://developer.android.com/jetpack",
        notice = APACHE_2_SHORT,
    ),
    OpenSourceEntry(
        name = "Kotlin & kotlinx.coroutines",
        license = "Apache-2.0",
        copyright = "Copyright (C) JetBrains s.r.o. and Kotlin Programming Language contributors",
        url = "https://github.com/JetBrains/kotlin",
        notice = APACHE_2_SHORT,
    ),
    OpenSourceEntry(
        name = "CommonMark Java",
        license = "BSD-2-Clause",
        copyright = "Copyright (C) Atlassian Pty Ltd and CommonMark Java contributors",
        url = "https://github.com/commonmark/commonmark-java",
        notice = BSD_NOTICE,
    ),
    OpenSourceEntry(
        name = "JSch (mwiede fork)",
        license = "BSD-3-Clause",
        copyright = "Copyright (C) ymnk, JCraft, Inc. & Matthias Wiedemann",
        url = "https://github.com/mwiede/jsch",
        notice = BSD_NOTICE,
    ),
    OpenSourceEntry(
        name = "Bouncy Castle Crypto APIs",
        license = "MIT-style",
        copyright = "Copyright (C) 2000 - 2026 The Legion of the Bouncy Castle Inc.",
        url = "https://www.bouncycastle.org/license.html",
        notice = MIT_NOTICE,
    ),
    OpenSourceEntry(
        name = "Shizuku API & Provider",
        license = "Apache-2.0",
        copyright = "Copyright (C) RikkaW / Shizuku contributors",
        url = "https://github.com/RikkaApps/Shizuku-API",
        notice = APACHE_2_SHORT,
    ),
    OpenSourceEntry(
        name = "Cascadia Code / CascadiaMonoNF",
        license = "OFL-1.1",
        copyright = "Copyright (C) Microsoft Corporation & Nerd Fonts contributors",
        url = "https://github.com/microsoft/cascadia-code",
        notice = "Licensed under the SIL Open Font License, Version 1.1 (https://openfontlicense.org). The Font Software may be freely embedded, modified, and redistributed under OFL-1.1 terms.",
    ),
    OpenSourceEntry(
        name = "PRoot & talloc",
        license = "GPL-2.0+ / LGPL-3.0+",
        copyright = "Copyright (C) STMicroelectronics / Termux PRoot & Samba talloc contributors",
        url = "https://github.com/termux/proot",
        notice = "PRoot is distributed under the GNU General Public License v2.0 or later; talloc is distributed under the GNU Lesser General Public License v3.0 or later.",
    ),
    OpenSourceEntry(
        name = "Node.js & Ubuntu Base Rootfs",
        license = "MIT / Open Source",
        copyright = "Copyright (C) OpenJS Foundation, Node.js contributors & Canonical / Ubuntu community",
        url = "https://nodejs.org",
        notice = "Node.js is licensed under the MIT License; Ubuntu 24.04 base userland packages are governed by their respective open-source licenses included in /usr/share/doc/*/copyright.",
    ),
    OpenSourceEntry(
        name = "ripgrep (rg)",
        license = "MIT / Unlicense",
        copyright = "Copyright (C) Andrew Gallant (BurntSushi)",
        url = "https://github.com/BurntSushi/ripgrep",
        notice = MIT_NOTICE,
    ),
    OpenSourceEntry(
        name = "Go Standard Library & golang.org/x/sys",
        license = "BSD-3-Clause",
        copyright = "Copyright (C) The Go Authors",
        url = "https://go.dev",
        notice = BSD_NOTICE,
    ),
    OpenSourceEntry(
        name = "android-shmem (libandroid-shmem)",
        license = "Apache-2.0",
        copyright = "Copyright (C) Termux & pelya contributors",
        url = "https://github.com/termux/libandroid-shmem",
        notice = APACHE_2_SHORT,
    ),
    OpenSourceEntry(
        name = "JUnit 4 & JSON-java (Test Dependencies)",
        license = "EPL-1.0 / Public Domain",
        copyright = "Copyright (C) JUnit Team & JSON.org contributors",
        url = "https://github.com/junit-team/junit4",
        notice = "Used exclusively for unit testing: JUnit 4 under Eclipse Public License 1.0; org.json under the JSON / Public Domain license.",
    ),
)

/** 开放源代码许可子页面。 */
@Composable
fun OpenSourceLicensesScreen(snackbar: SnackbarHostState? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    var expandedNames by remember { mutableStateOf<Set<String>>(emptySet()) }

    SubPageScaffold(title = "开放源代码许可", onBack = onBack, snackbar = snackbar) {
        SectionLabel("本项目开源许可")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Solbraid", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink, modifier = Modifier.weight(1f))
                    TagPill("AGPL-3.0", fg = G.accent, bg = G.accentContainer)
                }
                Text(
                    "Copyright (C) 2026 GALIAIS / Solbraid Contributors",
                    fontFamily = Mono,
                    fontSize = 11.5.sp,
                    color = G.inkMuted,
                )
                Text(
                    "本程序为自由软件：您可以根据自由软件基金会发布的 GNU Affero 通用公共许可证第 3 版（或任一更新版本）的条款重新分发和/或修改本程序。本程序以希望其有用的前提分发，但不提供任何担保（包括适销性或特定用途适用性的默示担保）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.inkMuted,
                    lineHeight = 19.sp,
                )
                Text(
                    "查看完整 AGPL-3.0 协议全文 ->",
                    style = MaterialTheme.typography.labelMedium,
                    color = G.accent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(D.shape(6.dp))
                        .clickable { openExternalBrowserUrl(context, SOLBRAID_LICENSE_URL) }
                        .padding(vertical = 2.dp),
                )
            }
        }

        SectionLabel("第三方开源组件（${OPEN_SOURCE_ENTRIES.size}）")
        CrispCard(padding = PaddingValues(0.dp)) {
            OPEN_SOURCE_ENTRIES.forEachIndexed { index, entry ->
                val expanded = entry.name in expandedNames
                val chevronRot by animateFloatAsState(if (expanded) 90f else 0f, label = "oss_chevron")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedNames = if (expanded) expandedNames - entry.name else expandedNames + entry.name
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = G.ink)
                            Text(entry.copyright, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        }
                        TagPill(entry.license, fg = G.inkMuted, bg = G.surfaceSubtle)
                        Icon(
                            GaliaisIcons.ChevronRight,
                            contentDescription = if (expanded) "收起" else "展开",
                            tint = G.inkSubtle,
                            modifier = Modifier.size(16.dp).rotate(chevronRot),
                        )
                    }
                    AnimatedVisibility(visible = expanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clip(D.shape(8.dp))
                                .background(G.surfaceSubtle)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(entry.notice, fontFamily = Mono, fontSize = 11.sp, color = G.ink, lineHeight = 16.sp)
                            Text(
                                entry.url,
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                color = G.accent,
                                modifier = Modifier.clickable { openExternalBrowserUrl(context, entry.url) },
                            )
                        }
                    }
                }
                if (index < OPEN_SOURCE_ENTRIES.lastIndex) {
                    Hairline(Modifier.padding(start = 14.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
