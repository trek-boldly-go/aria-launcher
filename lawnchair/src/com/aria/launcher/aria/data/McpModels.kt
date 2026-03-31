// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// MCP entity stubs — entity annotations are present for schema planning,
// but these classes are NOT added to AriaDatabase until Phase 8.

/** A discovered MCP server instance. */
@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey val id: String,
    val serverName: String,
    val endpoint: String,
    val transportType: McpTransport,
    val isReachable: Boolean,
    val lastChecked: Long,
    val lastSuccessfulCall: Long?,
    val requiresAuth: Boolean,
    val authToken: String?,
    val networkScope: NetworkScope,
)

/** Cached tool manifest for a server. */
@Entity(tableName = "mcp_capabilities")
data class McpCapability(
    @PrimaryKey val id: String,
    val serverId: String,
    val toolName: String,
    val description: String,
    val inputSchemaJson: String,
    val outputSchemaJson: String?,
    val cachedAt: Long,
    val isAvailable: Boolean,
)

/** Execution log for debugging and rule trigger counts. */
@Entity(tableName = "mcp_executions")
data class McpExecution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val toolName: String,
    val inputJson: String,
    val outputJson: String?,
    val executedAt: Long,
    val durationMs: Long,
    val triggeredBy: ExecutionTrigger,
    val success: Boolean,
    val errorMessage: String?,
)

enum class McpTransport { HTTP_SSE, STDIO }
enum class NetworkScope { HOME_WIFI, ANY, VPN_ONLY }
enum class ExecutionTrigger { CHAT, RULE, SKILL, PROACTIVE }
