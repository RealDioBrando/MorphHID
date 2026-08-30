package dev.morphhid.core.control

import dev.morphhid.core.profile.AgentScope
import dev.morphhid.core.profile.Profile

enum class ActionKind { READ, INVOKE_MACRO, ACTUATE_CONTROL, EMERGENCY }

/**
 * Per-profile access policy. Humans are always allowed; agents are gated by
 * the profile's declared scope, sensitive-control list and rate limit.
 */
class AccessPolicy(
    private val profile: Profile?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val limiters = HashMap<String, RateLimiter>()

    fun check(actor: Actor, kind: ActionKind, target: String?, meter: Boolean = true): OpResult {
        if (actor is Actor.Human) return OpResult.Ok

        val agent = profile?.agent
        val scope = agent?.defaultScope ?: AgentScope.INVOKE_ONLY

        val denial: OpResult? = when (kind) {
            ActionKind.READ -> null
            ActionKind.EMERGENCY -> null
            ActionKind.INVOKE_MACRO -> if (scope == AgentScope.READ_ONLY) {
                OpResult.Denied("agent scope is read-only")
            } else null
            ActionKind.ACTUATE_CONTROL -> when (scope) {
                AgentScope.READ_ONLY -> OpResult.Denied("agent scope is read-only")
                AgentScope.INVOKE_ONLY -> OpResult.Denied("agent scope is invoke-only; direct control actuation requires FULL")
                AgentScope.FULL -> null
            }
        }
        if (denial != null) return denial

        if (kind == ActionKind.ACTUATE_CONTROL && target != null) {
            val sensitive = agent?.sensitiveControls.orEmpty()
            if (sensitive.any { target == it || target.startsWith("$it.") }) {
                return OpResult.Denied("control '$target' is marked sensitive")
            }
        }

        if (!meter) return OpResult.Ok

        val rate = agent?.rateLimitPerMinute ?: 240
        val limiter = synchronized(limiters) {
            limiters.getOrPut(actor.id) { RateLimiter(capacity = rate, perMinute = rate, clock = clock) }
        }
        if (!limiter.tryConsume()) {
            return OpResult.Denied("rate limit exceeded (${rate}/min for agent '${actor.id}')")
        }
        return OpResult.Ok
    }
}

/** Simple token bucket. */
class RateLimiter(
    private val capacity: Int,
    private val perMinute: Int,
    private val clock: () -> Long,
) {
    private var tokens = capacity.toDouble()
    private var lastMs = clock()

    fun tryConsume(): Boolean {
        val now = clock()
        val elapsed = now - lastMs
        if (elapsed > 0) {
            tokens = minOf(capacity.toDouble(), tokens + elapsed / 60000.0 * perMinute)
            lastMs = now
        }
        if (tokens >= 1.0) {
            tokens -= 1.0
            return true
        }
        return false
    }
}