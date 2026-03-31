// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.llm

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * Authentication configuration for reverse-proxy-protected LLM endpoints.
 * Supports common auth schemes used in homelab setups (Traefik, Nginx, Caddy,
 * Cloudflare Tunnel, etc.).
 */
@Serializable
sealed class AuthConfig {

    @Serializable
    @SerialName("none")
    data object None : AuthConfig()

    @Serializable
    @SerialName("basic")
    data class Basic(val username: String, val password: String) : AuthConfig()

    @Serializable
    @SerialName("bearer")
    data class BearerToken(val token: String) : AuthConfig()

    /** Arbitrary header key-value pairs. Supports multi-header schemes like Cloudflare Access. */
    @Serializable
    @SerialName("headers")
    data class CustomHeaders(val headers: Map<String, String>) : AuthConfig()

    /** Serialize to JSON for DataStore persistence. */
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        /** Deserialize from DataStore JSON string. Returns [None] for null/blank/malformed input. */
        fun decode(raw: String?): AuthConfig {
            if (raw.isNullOrBlank()) return None
            return try {
                json.decodeFromString(serializer(), raw)
            } catch (_: Exception) {
                None
            }
        }
    }
}

/** Apply this [AuthConfig] to an OkHttp [Request.Builder]. */
fun Request.Builder.applyAuth(authConfig: AuthConfig): Request.Builder = apply {
    when (authConfig) {
        is AuthConfig.None -> { /* no-op */ }

        is AuthConfig.Basic -> {
            val credentials = Base64.getEncoder().encodeToString(
                "${authConfig.username}:${authConfig.password}".toByteArray(),
            )
            header("Authorization", "Basic $credentials")
        }

        is AuthConfig.BearerToken -> {
            header("Authorization", "Bearer ${authConfig.token}")
        }

        is AuthConfig.CustomHeaders -> {
            for ((name, value) in authConfig.headers) {
                header(name, value)
            }
        }
    }
}
