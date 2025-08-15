package com.beeper.mcp.tools

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.serialization.json.jsonPrimitive
import androidx.core.net.toUri
import com.beeper.mcp.BEEPER_AUTHORITY

private const val TAG = "GetContactsHandler"

fun ContentResolver.handleGetContacts(request: CallToolRequest): CallToolResult {
    val startTime = System.currentTimeMillis()
    return try {
        val query = request.arguments.get("query")?.jsonPrimitive?.content
        val roomIds = request.arguments.get("roomIds")?.jsonPrimitive?.content
        val senderIds = request.arguments.get("senderIds")?.jsonPrimitive?.content
        val limit = request.arguments.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 100
        val offset = request.arguments.get("offset")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        
        Log.i(TAG, "=== TOOL REQUEST: get_contacts ===")
        Log.i(TAG, "Parameters: query=$query, roomIds=$roomIds, senderIds=$senderIds, limit=$limit, offset=$offset")
        Log.i(TAG, "Start time: $startTime")
        
        // Build common parameter string for both count and query
        val params = buildString {
            query?.let { append("query=${Uri.encode(it)}&") }
            roomIds?.let { append("roomIds=${Uri.encode(it)}&") }
            senderIds?.let { append("senderIds=${Uri.encode(it)}&") }
        }.trimEnd('&')
        
        // 1. Get paginated results
        val paginationParams = if (params.isNotEmpty()) {
            "$params&limit=$limit&offset=$offset"
        } else {
            "limit=$limit&offset=$offset"
        }
        
        val queryUri = "content://$BEEPER_AUTHORITY/contacts?$paginationParams".toUri()
        val contacts = mutableListOf<Map<String, Any?>>()
        
        query(queryUri, null, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex("id")
            val roomIdsIdx = cursor.getColumnIndex("roomIds")
            val displayNameIdx = cursor.getColumnIndex("displayName")
            val contactDisplayNameIdx = cursor.getColumnIndex("contactDisplayName")
            val linkedContactIdIdx = cursor.getColumnIndex("linkedContactId")
            val itsMeIdx = cursor.getColumnIndex("itsMe")
            val protocolIdx = cursor.getColumnIndex("protocol")
            
            while (cursor.moveToNext()) {
                val contactData = mapOf(
                    "id" to cursor.getString(idIdx),
                    "roomIds" to cursor.getString(roomIdsIdx),
                    "displayName" to cursor.getString(displayNameIdx),
                    "contactDisplayName" to cursor.getString(contactDisplayNameIdx),
                    "linkedContactId" to cursor.getString(linkedContactIdIdx),
                    "itsMe" to (cursor.getInt(itsMeIdx) == 1),
                    "protocol" to cursor.getString(protocolIdx),
                )
                contacts.add(contactData)
            }
        }
        
        // 2. Get total count only if we got a full page (indicating more results may exist)
        var totalCount: Int? = null
        if (contacts.size == limit) {
            val countUri = "content://$BEEPER_AUTHORITY/contacts/count".let { baseUri ->
                if (params.isNotEmpty()) "$baseUri?$params" else baseUri
            }.toUri()
            
            totalCount = query(countUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val countIdx = cursor.getColumnIndex("count")
                    if (countIdx >= 0) cursor.getInt(countIdx) else 0
                } else 0
            } ?: 0
            
            Log.i(TAG, "Total contacts found: $totalCount")
        }
        
        // 3. Group contacts by unique contact (same ID) and format paginated results
        val result = buildString {
            when {
                query != null && roomIds != null -> {
                    appendLine("Contact Search Results for: \"$query\" in rooms: $roomIds")
                }
                query != null -> {
                    appendLine("Contact Search Results for: \"$query\"")
                }
                roomIds != null -> {
                    appendLine("Contacts in Rooms: $roomIds")
                }
                senderIds != null -> {
                    appendLine("Contact Details for Senders: $senderIds")
                }
                else -> {
                    appendLine("All Contacts")
                }
            }
            appendLine("=" .repeat(50))
            
            if (contacts.isNotEmpty()) {
                // Group contacts by unique contact ID and display name
                val contactMap = mutableMapOf<String, MutableList<String>>()
                
                contacts.forEach { contactData ->
                    val contactId = contactData["id"] as? String ?: "unknown"
                    val displayName = contactData["displayName"] as? String ?: "Unknown"
                    val contactDisplayName = contactData["contactDisplayName"] as? String ?: ""
                    val linkedContactId = contactData["linkedContactId"] as? String ?: ""
                    val itsMe = contactData["itsMe"] as? Boolean ?: false
                    val protocol = contactData["protocol"] as? String ?: "unknown"
                    val roomIds = contactData["roomIds"] as? String ?: ""
                    
                    val key = "$contactId|$displayName|$contactDisplayName|$linkedContactId|$itsMe|$protocol"
                    
                    if (!contactMap.containsKey(key)) {
                        contactMap[key] = mutableListOf()
                    }
                    
                    // Split comma-separated room IDs and add them to the list
                    if (roomIds.isNotEmpty()) {
                        val roomList = roomIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        contactMap[key]?.addAll(roomList)
                    }
                }
                
                contactMap.entries.forEachIndexed { index, (key, roomList) ->
                    val parts = key.split("|")
                    val contactId = parts[0]
                    val displayName = parts[1]
                    val contactDisplayName = parts[2]
                    val linkedContactId = parts[3]
                    val itsMe = parts[4].toBoolean()
                    val protocol = parts[5]

                    appendLine()
                    appendLine("Contact #${index + 1}:")
                    val nameToShow = contactDisplayName.ifEmpty { displayName }
                    appendLine("  Name: $nameToShow${if (itsMe) " (You)" else ""}")
                    appendLine("  ID: $contactId")
                    appendLine("  Protocol: $protocol")
                    if (linkedContactId.isNotEmpty()) {
                        appendLine("  Linked Contact: $linkedContactId")
                    }
                    appendLine("  Present in ${roomList.size} room${if (roomList.size != 1) "s" else ""}:")
                    roomList.take(3).forEach { roomId ->
                        appendLine("    - $roomId")
                    }
                    if (roomList.size > 3) {
                        appendLine("    ... and ${roomList.size - 3} more rooms")
                    }
                }
                
                appendLine()
                if (totalCount != null) {
                    appendLine("Showing ${offset + 1}-${offset + contacts.size} of $totalCount total contact instances")
                    appendLine("Unique contacts in this page: ${contactMap.size}")
                    if (offset + contacts.size < totalCount) {
                        appendLine("Use offset=${offset + contacts.size} to get the next page")
                    }
                } else {
                    appendLine("Showing ${contacts.size} contact instance${if (contacts.size != 1) "s" else ""} (page complete)")
                    appendLine("Unique contacts in this page: ${contactMap.size}")
                }
            } else {
                when {
                    query != null && roomIds != null -> {
                        appendLine("\nNo contacts found matching \"$query\" in the specified rooms")
                    }
                    query != null -> {
                        appendLine("\nNo contacts found matching \"$query\"")
                    }
                    roomIds != null -> {
                        appendLine("\nNo contacts found in the specified rooms")
                    }
                    senderIds != null -> {
                        appendLine("\nNo contacts found for the specified sender IDs")
                    }
                    else -> {
                        appendLine("\nNo contacts found")
                    }
                }
                if (offset > 0) {
                    appendLine("This page is empty - try a smaller offset value.")
                }
            }
        }
        
        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "=== TOOL RESPONSE: get_contacts ===")
        Log.i(TAG, "Duration: ${duration}ms")
        Log.i(TAG, "Total count: ${totalCount ?: "not fetched"}")
        Log.i(TAG, "Contacts retrieved: ${contacts.size}")
        Log.i(TAG, "Result length: ${result.length} characters")
        Log.i(TAG, "Status: SUCCESS")
        
        CallToolResult(
            content = listOf(TextContent(text = result)),
            isError = false
        )
        
    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - startTime
        Log.e(TAG, "=== TOOL ERROR: get_contacts ===")
        Log.e(TAG, "Duration: ${duration}ms")
        Log.e(TAG, "Error: ${e.message}")
        Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
        Log.e(TAG, "Stack trace:", e)
        CallToolResult(
            content = listOf(TextContent(text = "Error searching contacts: ${e.message}")),
            isError = true
        )
    }
}