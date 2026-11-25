package com.galiais.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.CustomProvider
import com.galiais.agent.engine.ProviderAuthMode
import com.galiais.agent.engine.ProviderProtocol
import com.galiais.agent.engine.maskApiKey
import com.galiais.agent.engine.newProviderId
import com.galiais.agent.engine.providerPresets
import com.galiais.agent.engine.suggestProtocolForUrl
import com.galiais.agent.engine.validateProviderBaseUrl
import kotlinx.coroutines.launch

private fun parseModelMap(text: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val arrowIdx = line.indexOf("->")
        if (arrowIdx > 0) {
            val k = line.substring(0, arrowIdx).trim()
            val v = line.substring(arrowIdx + 2).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) result[k] = v
            continue
        }
        val eqIdx = line.indexOf('=')
        if (eqIdx > 0) {
            val k = line.substring(0, eqIdx).trim()
            val v = line.substring(eqIdx + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) result[k] = v
        }
    }
    return result
}

private fun formatPairs(map: Map<String, String>, sep: String): String =
    map.entries.joinToString("\n") { "${it.key}$sep${it.value}" }

private fun parsePairs(text: String, sep: Char): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val idx = line.indexOf(sep)
        if (idx > 0) result[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditorDialog(
    app: GaliaisApplication,
    initial: CustomProvider?,
    onDismiss: () -> Unit,
    onSaved: (CustomProvider) -> Unit,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val pluginTranslators = remember(hostPlugins) { if (hostPlugins.safeMode) emptyList() else hostPlugins.translators }
    val scope = rememberCoroutineScope()
    val id = remember(initial) { initial?.id ?: newProviderId() }
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var protocol by remember(initial) { mutableStateOf(initial?.protocol ?: ProviderProtocol.OPENAI_CHAT) }
    var customProtocolId by remember(initial) { mutableStateOf(initial?.customProtocolId ?: "") }
    var baseUrl by remember(initial) { mutableStateOf(initial?.baseUrl ?: ProviderProtocol.OPENAI_CHAT.defaultUrl) }
    var authMode by remember(initial) { mutableStateOf(initial?.authMode ?: ProviderAuthMode.BEARER) }
    var apiKey by remember(initial) { mutableStateOf("") }
    val backupKeys = remember(initial) {
        mutableStateListOf<String>().apply {
            initial?.let { addAll(app.settings.providerApiKeys(it.id).drop(1)) }
        }
    }
    var newBackupKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var clearKey by remember(initial) { mutableStateOf(false) }
    var presetCategory by remember { mutableStateOf("all") }
    var selectedPresetId by remember(initial) { mutableStateOf(initial?.presetId) }
    var selectedRegionId by remember(initial) { mutableStateOf(initial?.regionId) }
    var chatUrl by remember(initial) { mutableStateOf(initial?.chatUrl ?: "") }
    var responsesUrl by remember(initial) { mutableStateOf(initial?.responsesUrl ?: "") }
    var anthropicUrl by remember(initial) { mutableStateOf(initial?.anthropicUrl ?: "") }
    var geminiUrl by remember(initial) { mutableStateOf(initial?.geminiUrl ?: "") }
    var billingKind by remember(initial) { mutableStateOf(initial?.billingKind ?: "") }
    var priceRateText by remember(initial) {
        mutableStateOf(
            when {
                initial == null -> "1.0"
                initial.billingKind in setOf("plan", "free") -> "0.0"
                else -> initial.priceRate.toString()
            },
        )
    }
    var extraBodyJson by remember(initial) { mutableStateOf(initial?.extraBodyJson ?: "") }
    val currentSettings by app.settings.state.collectAsState()
    var setCodexDefault by remember(initial) { mutableStateOf(initial != null && currentSettings.codexProviderId == initial.id) }
    var setClaudeDefault by remember(initial) { mutableStateOf(initial != null && currentSettings.claudeProviderId == initial.id) }
    var setAgyDefault by remember(initial) { mutableStateOf(initial != null && currentSettings.agentProviders["antigravity"] == initial.id) }
    val models = remember(initial) { mutableStateListOf<String>().apply { initial?.models?.let(::addAll) } }
    var newModel by remember { mutableStateOf("") }
    var modelMapText by remember(initial) { mutableStateOf(initial?.modelMap?.let { formatPairs(it, " -> ") } ?: "") }
    var headersText by remember(initial) { mutableStateOf(initial?.headers?.let { formatPairs(it, ": ") } ?: "") }
    var queryText by remember(initial) { mutableStateOf(initial?.queryParams?.let { formatPairs(it, "=") } ?: "") }
    var protocolMenu by remember { mutableStateOf(false) }
    var authMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>?>(null) }
    val activePreset = remember(selectedPresetId) { providerPresets.firstOrNull { it.id == selectedPresetId } }
    val suggestedProtocol = remember(baseUrl, protocol, customProtocolId) {
        if (customProtocolId.startsWith("plugin:")) null
        else suggestProtocolForUrl(baseUrl)?.takeIf { it != protocol }
    }

    fun syncBillingDefaults(kind: String) {
        billingKind = kind
        priceRateText = if (kind in setOf("plan", "free")) "0.0" else if (priceRateText == "0.0" || priceRateText == "0" || priceRateText.isBlank()) "1.0" else priceRateText
    }

    fun addBackupKeyInput() {
        val entries = newBackupKey.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (entries.isEmpty()) return
        for (entry in entries) {
            if (entry !in backupKeys && backupKeys.size < 31) backupKeys.add(entry)
        }
        newBackupKey = ""
        clearKey = false
    }

    fun resolvedPool(): List<String>? {
        if (clearKey) return emptyList()
        val existingPrimary = if (apiKey.isBlank()) initial?.let { app.settings.providerApiKey(it.id) } else apiKey.trim()
        val pendingExtras = newBackupKey.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val extras = (backupKeys + pendingExtras).distinct()
        val combined = buildList {
            if (!existingPrimary.isNullOrBlank()) add(existingPrimary)
            extras.forEach { if (it !in this) add(it) }
        }
        val hadPool = (initial?.keyPoolCount ?: 0) > 1
        return if (apiKey.isNotBlank() || extras.isNotEmpty() || hadPool) combined else null
    }

    fun draftProvider(): CustomProvider {
        val effectiveRate = if (billingKind in setOf("plan", "free")) 0.0
        else priceRateText.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0
        val cleanExtra = extraBodyJson.trim()
        if (cleanExtra.isNotEmpty()) org.json.JSONObject(cleanExtra) // 校验合法 JSON 对象
        return CustomProvider(
            id = id,
            name = name.trim().ifEmpty { protocol.shortLabel },
            protocol = protocol,
            baseUrl = validateProviderBaseUrl(baseUrl),
            authMode = authMode,
            models = models.toList(),
            modelMap = parseModelMap(modelMapText),
            headers = parsePairs(headersText, ':'),
            queryParams = parsePairs(queryText, '='),
            hasApiKey = (initial?.hasApiKey == true && !clearKey) || apiKey.isNotBlank() || backupKeys.isNotEmpty(),
            presetId = selectedPresetId,
            regionId = selectedRegionId,
            chatUrl = if (protocol == ProviderProtocol.OPENAI_CHAT) "" else chatUrl.trim(),
            responsesUrl = if (protocol == ProviderProtocol.OPENAI_RESPONSES) "" else responsesUrl.trim(),
            anthropicUrl = if (protocol == ProviderProtocol.ANTHROPIC_MESSAGES) "" else anthropicUrl.trim(),
            geminiUrl = if (protocol == ProviderProtocol.GOOGLE_GEMINI) "" else geminiUrl.trim(),
            extraBodyJson = cleanExtra,
            billingKind = billingKind,
            priceRate = effectiveRate,
            anthropicAuthFallback = true,
            enabled = initial?.enabled ?: true,
            customProtocolId = customProtocolId,
        )
    }

    GaliaisSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (initial == null) "添加自定义服务商" else "编辑服务商",
                    style = MaterialTheme.typography.titleLarge,
                    color = G.ink,
                    modifier = Modifier.weight(1f),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (initial == null) {
                    Text("快速预设（${providerPresets.size} 个官方/套餐/中转）", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("all" to "全部", "vendor" to "官方模型商", "plan" to "Token/Coding 套餐", "relay" to "中转聚合", "local" to "本地").forEach { (cid, label) ->
                            SelectableChip(text = label, selected = presetCategory == cid, onClick = { presetCategory = cid })
                        }
                    }
                    val visiblePresets = remember(presetCategory) {
                        if (presetCategory == "all") providerPresets else providerPresets.filter { it.category == presetCategory }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        visiblePresets.forEach { preset ->
                            SelectableChip(
                                text = preset.label,
                                selected = selectedPresetId == preset.id,
                                onClick = {
                                    selectedPresetId = preset.id
                                    selectedRegionId = preset.regions.firstOrNull()?.id
                                    name = preset.name
                                    protocol = preset.protocol
                                    customProtocolId = ""
                                    baseUrl = preset.baseUrl
                                    authMode = preset.authMode
                                    chatUrl = if (preset.protocol == ProviderProtocol.OPENAI_CHAT) "" else preset.chatUrl
                                    responsesUrl = if (preset.protocol == ProviderProtocol.OPENAI_RESPONSES) "" else preset.responsesUrl
                                    anthropicUrl = if (preset.protocol == ProviderProtocol.ANTHROPIC_MESSAGES) "" else preset.anthropicUrl
                                    geminiUrl = if (preset.protocol == ProviderProtocol.GOOGLE_GEMINI) "" else preset.geminiUrl
                                    syncBillingDefaults(preset.billingKind)
                                    models.clear()
                                    models.addAll(preset.sampleModels)
                                },
                            )
                        }
                    }
                    activePreset?.takeIf { it.note.isNotBlank() }?.let { p ->
                        Text(p.note, style = MaterialTheme.typography.bodySmall, color = G.accent)
                    }
                }
                activePreset?.takeIf { it.regions.isNotEmpty() }?.let { p ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("区域 / 套餐方案（${p.label}）", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.regions.forEach { region ->
                                SelectableChip(
                                    text = region.label,
                                    selected = selectedRegionId == region.id,
                                    onClick = {
                                        selectedRegionId = region.id
                                        val nextProto = when {
                                            region.responsesUrl.isNotBlank() -> ProviderProtocol.OPENAI_RESPONSES
                                            region.chatUrl.isNotBlank() -> ProviderProtocol.OPENAI_CHAT
                                            region.anthropicUrl.isNotBlank() -> ProviderProtocol.ANTHROPIC_MESSAGES
                                            else -> ProviderProtocol.GOOGLE_GEMINI
                                        }
                                        protocol = nextProto
                                        customProtocolId = ""
                                        baseUrl = region.responsesUrl.ifBlank { region.chatUrl.ifBlank { region.anthropicUrl.ifBlank { region.geminiUrl } } }
                                        chatUrl = if (nextProto == ProviderProtocol.OPENAI_CHAT) "" else region.chatUrl
                                        responsesUrl = if (nextProto == ProviderProtocol.OPENAI_RESPONSES) "" else region.responsesUrl
                                        anthropicUrl = if (nextProto == ProviderProtocol.ANTHROPIC_MESSAGES) "" else region.anthropicUrl
                                        geminiUrl = if (nextProto == ProviderProtocol.GOOGLE_GEMINI) "" else region.geminiUrl
                                        if (region.billingKind.isNotBlank()) syncBillingDefaults(region.billingKind)
                                        if (region.sampleModels.isNotEmpty() && initial == null) {
                                            models.clear()
                                            models.addAll(region.sampleModels)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("服务商名称") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 协议选择器
                Box {
                    val activeTranslator = pluginTranslators.firstOrNull { "plugin:${it.id}" == customProtocolId }
                    val protoLabel = if (customProtocolId.startsWith("plugin:")) {
                        "插件协议：${activeTranslator?.name ?: customProtocolId.removePrefix("plugin:")}"
                    } else protocol.label
                    PickerField(
                        label = "接口协议",
                        value = protoLabel,
                        onClick = { protocolMenu = true },
                    )
                    GaliaisDropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ProviderProtocol.entries.forEach { item ->
                                GaliaisMenuRow(
                                    title = item.label,
                                    selected = customProtocolId.isBlank() && protocol == item,
                                    onClick = {
                                        val prevDefault = protocol.defaultUrl
                                        protocol = item
                                        customProtocolId = ""
                                        if (baseUrl.isBlank() || baseUrl == prevDefault) baseUrl = item.defaultUrl
                                        authMode = item.defaultAuth
                                        protocolMenu = false
                                    },
                                )
                            }
                            if (pluginTranslators.isNotEmpty()) {
                                Hairline(Modifier.padding(vertical = 2.dp))
                                pluginTranslators.forEach { tr ->
                                    val wire = "plugin:${tr.id}"
                                    GaliaisMenuRow(
                                        title = "插件：${tr.name} ($wire)",
                                        subtitle = tr.description.ifBlank { "由插件 ${tr.pluginId} 热注册" },
                                        selected = customProtocolId == wire,
                                        onClick = {
                                            customProtocolId = wire
                                            protocolMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                val activePluginTranslator = remember(pluginTranslators, customProtocolId) {
                    pluginTranslators.firstOrNull { "plugin:${it.id}" == customProtocolId }
                }
                Text(
                    if (customProtocolId.startsWith("plugin:")) "通过本地插件控制通道实时翻译 Codex / Claude / 兼容接口协议（$customProtocolId）" else protocol.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = G.inkSubtle,
                )
                if (customProtocolId.startsWith("plugin:") && activePluginTranslator == null) {
                    Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = G.warningTint) {
                        Text(
                            "当前绑定的插件协议（$customProtocolId）未加载，所属插件可能已卸载、停用或处于安全模式；请重新启用对应插件或在上方切换为其他协议。",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.warning,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { raw ->
                        val next = raw.trim()
                        if (next != baseUrl) {
                            baseUrl = next
                            // 修改主 Base URL 时清空预设自带的多协议覆盖端点，防止旧厂商地址静默劫持请求。
                            responsesUrl = ""
                            chatUrl = ""
                            anthropicUrl = ""
                            geminiUrl = ""
                        }
                    },
                    label = { Text("API 地址（Base URL）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                val endpointPreview = if (customProtocolId.startsWith("plugin:")) "" else protocol.resolvedEndpointPreview(baseUrl)
                if (endpointPreview.isNotEmpty()) {
                    Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = G.surfaceSubtle) {
                        Text(
                            endpointPreview,
                            fontFamily = Mono,
                            fontSize = 11.sp,
                            color = G.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                suggestedProtocol?.let { suggested ->
                    Surface(
                        onClick = {
                            protocol = suggested
                            customProtocolId = ""
                            authMode = suggested.defaultAuth
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = D.shape(8.dp),
                        color = G.accentContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "检测到该地址更匹配「${suggested.label}」，点击切换",
                                style = MaterialTheme.typography.labelSmall,
                                color = G.onAccentContainer,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text("切换 →", style = MaterialTheme.typography.labelSmall, color = G.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // 认证方式
                Box {
                    PickerField(
                        label = "认证方式",
                        value = authMode.label,
                        onClick = { authMenu = true },
                    )
                    GaliaisDropdownMenu(expanded = authMenu, onDismissRequest = { authMenu = false }) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ProviderAuthMode.entries.forEach { item ->
                                GaliaisMenuRow(
                                    title = item.label,
                                    selected = authMode == item,
                                    onClick = { authMode = item; authMenu = false },
                                )
                            }
                        }
                    }
                }

                if (authMode != ProviderAuthMode.NONE) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim(); if (it.isNotBlank()) clearKey = false },
                        label = { Text(if (initial?.hasApiKey == true && !clearKey) "API Key（留空保留原密钥）" else "API Key") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            if (apiKey.isNotEmpty()) {
                                TextButton(onClick = { showKey = !showKey }) {
                                    Text(if (showKey) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall, color = G.accent)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (initial?.hasApiKey == true && apiKey.isBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(D.shape(8.dp))
                                .toggleable(clearKey, role = Role.Checkbox) { clearKey = it }
                                .padding(vertical = 2.dp),
                        ) {
                            GaliaisCheckbox(clearKey)
                            Text("清除已保存的密钥", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    }
                }

                Text("模型列表（${models.size}）", style = MaterialTheme.typography.labelLarge, color = G.ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newModel,
                        onValueChange = { newModel = it.trim() },
                        label = { Text("添加模型 ID") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (newModel.isNotBlank()) G.accent else G.surfaceSubtle)
                            .semantics { contentDescription = "添加模型" }
                            .clickable(enabled = newModel.isNotBlank(), role = Role.Button) {
                                val m = newModel.trim()
                                if (m.isNotEmpty() && m !in models) models.add(m)
                                newModel = ""
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            GaliaisIcons.Add,
                            contentDescription = null,
                            tint = if (newModel.isNotBlank()) G.onAccent else G.inkSubtle,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (models.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        models.forEach { m ->
                            Surface(
                                shape = D.shape(999.dp),
                                color = G.surfaceSubtle,
                                border = BorderStroke(0.75.dp, G.border),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(m, fontFamily = Mono, fontSize = 11.5.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .clickable(role = Role.Button) { models.remove(m) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(GaliaisIcons.Close, contentDescription = "移除模型 $m", tint = G.inkMuted, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryPillButton(
                        text = "检测并获取模型",
                        enabled = runtime.ready && !busy && baseUrl.isNotBlank(),
                        onClick = {
                            busy = true; statusText = null
                            scope.launch {
                                try {
                                    val res = app.conversations.testProvider(draftProvider(), apiKey.ifBlank { null }, resolvedPool())
                                    val ms = res.optInt("latencyMs")
                                    val remoteArr = res.optJSONArray("models")
                                    val list = buildList {
                                        if (remoteArr != null) {
                                            for (i in 0 until remoteArr.length()) {
                                                val m = remoteArr.optString(i).trim()
                                                if (m.isNotEmpty()) add(m)
                                            }
                                        }
                                    }
                                    if (list.isEmpty()) {
                                        statusText = "连接正常（${ms}ms），但该接口未返回模型列表，可手动填写模型 ID"
                                        statusError = false
                                    } else {
                                        fetchedModels = list
                                        statusText = "连接正常 · 已获取 ${list.size} 个模型 · ${ms}ms"
                                        statusError = false
                                    }
                                } catch (error: Exception) { statusText = "连接失败：${error.message}"; statusError = true }
                                busy = false
                            }
                        },
                    )
                    SecondaryPillButton(
                        text = "推理实测 Ping",
                        enabled = runtime.ready && !busy && baseUrl.isNotBlank(),
                        onClick = {
                            busy = true; statusText = null
                            scope.launch {
                                try {
                                    val res = app.conversations.pingProvider(draftProvider(), apiKey.ifBlank { null }, models.firstOrNull(), resolvedPool())
                                    val m = res.optString("mappedModel").ifBlank { res.optString("model") }
                                    val ttft = res.optInt("ttftMs")
                                    val ms = res.optInt("latencyMs")
                                    val prev = res.optString("preview")
                                    statusText = "实测成功（$m）· TTFT ${ttft}ms / 总 ${ms}ms" + if (prev.isNotBlank()) " · 「$prev」" else ""
                                    statusError = false
                                } catch (error: Exception) { statusText = "推理实测失败：${error.message}"; statusError = true }
                                busy = false
                            }
                        },
                    )
                }

                if (!runtime.ready) {
                    Text("启动运行环境后可在线拉取模型与推理实测。", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                }
                if (busy) {
                    GaliaisProgressBar(thin = true)
                }
                statusText?.let {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = D.shape(10.dp),
                        color = if (statusError) G.dangerTint else G.successTint,
                    ) {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (statusError) G.danger else G.success, modifier = Modifier.padding(10.dp))
                    }
                }

                Collapsible(
                    title = if (modelMapText.isNotBlank()) "模型别名映射（已配 ${parseModelMap(modelMapText).size} 条）" else "模型别名映射（可选）",
                    initiallyOpen = modelMapText.isNotBlank(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("每行「原模型 -> 目标模型」（支持 * 通配符），便于用 Claude/Codex 默认模型名映射到第三方模型。", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        val firstModel = models.firstOrNull().orEmpty()
                        if (firstModel.isNotBlank()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                TagPill("Claude 别名 → $firstModel", fg = G.claude, bg = G.claudeTint, onClick = {
                                    val snippet = "claude-opus-* -> $firstModel\nclaude-sonnet-* -> $firstModel\nclaude-haiku-* -> $firstModel"
                                    modelMapText = if (modelMapText.isBlank()) snippet else "$modelMapText\n$snippet"
                                })
                                TagPill("GPT/Codex 别名 → $firstModel", fg = G.codex, bg = G.codexTint, onClick = {
                                    val snippet = "gpt-* -> $firstModel"
                                    modelMapText = if (modelMapText.isBlank()) snippet else "$modelMapText\n$snippet"
                                })
                            }
                        }
                        OutlinedTextField(
                            value = modelMapText,
                            onValueChange = { modelMapText = it },
                            label = { Text("模型映射表（如 claude-sonnet-* -> deepseek-chat）") },
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                val extraEndpointCount = listOf(
                    if (protocol == ProviderProtocol.OPENAI_RESPONSES) "" else responsesUrl,
                    if (protocol == ProviderProtocol.OPENAI_CHAT) "" else chatUrl,
                    if (protocol == ProviderProtocol.ANTHROPIC_MESSAGES) "" else anthropicUrl,
                    if (protocol == ProviderProtocol.GOOGLE_GEMINI) "" else geminiUrl,
                ).count { it.isNotBlank() }
                Collapsible(
                    title = if (extraEndpointCount > 0) "多协议直通端点（已配 $extraEndpointCount 个）" else "多协议直通端点（可选）",
                    initiallyOpen = false,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("留空时网关自动通过主协议转换；若服务商另提供其他原生协议地址，可在此单独指定以直通免转换。", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        if (protocol != ProviderProtocol.OPENAI_RESPONSES) {
                            OutlinedTextField(value = responsesUrl, onValueChange = { responsesUrl = it.trim() }, label = { Text("OpenAI Responses 地址") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol != ProviderProtocol.OPENAI_CHAT) {
                            OutlinedTextField(value = chatUrl, onValueChange = { chatUrl = it.trim() }, label = { Text("OpenAI Chat 兼容地址") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol != ProviderProtocol.ANTHROPIC_MESSAGES) {
                            OutlinedTextField(value = anthropicUrl, onValueChange = { anthropicUrl = it.trim() }, label = { Text("Anthropic Messages 地址") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol != ProviderProtocol.GOOGLE_GEMINI) {
                            OutlinedTextField(value = geminiUrl, onValueChange = { geminiUrl = it.trim() }, label = { Text("Google Gemini 地址") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                val hasAdvancedCustom = backupKeys.isNotEmpty() || headersText.isNotBlank() || queryText.isNotBlank() || extraBodyJson.isNotBlank() || billingKind.isNotBlank()
                Collapsible(
                    title = "多 Key 池 · 计费与请求定制",
                    initiallyOpen = hasAdvancedCustom,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (authMode != ProviderAuthMode.NONE) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("备用 API Key 池（${backupKeys.size} 个，遇 429/5xx 自动冷却切换）", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                if (backupKeys.isNotEmpty()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        backupKeys.forEachIndexed { idx, secret ->
                                            Surface(
                                                shape = D.shape(999.dp),
                                                color = G.surfaceSubtle,
                                                border = BorderStroke(0.75.dp, G.border),
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                ) {
                                                    Text("#${idx + 2} ${maskApiKey(secret)}", fontFamily = Mono, fontSize = 11.5.sp, color = G.ink)
                                                    Box(
                                                        modifier = Modifier
                                                            .size(22.dp)
                                                            .clip(CircleShape)
                                                            .clickable(role = Role.Button) { backupKeys.removeAt(idx) },
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Icon(GaliaisIcons.Close, contentDescription = "移除备用密钥", tint = G.inkMuted, modifier = Modifier.size(12.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = newBackupKey,
                                        onValueChange = { newBackupKey = it },
                                        label = { Text("新增备用 Key（支持多行粘贴）") },
                                        singleLine = false,
                                        maxLines = 2,
                                        visualTransformation = PasswordVisualTransformation(),
                                        shape = D.fieldShape,
                                        colors = galiaisTextFieldColors(),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                        modifier = Modifier.weight(1f),
                                    )
                                    SecondaryPillButton("加入", enabled = newBackupKey.isNotBlank(), onClick = ::addBackupKeyInput)
                                }
                            }
                            Hairline()
                        }
                        AgentOptionChips(
                            title = "计费类型（用于用量中心估算）",
                            options = listOf("" to "自动", "plan" to "订阅套餐", "payg" to "按量计费", "free" to "免费"),
                            selected = billingKind,
                            onSelect = { syncBillingDefaults(it ?: "") },
                        )
                        if (billingKind !in setOf("plan", "free")) {
                            OutlinedTextField(
                                value = priceRateText,
                                onValueChange = { priceRateText = it.trim() },
                                label = { Text("费用倍率（默认 1.0，中转折扣可填 0.5）") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Hairline()
                        OutlinedTextField(
                            value = extraBodyJson,
                            onValueChange = { extraBodyJson = it },
                            label = { Text("附加请求体 JSON（可选，如 {\"enable_thinking\":true}）") },
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = headersText,
                            onValueChange = { headersText = it },
                            label = { Text("自定义请求头（每行 Key: Value）") },
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = queryText,
                            onValueChange = { queryText = it },
                            label = { Text("查询参数（每行 key=value，如 api-version）") },
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectableChip("设为 Codex 默认", selected = setCodexDefault, onClick = { setCodexDefault = !setCodexDefault })
                    SelectableChip("设为 Claude Code 默认", selected = setClaudeDefault, onClick = { setClaudeDefault = !setClaudeDefault })
                    SelectableChip("设为 Antigravity 默认", selected = setAgyDefault, onClick = { setAgyDefault = !setAgyDefault })
                }
            }

            Hairline()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryPillButton("取消", onClick = onDismiss, enabled = !busy)
                PrimaryPillButton(
                    text = "保存",
                    modifier = Modifier.weight(1f),
                    enabled = !busy && name.isNotBlank() && baseUrl.isNotBlank(),
                    onClick = {
                        try {
                            val saved = draftProvider()
                            app.conversations.saveProvider(saved, apiKey.ifBlank { null }, clearKey, resolvedPool())
                            if (setCodexDefault) app.settings.setCodexProvider(saved.id)
                            else if (currentSettings.codexProviderId == saved.id) app.settings.setCodexProvider(null)
                            if (setClaudeDefault) app.settings.setClaudeProvider(saved.id)
                            else if (currentSettings.claudeProviderId == saved.id) app.settings.setClaudeProvider(null)
                            if (setAgyDefault) app.settings.setAgentProvider("antigravity", saved.id)
                            else if (currentSettings.agentProviders["antigravity"] == saved.id) app.settings.setAgentProvider("antigravity", null)
                            onSaved(saved)
                        } catch (error: Exception) {
                            statusText = error.message
                            statusError = true
                        }
                    },
                )
            }
        }
    }

    fetchedModels?.let { remote ->
        RemoteModelPickerDialog(remote, existing = models.toSet(), onDismiss = { fetchedModels = null }) { chosen ->
            for (m in chosen) if (m !in models) models.add(m)
            fetchedModels = null
        }
    }
}

@Composable
internal fun RemoteModelPickerDialog(remote: List<String>, existing: Set<String>, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(existing.filter { it in remote }) } }
    val filtered = remember(remote, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) remote else remote.filter { it.lowercase().contains(q) }
    }
    GaliaisSheetScaffold(
        title = "选择远端模型",
        subtitle = "已选 ${selected.size} / 共 ${remote.size} 个模型",
        onDismiss = onDismiss,
        scrollable = false,
        footer = {
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton(
                text = "添加所选 (${selected.size})",
                modifier = Modifier.weight(1f),
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(selected.toList()) },
            )
        },
    ) {
        SearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = "按模型 ID 或系列筛选…",
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(listOf("gpt", "claude", "gemini", "deepseek", "qwen")) { family ->
                SelectableChip(
                    text = family,
                    selected = query.equals(family, ignoreCase = true),
                    onClick = { query = if (query.equals(family, ignoreCase = true)) "" else family },
                )
            }
            item {
                TagPill("全选当前", fg = G.accent, bg = G.accentContainer, onClick = {
                    filtered.take(60).forEach { if (it !in selected) selected.add(it) }
                })
            }
            if (selected.isNotEmpty()) {
                item {
                    TagPill("清空", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { selected.clear() })
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filtered, key = { it }) { id ->
                val checked = id in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(D.shape(8.dp))
                        .toggleable(checked, role = Role.Checkbox) { if (it) selected.add(id) else selected.remove(id) }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                ) {
                    GaliaisCheckbox(checked)
                    Text(
                        id,
                        fontFamily = Mono,
                        style = MaterialTheme.typography.bodySmall,
                        color = G.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
