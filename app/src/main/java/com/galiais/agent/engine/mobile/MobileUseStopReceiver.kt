package com.galiais.agent.engine.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.galiais.agent.GaliaisApplication

/** 通知栏「紧急停止 AI 操控」按钮广播接收器，确保任意 App 界面下拉通知栏即可一键熔断。 */
class MobileUseStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_EMERGENCY_STOP) {
            (context.applicationContext as? GaliaisApplication)?.mobileUse?.triggerEmergencyStop()
        }
    }

    companion object {
        const val ACTION_EMERGENCY_STOP = "com.galiais.agent.MOBILE_USE_EMERGENCY_STOP"
    }
}
