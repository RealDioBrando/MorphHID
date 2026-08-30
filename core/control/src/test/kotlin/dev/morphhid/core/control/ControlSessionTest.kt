package dev.morphhid.core.control

import dev.morphhid.core.hid.CompiledHid
import dev.morphhid.core.hid.HidDescriptorCompiler
import dev.morphhid.core.profile.AgentScope
import dev.morphhid.core.profile.AgentSpec
import dev.morphhid.core.profile.DeviceSpec
import dev.morphhid.core.profile.HidCollectionSpec
import dev.morphhid.core.profile.HidSpec
import dev.morphhid.core.profile.MacroPolicy
import dev.morphhid.core.profile.MacroSpec
import dev.morphhid.core.profile.MacroStep
import dev.morphhid.core.profile.Profile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionTest {

    private class FakeTransport : HidTransport {
        override val state = MutableStateFlow(TransportState(phase = TransportPhase.CONNECTED))
        override val outputReports = MutableSharedFlow<OutputReport>()
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        var registeredCount = 0
        var disconnectCount = 0

        override suspend fun register(compiled: CompiledHid): Boolean {
            registeredCount++
            return true
        }

        override suspend fun connect(hostAddress: String): Boolean = true
        override suspend fun disconnect() { disconnectCount++ }
        override suspend fun unregister() {}
        override suspend fun sendReport(reportId: Int, payload: ByteArray): Boolean {
            sent += reportId to payload.copyOf()
            return true
        }
    }

    private fun profile(agentScope: AgentScope = AgentScope.INVOKE_ONLY): Profile = Profile(
        device = DeviceSpec(
            name = "Test",
            hid = HidSpec(
                collections = listOf(
                    HidCollectionSpec.Keyboard(),
                    HidCollectionSpec.Consumer(usages = listOf("mute", "playPause")),
                ),
            ),
        ),
        macros = mapOf(
            "tapMute" to MacroSpec(
                steps = listOf(MacroStep.Tap(key = "consumer.mute", holdMs = 20)),
                policy = MacroPolicy.RESTART,
            ),
            "typeApple" to MacroSpec(
                steps = listOf(MacroStep.Type(text = "apple", keyDelayMs = 10, jitterMs = 0)),
            ),
        ),
        agent = AgentSpec(
            defaultScope = agentScope,
            sensitiveControls = listOf("keyboard.winKey"),
            rateLimitPerMinute = 10_000,
        ),
    )

    @Test
    fun `actuate sends the right report`() = runTest {
        val transport = FakeTransport()
        val session = ControlSession(transport, scope = backgroundScope)
        session.activate(profile(), HidDescriptorCompiler().compile(profile()))

        val result = session.actuate(Actor.Human(), "keyboard.a", pressed = true)
        assertEquals(OpResult.Ok, result)
        assertEquals(1, transport.sent.size)
        assertEquals(1, transport.sent[0].first)
        assertEquals(0x04, transport.sent[0].second[2].toInt() and 0xFF)

        session.actuate(Actor.Human(), "keyboard.a", pressed = false)
        assertEquals(2, transport.sent.size)
        assertEquals(0, transport.sent[1].second[2].toInt() and 0xFF)
    }

    @Test
    fun `invoke-only agents cannot actuate but can run macros`() = runTest {
        val transport = FakeTransport()
        val session = ControlSession(transport, scope = backgroundScope)
        session.activate(profile(), HidDescriptorCompiler().compile(profile()))
        val agent = Actor.Agent(id = "test-agent", transport = "mcp")

        val denied = session.actuate(agent, "keyboard.a", pressed = true)
        assertTrue(denied is OpResult.Denied)
        assertEquals(0, transport.sent.size)

        val allowed = session.runMacro(agent, "tapMute")
        assertTrue(allowed is OpResult.Ok)
        advanceTimeBy(500)
        advanceUntilIdle()
        assertTrue(transport.sent.isNotEmpty())
        assertTrue(transport.sent.any { it.first == 2 && it.second.any { b -> b != 0.toByte() } })
    }

    @Test
    fun `full-scope agents can actuate but sensitive controls stay denied`() = runTest {
        val transport = FakeTransport()
        val session = ControlSession(transport, scope = backgroundScope)
        session.activate(profile(AgentScope.FULL), HidDescriptorCompiler().compile(profile(AgentScope.FULL)))
        val agent = Actor.Agent(id = "full-agent", transport = "mcp")

        assertTrue(session.actuate(agent, "keyboard.a", pressed = true) is OpResult.Ok)
        assertTrue(session.actuate(agent, "keyboard.winKey", pressed = true) is OpResult.Denied)
    }

    @Test
    fun `typing macro emits a press and release per character`() = runTest {
        val transport = FakeTransport()
        val session = ControlSession(transport, scope = backgroundScope)
        session.activate(profile(), HidDescriptorCompiler().compile(profile()))

        session.runMacro(Actor.Human(), "typeApple")
        advanceTimeBy(10_000)
        advanceUntilIdle()

        // 5 chars x (down + up) = 10 reports.
        assertEquals(10, transport.sent.size)
        // All sent to the keyboard report.
        assertTrue(transport.sent.all { it.first == 1 })
        // First report contains 'a'.
        assertEquals(0x04, transport.sent[0].second[2].toInt() and 0xFF)
        // Last report is empty (all keys released).
        assertEquals(0, transport.sent.last().second[2].toInt() and 0xFF)
    }

    @Test
    fun `emergency stop releases everything and is audited`() = runTest {
        val transport = FakeTransport()
        val audit = InMemoryAuditLog()
        val session = ControlSession(transport, audit, scope = backgroundScope)
        session.activate(profile(), HidDescriptorCompiler().compile(profile()))

        session.actuate(Actor.Human(), "keyboard.a", pressed = true)
        session.emergencyStop(Actor.Human())
        advanceUntilIdle()

        val last = transport.sent.last()
        assertEquals(0, last.second[2].toInt() and 0xFF)
        assertTrue(audit.recent().any { it.action == "emergencyStop" && it.result == "ok" })
    }

    @Test
    fun `unknown macro is an error`() = runTest {
        val transport = FakeTransport()
        val session = ControlSession(transport, scope = backgroundScope)
        session.activate(profile(), HidDescriptorCompiler().compile(profile()))
        val result = session.runMacro(Actor.Human(), "nope")
        assertTrue(result is OpResult.Error)
    }

    @Test
    fun `ui macros are serialized so typed characters never interleave`() = runTest {
        val transport = FakeTransport()
        val session = ControlSession(transport, scope = backgroundScope)
        session.activate(profile(), HidDescriptorCompiler().compile(profile()))

        session.runAdhoc(
            Actor.Human(), "a",
            listOf(MacroStep.Type(text = "aaaa", keyDelayMs = 10, jitterMs = 0)),
        )
        session.runAdhoc(
            Actor.Human(), "b",
            listOf(MacroStep.Type(text = "bbbb", keyDelayMs = 10, jitterMs = 0)),
        )
        advanceTimeBy(10_000)
        advanceUntilIdle()

        // Extract the pressed key usage from each keyboard report.
        val presses = transport.sent
            .filter { it.first == 1 }
            .map { it.second[2].toInt() and 0xFF }
            .filter { it != 0 }
            .map { ('a' + (it - 0x04)).toChar().toString() }
            .joinToString("")
        assertTrue(
            "interleaved: $presses",
            presses == "aaaabbbb" || presses == "bbbbaaaa",
        )
    }

    @Test
    fun `reactivating the same profile keeps the connection`() = runTest {
        val transport = FakeTransport()
        val session = ControlSession(transport, scope = backgroundScope)
        val profile = profile()
        val compiled = HidDescriptorCompiler().compile(profile)

        session.activate(profile, compiled)
        assertEquals(1, transport.registeredCount)
        session.connectHost("AA:BB:CC:DD:EE:FF")
        assertEquals(0, transport.disconnectCount)

        // Same fingerprint: no disconnect, no second registration.
        session.activate(profile, compiled)
        assertEquals(1, transport.registeredCount)
        assertEquals(0, transport.disconnectCount)
    }

    @Test
    fun `adhoc binding runs through the same engine`() = runTest {
        val transport = FakeTransport()
        val session = ControlSession(transport, scope = backgroundScope)
        session.activate(profile(), HidDescriptorCompiler().compile(profile()))

        session.runAdhoc(
            Actor.Human(), "combo:test",
            listOf(MacroStep.Hold(keys = listOf("keyboard.leftCtrl", "keyboard.c"), durationMs = 50)),
        )
        advanceTimeBy(1_000)
        advanceUntilIdle()

        // 4 events: ctrl down, c down, c up, ctrl up.
        assertEquals(4, transport.sent.size)
        val ctrlDown = transport.sent[0].second
        assertEquals(0x01, ctrlDown[0].toInt() and 0xFF) // leftCtrl bit
        assertEquals(0, ctrlDown[2].toInt() and 0xFF)    // no key yet
        val cDown = transport.sent[1].second
        assertEquals(0x01, cDown[0].toInt() and 0xFF)
        assertEquals(0x06, cDown[2].toInt() and 0xFF)    // 'c' usage
        val ctrlUp = transport.sent[3].second
        assertEquals(0, ctrlUp[0].toInt() and 0xFF)
        assertEquals(0, ctrlUp[2].toInt() and 0xFF)
    }
}