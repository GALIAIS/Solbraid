package com.galiais.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galiais.agent.engine.AgentInstallPhase
import com.galiais.agent.engine.AgentInstallProgress
import com.galiais.agent.engine.AgentRuntimeStatus

internal val AgentRuntimeStatus.showInstallationProgress: Boolean
    get() = installing || (installation != null && installation.phase != AgentInstallPhase.COMPLETED)

@Composable
internal fun AgentInstallProgressView(status: AgentRuntimeStatus, modifier: Modifier = Modifier) {
    val installation = status.installation
    if (!status.showInstallationProgress) return
    val active = installation?.phase?.active ?: status.installing
    val complete = installation?.phase == AgentInstallPhase.COMPLETED
    val failed = installation?.phase == AgentInstallPhase.FAILED
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(installation?.phase?.label ?: "正在处理", style = MaterialTheme.typography.bodyMedium,
                color = if (failed) G.danger else if (complete) G.success else G.ink, modifier = Modifier.weight(1f))
            if (installation != null && !complete) {
                Text("步骤 ${installation.step}/${AgentInstallProgress.STEP_COUNT}",
                    style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
            }
        }
        GaliaisProgressBar(thin = false, progress = when {
            active -> null
            complete -> 1f
            else -> ((installation?.step ?: 1) - 1).toFloat() / AgentInstallProgress.STEP_COUNT
        })
    }
}
