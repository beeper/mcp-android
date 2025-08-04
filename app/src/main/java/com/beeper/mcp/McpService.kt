package com.beeper.mcp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.NetworkInterface

class McpService : Service() {
    
    private val binder = McpBinder()
    private var mcpServer: McpServer? = null
    private var localIpAddress: String = "Getting IP address..."
    private var serviceName: String = "beeper-mcp-server"
    private val handler = Handler(Looper.getMainLooper())
    private var keepAliveRunnable: Runnable? = null
    
    companion object {
        const val ACTION_START_SERVICE = "START_MCP_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_MCP_SERVICE"
    }
    
    inner class McpBinder : Binder() {
        fun getService(): McpService = this@McpService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d("McpService", "Background service created")
        getLocalIpAddress()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startMcpServer()
                startKeepAlive()
                Log.d("McpService", "MCP background service started with keep-alive")
            }
            ACTION_STOP_SERVICE -> {
                stopMcpServer()
                stopSelf()
                Log.d("McpService", "MCP Service stopped")
            }
        }
        return START_STICKY // Restart if killed by system
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopKeepAlive()
        stopMcpServer()
        
        // Schedule restart if not explicitly stopped
        val restartIntent = Intent("com.beeper.mcp.RESTART_SERVICE").apply {
            setClass(this@McpService, ServiceRestartReceiver::class.java)
        }
        sendBroadcast(restartIntent)
        
        Log.d("McpService", "Service destroyed, restart broadcast sent")
    }
    
    
    private fun startMcpServer() {
        if (mcpServer == null) {
            mcpServer = McpServer(this)
            serviceName = "beeper-mcp-server"
            mcpServer?.start()
            Log.d("McpService", "MCP Server started with complete protocol support")
        }
    }
    
    private fun stopMcpServer() {
        mcpServer?.stop()
        mcpServer = null
        Log.d("McpService", "MCP Server stopped")
    }
    
    private fun getLocalIpAddress() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    val addresses = networkInterface.inetAddresses
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && !address.isLinkLocalAddress && address.hostAddress?.contains(':') == false) {
                            localIpAddress = address.hostAddress ?: "Unable to get IP"
                            Log.d("McpService", "Local IP address: $localIpAddress")
                            return
                        }
                    }
                }
            }
            localIpAddress = "No network connection"
        } catch (e: Exception) {
            localIpAddress = "Error getting IP: ${e.message}"
            Log.e("McpService", "Error getting local IP address", e)
        }
    }
    
    private fun startKeepAlive() {
        keepAliveRunnable = object : Runnable {
            override fun run() {
                // Perform a light operation to keep service active
                Log.v("McpService", "Keep-alive: Service running, server active: ${mcpServer != null}")
                handler.postDelayed(this, 30_000) // Every 30 seconds
            }
        }
        handler.post(keepAliveRunnable!!)
        Log.d("McpService", "Keep-alive mechanism started")
    }
    
    private fun stopKeepAlive() {
        keepAliveRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            keepAliveRunnable = null
            Log.d("McpService", "Keep-alive mechanism stopped")
        }
    }
    
    fun getServiceStatus(): ServiceStatus {
        return ServiceStatus(
            isRunning = (mcpServer != null),
            serviceName = serviceName,
            localIpAddress = localIpAddress
        )
    }
    
    data class ServiceStatus(
        val isRunning: Boolean,
        val serviceName: String,
        val localIpAddress: String
    )
}