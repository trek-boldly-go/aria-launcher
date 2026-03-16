// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

/**
 * MCP entity stubs — defined now for schema planning, NOT added to
 * AriaDatabase until MCP is implemented in Phase 8.
 */

/** A discovered MCP server instance. */
data class McpServer(
    val id: String,
    val serverName: String,
    val endpoint: String,
    val transportType: McpTransport,
    val isReachable: Boolean,
    val lastChecked: Long,
    val lastSuccessfulCall: Long?,
    val requiresAuth: Boolean,
    val networkScope: NetworkScope,
)

/** Cached tool manifest for a server. */
data class McpCapability(
    val id: String,
    val serverId: String,
    val toolName: String,
    val description: String,
    val inputSchemaJson: String,
    val outputSchemaJson: String?,
    val cachedAt: Long,
    val isAvailable: Boolean,
)

/** Execution log for debugging and rule trigger counts. */
data class McpExecution(
    val id: Long = 0,
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
