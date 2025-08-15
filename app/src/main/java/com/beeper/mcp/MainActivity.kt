package com.beeper.mcp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.beeper.mcp.ui.theme.BeeperMcpTheme

class MainActivity : ComponentActivity() {
    private var mcpService: McpService? = null
    private var isBound = false
    private var permissionsGranted by mutableStateOf(false)
    private var batteryOptimized by mutableStateOf(false)
    private var serviceStatus by mutableStateOf(McpService.ServiceStatus(
        isRunning = false,
        serviceName = "beeper-mcp-server",
        localIpAddress = "Getting IP address..."
    ))
    
    private val beeperPermissions = mutableListOf(
        "com.beeper.android.permission.READ_PERMISSION",
        "com.beeper.android.permission.SEND_PERMISSION"
    ).apply {
        // Add notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
        Log.d("MainActivity", "Permission results: $permissions, all granted: $permissionsGranted")
        
        // Start service if permissions were just granted and service isn't running
        if (permissionsGranted && !serviceStatus.isRunning) {
            startMcpService()
            bindToMcpService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Check permissions status
        checkBeeperPermissions()
        
        // Check battery optimization status
        checkBatteryOptimization()
        
        // Only start service if permissions are granted
        if (permissionsGranted) {
            // Start MCP service
            startMcpService()
            
            // Bind to service to get status updates
            bindToMcpService()
        } else {
            Log.d("MainActivity", "Not starting service - permissions not granted")
        }
        
        setContent {
            BeeperMcpTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    McpServerStatus(
                        permissionsGranted = permissionsGranted,
                        batteryOptimized = batteryOptimized,
                        serviceStatus = serviceStatus,
                        modifier = Modifier.padding(innerPadding),
                        onRequestPermissions = { requestBeeperPermissions() },
                        onRequestBatteryOptimization = { requestBatteryOptimizationExemption() },
                        onStartService = { startMcpService() },
                        onStopService = { stopMcpService() }
                    )
                }
            }
        }
    }
    
    private fun checkBeeperPermissions() {
        val permissionsToRequest = beeperPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        permissionsGranted = permissionsToRequest.isEmpty()
        Log.d("MainActivity", "Permissions status - All granted: $permissionsGranted")
    }
    
    private fun requestBeeperPermissions() {
        val permissionsToRequest = beeperPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Requesting permissions: $permissionsToRequest")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    private fun startMcpService() {
        val intent = Intent(this, McpService::class.java).apply {
            action = McpService.ACTION_START_SERVICE
        }

        startForegroundService(intent)
        Log.d("MainActivity", "Started MCP foreground service")

        // Re-bind to service after starting
        if (!isBound) {
            bindToMcpService()
        }
    }
    
    private fun stopMcpService() {
        val intent = Intent(this, McpService::class.java).apply {
            action = McpService.ACTION_STOP_SERVICE
        }
        
        // Use startForegroundService for Android O+ to properly communicate with foreground service
        startForegroundService(intent)

        // Unbind from service when stopping
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            mcpService = null
        }
        
        // Update UI to reflect stopped state
        serviceStatus = McpService.ServiceStatus(
            isRunning = false,
            serviceName = "beeper-mcp-server",
            localIpAddress = serviceStatus.localIpAddress
        )
        
        Log.d("MainActivity", "Sent stop command to MCP service")
    }
    
    private fun bindToMcpService() {
        if (!isBound) {
            val intent = Intent(this, McpService::class.java)
            try {
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                Log.d("MainActivity", "Binding to MCP service")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to bind to service", e)
            }
        }
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
    
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        batteryOptimized = powerManager.isIgnoringBatteryOptimizations(packageName)
        Log.d("MainActivity", "Battery optimization exempt: $batteryOptimized")
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
            batteryOptimized = true
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
        // Re-check permissions and battery status when app resumes
        checkBeeperPermissions()
        checkBatteryOptimization()
    }
}

@Composable
fun McpServerStatus(
    modifier: Modifier = Modifier,
    permissionsGranted: Boolean = false,
    batteryOptimized: Boolean = false,
    serviceStatus: McpService.ServiceStatus = McpService.ServiceStatus(
        isRunning = false,
        serviceName = "beeper-mcp-server",
        localIpAddress = "Getting IP address..."
    ),
    onRequestPermissions: () -> Unit = {},
    onRequestBatteryOptimization: () -> Unit = {},
    onStartService: () -> Unit = {},
    onStopService: () -> Unit = {}
) {
    Column(
        modifier = modifier.padding(16.dp)
    ) {
        Text(
            text = "🤖 Beeper MCP Server",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        // Status Table
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Permissions Row
                StatusRow(
                    label = "Permissions",
                    status = if (permissionsGranted) "✅ Granted" else "❌ Not Granted",
                    statusColor = if (permissionsGranted) Color(0xFF4CAF50) else Color(0xFFF44336),
                    buttonText = "Fix",
                    showButton = !permissionsGranted,
                    onButtonClick = onRequestPermissions
                )
                
                Divider(modifier = Modifier.padding(vertical = 12.dp))
                
                // Battery Optimization Row
                StatusRow(
                    label = "Battery Status",
                    status = if (batteryOptimized) "✅ Unrestricted" else "⚠️ Restricted",
                    statusColor = if (batteryOptimized) Color(0xFF4CAF50) else Color(0xFFFF9800),
                    buttonText = "Fix",
                    showButton = !batteryOptimized,
                    onButtonClick = onRequestBatteryOptimization
                )
                
                Divider(modifier = Modifier.padding(vertical = 12.dp))
                
                // Service Status Row
                StatusRow(
                    label = "Service Status",
                    status = if (serviceStatus.isRunning) "✅ Running" else "⏸️ Stopped",
                    statusColor = if (serviceStatus.isRunning) Color(0xFF4CAF50) else Color(0xFFFF9800),
                    buttonText = if (serviceStatus.isRunning) "Stop" else "Start",
                    showButton = true,
                    onButtonClick = if (serviceStatus.isRunning) onStopService else onStartService
                )
            }
        }
        
        // Connection Info
        if (serviceStatus.isRunning) {
            Spacer(modifier = Modifier.height(24.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E1E1E)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Connection Info",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Text(
                        text = "Add to Claude:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB0B0B0),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "$ claude mcp add --transport sse \\\n  beeper-android \\\n  http://${serviceStatus.localIpAddress}:8081",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF00FF00),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = Color(0xFF0D0D0D),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(12.dp)
                    )
                }
            }
        }
        
        // Warning message
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "⚠️ Authentication not implemented",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFFF9800),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun StatusRow(
    label: String,
    status: String,
    statusColor: Color,
    buttonText: String,
    showButton: Boolean,
    onButtonClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        
        if (showButton) {
            Button(
                onClick = onButtonClick,
                modifier = Modifier.width(100.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
            ) {
                Text(buttonText)
            }
        } else {
            Spacer(modifier = Modifier.width(100.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun McpServerPreview() {
    BeeperMcpTheme {
        McpServerStatus(
            permissionsGranted = false,
            batteryOptimized = true,
            serviceStatus = McpService.ServiceStatus(
                isRunning = true,
                serviceName = "beeper-mcp-server",
                localIpAddress = "192.168.1.100"
            )
        )
    }
}