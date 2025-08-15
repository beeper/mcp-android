package com.beeper.mcp

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.beeper.mcp.tools.handleGetChats
import com.beeper.mcp.tools.handleGetContacts
import com.beeper.mcp.tools.handleGetMessages
import com.beeper.mcp.tools.handleSendMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

const val BEEPER_AUTHORITY = "com.beeper.api"

class McpServer(private val context: Context) {
    companion object {
        private const val TAG = "McpServer"
        private const val PORT = 8081
        private const val SERVICE_NAME = "beeper-mcp-server"
        private const val VERSION = "2.0.0"
    }
    

    private var ktorServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val activeSessions = ConcurrentHashMap<String, SessionInfo>()
    
    data class SessionInfo(
        val id: String,
        val clientInfo: String,
        val startTime: Long = System.currentTimeMillis()
    )

    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ktorServer = embeddedServer(Netty, port = PORT) {
                    // Install required features
                    install(SSE) {
                        // Configure SSE settings
                    }
                    
                    install(CORS) {
                        allowMethod(HttpMethod.Options)
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowMethod(HttpMethod.Put)
                        allowMethod(HttpMethod.Delete)
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Authorization)
                        allowHeader(HttpHeaders.Accept)
                        allowHeader("X-Request-Id")
                        allowHeader("X-Client-Name")
                        allowHeader("X-Client-Version")
                        anyHost() // In production, specify allowed origins
                        allowCredentials = true
                    }
                    
