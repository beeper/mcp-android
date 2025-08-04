package com.beeper.mcp.tools

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.serialization.json.jsonPrimitive
import androidx.core.net.toUri

private const val TAG = "GetMessagesHandler"

fun ContentResolver.handleGetMessages(request: CallToolRequest): CallToolResult {
    val startTime = System.currentTimeMillis()
    return try {
        val roomIds = request.arguments.get("roomIds")?.jsonPrimitive?.content
        val senderId = request.arguments.get("senderId")?.jsonPrimitive?.content
        val query = request.arguments.get("query")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
        val contextBefore =
            request.arguments.get("contextBefore")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val contextAfter =
            request.arguments.get("contextAfter")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val openAtUnread =
            request.arguments.get("openAtUnread")?.jsonPrimitive?.content?.toBoolean() ?: false
        val limit = request.arguments.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 100
        val offset = request.arguments.get("offset")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        Log.i(TAG, "=== TOOL REQUEST: get_messages ===")
        Log.i(
            TAG,
            "Parameters: roomIds=$roomIds, senderId=$senderId, query=$query, contextBefore=$contextBefore, contextAfter=$contextAfter, openAtUnread=$openAtUnread, limit=$limit, offset=$offset"
        )
        Log.i(TAG, "Start time: $startTime")

        // Build common parameter string for both count and query
        val params = buildString {
            roomIds?.let { append("roomIds=${Uri.encode(it)}&") }
            senderId?.let { append("senderId=${Uri.encode(it)}&") }
            query?.let { append("query=${Uri.encode(it)}&") }
            if (contextBefore > 0) append("contextBefore=$contextBefore&")
            if (contextAfter > 0) append("contextAfter=$contextAfter&")
            if (openAtUnread) append("openAtUnread=true&")
        }.trimEnd('&')

        // 1. Get paginated results
        val paginationParams = if (params.isNotEmpty()) {
            "$params&limit=$limit&offset=$offset"
        } else {
            "limit=$limit&offset=$offset"
        }

        val queryUri = "content://com.beeper.api/messages?$paginationParams".toUri()
        val messages = mutableListOf<Map<String, Any?>>()
        var pagingOffset: Int? = null
        var lastRead: String? = null

        query(queryUri, null, null, null, null)?.use { cursor ->
            // Column indices
            val roomIdIdx = cursor.getColumnIndex("roomId")
            val originalIdIdx = cursor.getColumnIndex("originalId")
            val senderContactIdIdx = cursor.getColumnIndex("senderContactId")
            val timestampIdx = cursor.getColumnIndex("timestamp")
            val isSentByMeIdx = cursor.getColumnIndex("isSentByMe")
            val isDeletedIdx = cursor.getColumnIndex("isDeleted")
            val typeIdx = cursor.getColumnIndex("type")
            val textContentIdx = cursor.getColumnIndex("text_content")
            val displayNameIdx = cursor.getColumnIndex("displayName")
            val isSearchMatchIdx = cursor.getColumnIndex("is_search_match")
            val reactionsIdx = cursor.getColumnIndex("reactions")

            while (cursor.moveToNext()) {
                val messageData = mapOf(
                    "messageId" to cursor.getString(originalIdIdx),
                    "roomId" to cursor.getString(roomIdIdx),
                    "senderId" to cursor.getString(senderContactIdIdx),
                    "displayName" to cursor.getString(displayNameIdx),
                    "timestamp" to cursor.getLong(timestampIdx),
                    "isSentByMe" to (cursor.getInt(isSentByMeIdx) == 1),
                    "isDeleted" to (cursor.getInt(isDeletedIdx) == 1),
                    "type" to cursor.getString(typeIdx),
                    "textContent" to cursor.getString(textContentIdx),
                    "content" to cursor.getString(textContentIdx),
                    "isSearchMatch" to if (isSearchMatchIdx >= 0) (cursor.getInt(isSearchMatchIdx) == 1) else true,
                    "reactions" to cursor.getString(reactionsIdx)
                )
                messages.add(messageData)
            }

            // Extract additional fields when openAtUnread is used
            if (openAtUnread && messages.isNotEmpty()) {
                if (cursor.moveToFirst()) {
                    val pagingOffsetIdx = cursor.getColumnIndex("paging_offset")
                    val lastReadIdx = cursor.getColumnIndex("last_read")
                    if (pagingOffsetIdx >= 0) pagingOffset = cursor.getInt(pagingOffsetIdx)
                    if (lastReadIdx >= 0) lastRead = cursor.getString(lastReadIdx)
                }
            }
        }

        // 2. Get total count only if we got a full page (indicating more results may exist)
        var totalCount: Int? = null
        if (messages.size == limit) {
            val countUri = "content://com.beeper.api/messages/count".let { baseUri ->
                if (params.isNotEmpty()) "$baseUri?$params" else baseUri
            }.toUri()

            totalCount = query(countUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val countIdx = cursor.getColumnIndex("count")
                    if (countIdx >= 0) cursor.getInt(countIdx) else 0
                } else 0
            } ?: 0

            Log.i(TAG, "Total messages found: $totalCount")
        }

        // 3. Format and return paginated results
        val result = buildString {
            when {
                query != null -> appendLine("Message Search Results for: \"$query\"")
                roomIds != null -> appendLine("Messages in Rooms: $roomIds")
                senderId != null -> appendLine("Messages from Sender: $senderId")
                else -> appendLine("Messages:")
            }
            if (roomIds != null && query == null && senderId == null) appendLine("Filtered to rooms: $roomIds")
            if (senderId != null && query == null) appendLine("Filtered to sender: $senderId")
            appendLine("=".repeat(60))

            if (messages.isNotEmpty()) {
                // Group messages by room for better readability when showing multiple rooms
                var currentRoomId: String? = null
                val messagesInRoom = mutableListOf<String>()

                messages.forEach { messageData ->
                    val roomId = messageData["roomId"] as? String ?: "unknown"
                    val displayName = messageData["displayName"] as? String ?: "Unknown"
                    val timestamp = messageData["timestamp"] as? Long ?: 0L
                    val content = messageData["content"] as? String ?: ""
                    val type = messageData["type"] as? String ?: "TEXT"
                    val isSentByMe = messageData["isSentByMe"] as? Boolean ?: false
                    val isDeleted = messageData["isDeleted"] as? Boolean ?: false
                    val isSearchMatch = messageData["isSearchMatch"] as? Boolean ?: true
                    val reactions = messageData["reactions"] as? String ?: ""

                    // Group messages by room for better readability when multiple rooms
                    if (roomId != currentRoomId && (roomIds == null || roomIds.contains(","))) {
                        if (currentRoomId != null && messagesInRoom.isNotEmpty()) {
                            messagesInRoom.forEach { msg -> appendLine(msg) }
                            appendLine()
                        }
                        currentRoomId = roomId
                        messagesInRoom.clear()
                        appendLine("\n📍 Room: $roomId")
                        appendLine("=".repeat(50))
                    }

                    val msgBuilder = StringBuilder()

                    if (!isSearchMatch && (contextBefore > 0 || contextAfter > 0)) {
                        msgBuilder.appendLine("  [Context]")
                    }

                    val prefix = if (query != null && isSearchMatch) "🔍 " else ""
                    msgBuilder.appendLine("  $prefix[${formatTimestamp(timestamp)}] $displayName${if (isSentByMe) " (You)" else ""}: ")

                    when {
                        isDeleted -> msgBuilder.appendLine("      [Message deleted]")
                        type == "TEXT" && content.isNotEmpty() -> {
                            content.lines().forEach { line ->
                                msgBuilder.appendLine("      $line")
                            }
                        }

                        else -> {
                            msgBuilder.appendLine("      [$type message]")
                        }
                    }

                    if (reactions.isNotEmpty()) {
                        val reactionList = reactions.split(",").map { reaction ->
                            val parts = reaction.split("|")
                            if (parts.size >= 3) "${parts[0]} (${if (parts[2] == "1") "You" else "Someone"})" else reaction
                        }
                        msgBuilder.appendLine("      Reactions: ${reactionList.joinToString(", ")}")
                    }

                    if (roomIds == null || roomIds.contains(",")) {
                        messagesInRoom.add(msgBuilder.toString())
                    } else {
                        appendLine(msgBuilder.toString())
                    }
                }

                // Print remaining messages if grouped by room
                if (messagesInRoom.isNotEmpty()) {
                    messagesInRoom.forEach { msg -> appendLine(msg) }
                }

                appendLine("\n" + "=".repeat(60))
                if (totalCount != null) {
                    appendLine("Showing ${offset + 1}-${offset + messages.size} of $totalCount total messages")
                    if (offset + messages.size < totalCount) {
                        appendLine("Use offset=${offset + messages.size} to get the next page")
                    }
                } else {
                    appendLine("Showing ${messages.size} message${if (messages.size != 1) "s" else ""} (page complete)")
                }

                // Add openAtUnread specific information
                if (openAtUnread) {
                    appendLine()
                    pagingOffset?.let { appendLine("Paging offset: $it") }
                    lastRead?.let { appendLine("Last read message: $it") }
                }
            } else {
                when {
                    query != null -> appendLine("\nNo messages found matching \"$query\"")
                    roomIds != null -> appendLine("\nNo messages found in the specified rooms")
                    senderId != null -> appendLine("\nNo messages found from the specified sender")
                    else -> appendLine("\nNo messages found")
                }
                if (offset > 0) {
                    appendLine("This page is empty - try a smaller offset value.")
                }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "=== TOOL RESPONSE: get_messages ===")
        Log.i(TAG, "Duration: ${duration}ms")
        Log.i(TAG, "Total count: ${totalCount ?: "not fetched"}")
        Log.i(TAG, "Messages retrieved: ${messages.size}")
        Log.i(TAG, "Result length: ${result.length} characters")
        Log.i(TAG, "Status: SUCCESS")

        CallToolResult(
            content = listOf(TextContent(text = result)),
            isError = false
        )

    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - startTime
        Log.e(TAG, "=== TOOL ERROR: get_messages ===")
        Log.e(TAG, "Duration: ${duration}ms")
        Log.e(TAG, "Error: ${e.message}")
        Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
        Log.e(TAG, "Stack trace:", e)
        CallToolResult(
            content = listOf(TextContent(text = "Error getting room messages: ${e.message}")),
            isError = true
        )
    }
}