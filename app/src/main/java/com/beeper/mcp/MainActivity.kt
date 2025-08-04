package com.beeper.mcp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.beeper.mcp.ui.theme.BeepermcpTheme

class MainActivity : ComponentActivity() {
    private var mcpService: McpService? = null
    private var isBound = false
    private var permissionsGranted by mutableStateOf(false)
    private var serviceStatus by mutableStateOf(McpService.ServiceStatus(
        isRunning = false,
        serviceName = "beeper-mcp-server",
        localIpAddress = "Getting IP address..."
    ))
    
    private val beeperPermissions = arrayOf(
        "com.beeper.android.permission.READ_PERMISSION",
        "com.beeper.android.permission.SEND_PERMISSION"
    )
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
        Log.d("MainActivity", "Permission results: $permissions, all granted: $permissionsGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Check and request Beeper permissions
        checkBeeperPermissions()
        
        // Request battery optimization exemption
        requestBatteryOptimizationExemption()
        
        // Start MCP service
        startMcpService()
        
        // Bind to service to get status updates
        bindToMcpService()
        
        setContent {
            BeepermcpTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    McpServerStatus(
                        permissionsGranted = permissionsGranted,
                        serviceStatus = serviceStatus,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
    
    private fun checkBeeperPermissions() {
        val permissionsToRequest = beeperPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isEmpty()) {
            permissionsGranted = true
            Log.d("MainActivity", "All Beeper permissions already granted")
        } else {
            permissionsGranted = false
            Log.d("MainActivity", "Requesting permissions: $permissionsToRequest")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    private fun startMcpService() {
        val intent = Intent(this, McpService::class.java).apply {
            action = McpService.ACTION_START_SERVICE
        }
        startService(intent)
        Log.d("MainActivity", "Started MCP background service")
    }
    
    private fun bindToMcpService() {
        val intent = Intent(this, McpService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as McpService.McpBinder
            mcpService = binder.getService()
            isBound = true
            updateServiceStatus()
            Log.d("MainActivity", "Bound to MCP service")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            mcpService = null
            isBound = false
            Log.d("MainActivity", "Unbound from MCP service")
        }
    }
    
    private fun updateServiceStatus() {
        mcpService?.let { service ->
            serviceStatus = service.getServiceStatus()
        }
    }
    
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            try {
                startActivity(intent)
                Log.d("MainActivity", "Requesting battery optimization exemption")
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to request battery optimization exemption", e)
            }
        } else {
            Log.d("MainActivity", "Already exempt from battery optimization")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (isBound) {
            updateServiceStatus()
        }
        // Re-check permissions when app resumes
        checkBeeperPermissions()
    }
}

@Composable
fun McpServerStatus(
    modifier: Modifier = Modifier,
    permissionsGranted: Boolean = false,
    serviceStatus: McpService.ServiceStatus = McpService.ServiceStatus(
        isRunning = false,
        serviceName = "beeper-mcp-server",
        localIpAddress = "Getting IP address..."
    ),
) {
    Column(
        modifier = modifier.padding(16.dp)
    ) {
        Text(
            text = "🤖 MCP Server",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        if (!serviceStatus.isRunning) {
            Text(
                text = "❌ Inactive",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (!permissionsGranted) {
            Text(
                text = "❌ Beeper permissions denied",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        
        Text(
            text = "🚨 Authentication not implemented yet 🚨",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp, top = 16.dp)
        )

        Text(
            text = "1) Add to Claude",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 4.dp, top = 16.dp)
        )
        Text(
            text = "$ claude mcp add --transport sse beeper-android http://${serviceStatus.localIpAddress}:8081",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF00FF00),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .background(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        )
        Text(
            text = "2) Start (or restart) claude",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 4.dp, top = 16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun McpServerPreview() {
    BeepermcpTheme {
        McpServerStatus()
    }
}