// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class SurfacePriority { ALWAYS_SHOW, BOOST, PIN_TO_DOCK }

@Serializable
sealed class RuleAction {

    @Serializable
    @SerialName("surface_app")
    data class SurfaceApp(
        val packageName: String,
        val priority: SurfacePriority,
    ) : RuleAction()

    @Serializable
    @SerialName("suppress_app")
    data class SuppressApp(
        val packageName: String,
    ) : RuleAction()

    @Serializable
    @SerialName("show_card")
    data class ShowCard(
        val cardType: String,
        val headline: String,
        val subtext: String?,
        val intentUri: String?,
    ) : RuleAction()

    @Serializable
    @SerialName("open_app")
    data class OpenApp(
        val packageName: String,
        val intentUri: String? = null,
    ) : RuleAction()

    @Serializable
    @SerialName("send_message")
    data class SendMessage(
        val contactName: String,
        val messageTemplate: String, // supports {time}, {location} tokens
    ) : RuleAction()

    @Serializable
    @SerialName("set_space")
    data class SetSpace(
        val spaceName: String,
    ) : RuleAction()

    @Serializable
    @SerialName("run_skill")
    data class RunSkill(
        val skillId: String,
        val params: Map<String, String>,
    ) : RuleAction()

    @Serializable
    @SerialName("fetch_data")
    data class FetchData(
        val serverId: String,
        val toolName: String,
        val params: Map<String, String>,
        val onSuccess: RuleAction,
        val onFailure: RuleAction? = null, // MCP Seam 3 — no-op until Phase 8
    ) : RuleAction()
}
