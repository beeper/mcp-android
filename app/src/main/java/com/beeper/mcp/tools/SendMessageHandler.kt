package com.beeper.mcp.tools

import android.content.ContentResolver
import android.content.ContentValues
import androidx.core.net.toUri
import android.util.Log
import com.beeper.mcp.BEEPER_AUTHORITY
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "SendMessageHandler"

suspend fun ContentResolver.handleSendMessage(request: CallToolRequest): CallToolResult {
    val startTime = System.currentTimeMillis()
    return try {
        val roomId = request.arguments?.get("room_id")?.jsonPrimitive?.content
        val text = request.arguments?.get("text")?.jsonPrimitive?.content
        
        if (roomId == null || text == null) {
            return CallToolResult(
                content = listOf(TextContent(text = "Error: Both 'room_id' and 'text' parameters are required")),
                isError = true
            ).also {
                Log.e(TAG, "=== TOOL ERROR: send_message ===")
                Log.e(TAG, "Error: Missing required parameters - need both room_id and text")
            }
        }
        
        Log.i(TAG, "=== TOOL REQUEST: send_message ===")
        Log.i(TAG, "Parameters: roomId=$roomId, text length=${text.length}")
        Log.i(TAG, "Start time: $startTime")
        
        val uriBuilder = "content://$BEEPER_AUTHORITY/messages".toUri().buildUpon()
        uriBuilder.appendQueryParameter("roomId", roomId)
        uriBuilder.appendQueryParameter("text", text)
        
        val uri = uriBuilder.build()
        val contentValues = ContentValues()
        val result = insert(uri, contentValues)
        
        val responseText = if (result != null) {
            "Message sent successfully to room: $roomId\n\nMessage content: $text"
        } else {
            "Failed to send message to room: $roomId\n\nThis could be due to:\n  - Invalid room ID\n  - Network connectivity issues\n  - Insufficient permissions\n  - Beeper app not running"
        }
        
        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "=== TOOL RESPONSE: send_message ===")
        Log.i(TAG, "Duration: ${duration}ms")
        Log.i(TAG, "Success: ${result != null}")
        Log.i(TAG, "Status: ${if (result != null) "SUCCESS" else "FAILED"}")
        
        CallToolResult(
            content = listOf(TextContent(text = responseText)),
            isError = result == null
        )
        
    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - startTime
        Log.e(TAG, "=== TOOL ERROR: send_message ===")
        Log.e(TAG, "Duration: ${duration}ms")
        Log.e(TAG, "Error: ${e.message}")
        Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
        Log.e(TAG, "Stack trace:", e)
        CallToolResult(
            content = listOf(TextContent(text = "Error sending message: ${e.message}")),
            isError = true
        )
    }
}