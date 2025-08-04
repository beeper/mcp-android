package com.beeper.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "com.beeper.mcp.RESTART_SERVICE" -> {
                Log.d("ServiceRestartReceiver", "Restarting MCP service due to: ${intent.action}")
                val serviceIntent = Intent(context, McpService::class.java).apply {
                    action = McpService.ACTION_START_SERVICE
                }
                context.startService(serviceIntent)
            }
        }
    }
}