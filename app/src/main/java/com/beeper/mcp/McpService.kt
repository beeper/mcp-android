package com.beeper.mcp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.NetworkInterface

class McpService : Service() {
    
    private val binder = McpBinder()
    private var mcpServer: McpServer? = null
    private var localIpAddress: String = "Getting IP address..."
    private var serviceName: String = "beeper-mcp-server"
    private val handler = Handler(Looper.getMainLooper())
    private var keepAliveRunnable: Runnable? = null
    private var isStoppedByUser = false
    
    companion object {
        const val ACTION_START_SERVICE = "START_MCP_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_MCP_SERVICE"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "mcp_service_channel"
        private const val TAG = "McpService"
    }
    
    inner class McpBinder : Binder() {
        fun getService(): McpService = this@McpService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Foreground service created")
        createNotificationChannel()
        getLocalIpAddress()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                isStoppedByUser = false
                startForegroundService()
                startMcpServer()
                startKeepAlive()
                Log.d(TAG, "MCP foreground service started with keep-alive")
            }
            ACTION_STOP_SERVICE -> {
                isStoppedByUser = true
                stopKeepAlive()
                stopMcpServer()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                Log.d(TAG, "MCP Service stopped by user request")
            }
            else -> {
                // Default action: start the service
                startForegroundService()
                startMcpServer()
                startKeepAlive()
                Log.d(TAG, "MCP foreground service started (default action)")
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
        
        // Only schedule restart if not explicitly stopped by user
        if (!isStoppedByUser) {
            val restartIntent = Intent("com.beeper.mcp.RESTART_SERVICE").apply {
                setClass(this@McpService, ServiceRestartReceiver::class.java)
            }
            sendBroadcast(restartIntent)
            Log.d(TAG, "Service destroyed, restart broadcast sent")
        } else {
            Log.d(TAG, "Service destroyed by user, not scheduling restart")
        }
    }
    
    
    private fun startMcpServer() {
        if (mcpServer == null) {
            mcpServer = McpServer(this)
            serviceName = "beeper-mcp-server"
            mcpServer?.start()
            updateNotification() // Update notification with correct IP
            Log.d(TAG, "MCP Server started with complete protocol support")
        }
    }
    
    private fun stopMcpServer() {
        mcpServer?.stop()
        mcpServer = null
        Log.d(TAG, "MCP Server stopped")
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
                            Log.d(TAG, "Local IP address: $localIpAddress")
                            updateNotification() // Update notification with IP
                            return
                        }
                    }
                }
            }
            localIpAddress = "No network connection"
        } catch (e: Exception) {
            localIpAddress = "Error getting IP: ${e.message}"
            Log.e(TAG, "Error getting local IP address", e)
        }
    }
    
    private fun startKeepAlive() {
        keepAliveRunnable = object : Runnable {
            override fun run() {
                // Perform a light operation to keep service active
                Log.v(TAG, "Keep-alive: Service running, server active: ${mcpServer != null}")
                handler.postDelayed(this, 30_000) // Every 30 seconds
            }
        }
        handler.post(keepAliveRunnable!!)
        Log.d(TAG, "Keep-alive mechanism started")
    }
    
    private fun stopKeepAlive() {
        keepAliveRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            keepAliveRunnable = null
            Log.d(TAG, "Keep-alive mechanism stopped")
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
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Beeper MCP Server notifications"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }
    
    private fun startForegroundService() {
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        Log.d(TAG, "Started as foreground service with notification")
    }
    
    private fun createNotification(): Notification {
        val stopIntent = Intent(this, McpService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Beeper MCP Server")
            .setContentText("Server running on $localIpAddress:8081")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }
    
    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
}