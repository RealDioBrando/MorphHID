package dev.morphhid.core.profile

import kotlinx.serialization.Serializable

enum class AgentScope { READ_ONLY, INVOKE_ONLY, FULL }

@Serializable
data class AgentSpec(
    val defaultScope: AgentScope = AgentScope.INVOKE_ONLY,
    /** Control ids that require extra confirmation / are denied to agents by default. */
    val sensitiveControls: List<String> = emptyList(),
    val rateLimitPerMinute: Int = 240,
    val requireConfirmationForSensitive: Boolean = true,
)