                    routing {
                        // Health check endpoint
                        get("/health") {
                            Log.d(TAG, "=== HTTP REQUEST: /health ===")
                            Log.d(TAG, "Remote address: ${call.request.local.remoteHost}")
                            Log.d(TAG, "User agent: ${call.request.headers["User-Agent"] ?: "unknown"}")
                            
                            val status = buildJsonObject {
                                put("status", "healthy")
                                put("service", SERVICE_NAME)
                                put("version", VERSION)
                                put("uptime", System.currentTimeMillis())
                                put("sessions", activeSessions.size)
                                put("ip", getLocalIpAddress())
                            }
                            call.respondText(
                                status.toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.OK
                            )
                            
                            Log.d(TAG, "=== HTTP RESPONSE: /health ===")
                            Log.d(TAG, "Status: 200 OK")
                            Log.d(TAG, "Response length: ${status.toString().length} bytes")
                        }
                        
                        // Session info endpoint
                        get("/sessions") {
                            Log.d(TAG, "=== HTTP REQUEST: /sessions ===")
                            Log.d(TAG, "Remote address: ${call.request.local.remoteHost}")
                            Log.d(TAG, "Current sessions: ${activeSessions.size}")
                            
                            val sessions = buildJsonArray {
                                activeSessions.forEach { (id, info) ->
                                    add(buildJsonObject {
                                        put("id", id)
                                        put("client", info.clientInfo)
                                        put("duration", System.currentTimeMillis() - info.startTime)
                                    })
                                }
                            }
                            call.respondText(
                                sessions.toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.OK
                            )
                            
                            Log.d(TAG, "=== HTTP RESPONSE: /sessions ===")
                            Log.d(TAG, "Status: 200 OK")
                            Log.d(TAG, "Sessions returned: ${activeSessions.size}")
                            Log.d(TAG, "Response length: ${sessions.toString().length} bytes")
                        }
                        
                        // Main MCP SSE endpoint
                        sse("/sse") {
                            Log.i(TAG, "=== SSE CONNECTION ===")
                            Log.i(TAG, "New SSE connection established")
                            Log.i(TAG, "Remote address: ${call.request.local.remoteHost}")
                            Log.i(TAG, "User agent: ${call.request.headers["User-Agent"] ?: "unknown"}")
                            handleMcpSession(this)
                        }
                        
                        // Alternative MCP endpoint for compatibility
                        mcp("/mcp") {
                            createMcpServer()
                        }
                    }
                }.start(wait = false)
                
                val ipAddress = getLocalIpAddress()
                Log.i(TAG, "======================================")
                Log.i(TAG, "=== FULL MCP SERVER STARTED ===")
                Log.i(TAG, "======================================")
                Log.i(TAG, "Server: $SERVICE_NAME v$VERSION")
                Log.i(TAG, "Address: http://$ipAddress:$PORT")
                Log.i(TAG, "SSE endpoint: http://$ipAddress:$PORT/sse")
                Log.i(TAG, "Health check: http://$ipAddress:$PORT/health")
                Log.i(TAG, "Sessions endpoint: http://$ipAddress:$PORT/sessions")
                Log.i(TAG, "MCP endpoint: http://$ipAddress:$PORT/mcp")
                Log.i(TAG, "Start time: ${System.currentTimeMillis()}")
                Log.i(TAG, "======================================")
                
                
            } catch (e: Exception) {
                Log.e(TAG, "======================================")
                Log.e(TAG, "=== SERVER START FAILED ===")
                Log.e(TAG, "======================================")
                Log.e(TAG, "Error: ${e.message}")
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "Time: ${System.currentTimeMillis()}")
                Log.e(TAG, "======================================")
                Log.e(TAG, "Full stack trace:", e)
            }
        }
    }
    
    private suspend fun handleMcpSession(session: ServerSSESession) {
        val sessionId = generateSessionId()
        val clientName = "Unknown" // Headers not directly accessible in SSE session
        val clientVersion = "Unknown"
        
        val sessionInfo = SessionInfo(
            id = sessionId,
            clientInfo = "$clientName/$clientVersion"
        )
        
        activeSessions[sessionId] = sessionInfo
        Log.i(TAG, "=== NEW MCP SESSION ===")
        Log.i(TAG, "Session ID: $sessionId")
        Log.i(TAG, "Client: $clientName/$clientVersion")
        Log.i(TAG, "Time: ${System.currentTimeMillis()}")
        Log.i(TAG, "Active sessions: ${activeSessions.size}")
        
        try {
            // Send initialization event
            session.send("event: initialize\n")
            session.send("data: ${createInitializeResponse()}\n\n")
            
            // Keep connection alive and handle incoming messages
            while (true) {
                // This would normally handle incoming SSE messages
                // The kotlin-sdk handles this internally
                kotlinx.coroutines.delay(30000) // Keep-alive ping every 30 seconds
                session.send("event: ping\n")
                session.send("data: {\"type\":\"ping\",\"timestamp\":${System.currentTimeMillis()}}\n\n")
            }
        } finally {
            activeSessions.remove(sessionId)
            val duration = System.currentTimeMillis() - sessionInfo.startTime
            Log.i(TAG, "=== MCP SESSION ENDED ===")
            Log.i(TAG, "Session ID: $sessionId")
            Log.i(TAG, "Duration: ${duration}ms")
            Log.i(TAG, "Remaining sessions: ${activeSessions.size}")
        }
    }
    
    private fun createMcpServer(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = SERVICE_NAME,
                version = VERSION
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    resources = ServerCapabilities.Resources(
                        subscribe = true,
                        listChanged = true
                    ),
                    prompts = ServerCapabilities.Prompts(
                        listChanged = true
                    ),
                    // logging capability can be added when available in SDK
                )
            )
        )
        
        // Add tools with full parameter schemas
        server.addTool(
            name = "get_chats",
            description = "Retrieves chats/conversations with optional filtering. Parameters: roomIds (optional, comma-separated), isLowPriority (optional, 0/1), isArchived (optional, 0/1), isUnread (optional, 0/1), showInAllChats (optional, 0/1), limit (optional, default 100), offset (optional, default 0). Returns formatted text with complete chat information and pagination details.",
        ) { request ->
            context.contentResolver.handleGetChats(request)
        }
        
        
        server.addTool(
            name = "get_contacts",
            description = "Retrieves contacts/senders with optional filtering. Parameters: senderIds (optional, comma-separated), roomIds (optional, comma-separated), query (optional, full-text search), limit (optional, default 100), offset (optional, default 0). Returns contact details including display names, protocols, room memberships, and pagination details.",
        ) { request ->
            context.contentResolver.handleGetContacts(request)
        }
        
        server.addTool(
            name = "send_message",
            description = "Send a text message to a specific chat room. Requires room_id (the Matrix room ID like !roomId:server.com) and text (the message content). Returns success/failure status.",
        ) { request ->
            context.contentResolver.handleSendMessage(request)
        }
        
        server.addTool(
            name = "get_messages",
            description = "Get messages from chats with optional filtering. Parameters: roomIds (optional, comma-separated to filter specific rooms), senderId (optional, filter by sender), query (optional, full-text search), contextBefore (optional, number), contextAfter (optional, number), openAtUnread (optional, boolean), limit (optional, default 100), offset (optional, default 0). Returns formatted messages with sender info, timestamps, content, reactions, and pagination details.",
        ) { request ->
            context.contentResolver.handleGetMessages(request)
        }
        
        // Add resources
        server.addResource(
            uri = "beeper://chats",
            name = "Chat List",
            description = "List of all Beeper chats",
            mimeType = "application/json"
        ) { request ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = request.uri,
                        text = getChatListResource(),
                        mimeType = "application/json"
                    )
                )
            )
        }
        
        
        // Add prompts
        server.addPrompt(
            name = "summarize_chats",
            description = "Generate a summary of recent chat activity",
            arguments = listOf(
                PromptArgument(
                    name = "time_range",
                    description = "Time range for summary (e.g., '1h', '24h', '7d')",
                    required = false
                )
            )
        ) { request ->
            GetPromptResult(
                description = "Summary of recent chat activity",
                messages = listOf(
                    PromptMessage(
                        role = Role.user,
                        content = TextContent(
                            text = generateChatSummaryPrompt(request.arguments)
                        )
                    )
                )
            )
        }
        
        return server
    }
    
    private fun createInitializeResponse(): String {
        return buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {
                    put("listChanged", true)
                })
                put("resources", buildJsonObject {
                    put("subscribe", true)
                    put("listChanged", true)
                })
                put("prompts", buildJsonObject {
                    put("listChanged", true)
                })
                put("logging", buildJsonObject {})
            })
            put("serverInfo", buildJsonObject {
                put("name", SERVICE_NAME)
                put("version", VERSION)
            })
        }.toString()
    }

    private fun getChatListResource(): String {
        return try {
            val uri = "content://$BEEPER_AUTHORITY/chats".toUri()
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            
            val chats = buildJsonArray {
                cursor?.use {
                    val roomIdIdx = it.getColumnIndex("roomId")
                    val titleIdx = it.getColumnIndex("title")
                    val unreadIdx = it.getColumnIndex("unreadCount")
                    val timestampIdx = it.getColumnIndex("timestamp")
                    
                    while (it.moveToNext()) {
                        add(buildJsonObject {
                            put("roomId", it.getString(roomIdIdx) ?: "")
                            put("title", it.getString(titleIdx) ?: "")
                            put("unreadCount", it.getInt(unreadIdx))
                            put("timestamp", it.getLong(timestampIdx))
                        })
                    }
                }
            }
            
            chats.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chat list resource", e)
            "[]"
        }
    }
    
    
    private fun generateChatSummaryPrompt(arguments: Map<String, String>?): String {
        val timeRange = arguments?.get("time_range") ?: "24h"
        return """
            Please summarize the chat activity for the last $timeRange.
            Focus on:
            - Number of active chats
            - Unread message distribution
            - Most active conversations
            - Key topics discussed
        """.trimIndent()
    }
    
    private fun generateSessionId(): String {
        return "session_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }
    
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                            return address.hostAddress ?: "unknown"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return "127.0.0.1"
    }
    
    
    
    fun stop() {
        try {
            // Clear active sessions
            activeSessions.clear()
            
            
            // Stop Ktor server
            ktorServer?.stop()
            
            Log.i(TAG, "======================================")
            Log.i(TAG, "=== FULL MCP SERVER STOPPED ===")
            Log.i(TAG, "======================================")
            Log.i(TAG, "Server: $SERVICE_NAME v$VERSION")
            Log.i(TAG, "Stop time: ${System.currentTimeMillis()}")
            Log.i(TAG, "Sessions cleared: ${activeSessions.size}")
            Log.i(TAG, "======================================")
            
        } catch (e: Exception) {
            Log.e(TAG, "======================================")
            Log.e(TAG, "=== SERVER STOP ERROR ===")
            Log.e(TAG, "======================================")
            Log.e(TAG, "Error: ${e.message}")
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Time: ${System.currentTimeMillis()}")
            Log.e(TAG, "======================================")
            Log.e(TAG, "Full stack trace:", e)
        }
    }
}