package dev.morphhid.core.control

import dev.morphhid.core.profile.MacroStep
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MacroRuntimeTest {

    private class RecordingHost(private val scope: kotlinx.coroutines.test.TestScope) : MacroRuntime.Host {
        val events = mutableListOf<Pair<Long, String>>()

        override suspend fun pressKey(keyId: String) {
            events += scope.testScheduler.currentTime to "down:$keyId"
        }

        override suspend fun releaseKey(keyId: String) {
            events += scope.testScheduler.currentTime to "up:$keyId"
        }

        override suspend fun setControl(controlId: String, value: String) {
            events += scope.testScheduler.currentTime to "set:$controlId=$value"
        }

        override suspend fun page(screenId: String) {
            events += scope.testScheduler.currentTime to "page:$screenId"
        }

        override suspend fun haptic(ms: Long) {
            events += scope.testScheduler.currentTime to "haptic:$ms"
        }

        override suspend fun delay(ms: Long) {
            kotlinx.coroutines.delay(ms)
        }
    }

    @Test
    fun `typing apple produces sequenced key events with gaps`() = runTest {
        val host = RecordingHost(this)
        MacroRuntime(host, Random(42)).execute(
            listOf(MacroStep.Type(text = "apple", keyDelayMs = 45, jitterMs = 0)),
        )

        val expected = listOf(
            "down:keyboard.a", "up:keyboard.a",
            "down:keyboard.p", "up:keyboard.p",
            "down:keyboard.p", "up:keyboard.p",
            "down:keyboard.l", "up:keyboard.l",
            "down:keyboard.e", "up:keyboard.e",
        )
        assertEquals(expected, host.events.map { it.second })

        // Timing: hold 15ms, gap 45ms -> each char takes 60ms.
        val downs = host.events.filter { it.second.startsWith("down:") }
        assertEquals(listOf(0L, 60L, 120L, 180L, 240L), downs.map { it.first })
    }

    @Test
    fun `uppercase letters hold shift across the run`() = runTest {
        val host = RecordingHost(this)
        MacroRuntime(host, Random(7)).execute(
            listOf(MacroStep.Type(text = "OK", keyDelayMs = 10, jitterMs = 0)),
        )
        val names = host.events.map { it.second }
        // shift goes down once, stays across both letters, releases at the end.
        assertEquals(1, names.count { it == "down:keyboard.leftShift" })
        assertEquals(1, names.count { it == "up:keyboard.leftShift" })
        // Shift release happens after the last letter release.
        assertTrue(names.indexOf("up:keyboard.leftShift") > names.indexOf("up:keyboard.k"))
    }

    @Test
    fun `jitter stays within bounds`() = runTest {
        val host = RecordingHost(this)
        MacroRuntime(host, Random(1)).execute(
            listOf(MacroStep.Type(text = "abcd", keyDelayMs = 30, jitterMs = 20)),
        )
        val downs = host.events.filter { it.second.startsWith("down:") }.map { it.first }
        // Gaps between consecutive key-downs are in [30, 50] + 15 hold = [45, 65].
        for (i in 1 until downs.size) {
            val gap = downs[i] - downs[i - 1]
            assertTrue("gap $gap out of bounds", gap in 45..65)
        }
    }

    @Test
    fun `hold presses then releases after duration`() = runTest {
        val host = RecordingHost(this)
        MacroRuntime(host).execute(
            listOf(
                MacroStep.Hold(keys = listOf("keyboard.leftCtrl", "keyboard.c"), durationMs = 90),
            ),
        )
        val names = host.events.map { it.second }
        assertEquals(
            listOf("down:keyboard.leftCtrl", "down:keyboard.c", "up:keyboard.c", "up:keyboard.leftCtrl"),
            names,
        )
        assertEquals(90L, host.events[2].first)
    }

    @Test
    fun `cancelling a macro releases held keys`() = runTest {
        val released = mutableListOf<String>()
        val host = object : MacroRuntime.Host {
            override suspend fun pressKey(keyId: String) {}
            override suspend fun releaseKey(keyId: String) { released += keyId }
            override suspend fun setControl(controlId: String, value: String) {}
            override suspend fun page(screenId: String) {}
            override suspend fun haptic(ms: Long) {}
            override suspend fun delay(ms: Long) { kotlinx.coroutines.delay(ms) }
        }
        val job = launch {
            MacroRuntime(host).execute(
                listOf(
                    MacroStep.Press(keys = listOf("keyboard.leftCtrl", "keyboard.leftShift")),
                    MacroStep.Delay(ms = 60_000),
                ),
            )
        }
        advanceTimeBy(100)
        job.cancelAndJoin()
        // Reverse order of pressing.
        assertEquals(listOf("keyboard.leftShift", "keyboard.leftCtrl"), released)
    }

    @Test
    fun `repeat loops steps with interval`() = runTest {
        val host = RecordingHost(this)
        MacroRuntime(host).execute(
            listOf(
                MacroStep.Repeat(
                    times = 3,
                    intervalMs = 25,
                    steps = listOf(MacroStep.Tap(key = "keyboard.f5", holdMs = 10)),
                ),
            ),
        )
        assertEquals(6, host.events.size)
        val downs = host.events.filter { it.second == "down:keyboard.f5" }.map { it.first }
        // char cycle = 10 hold + 15 default? No: Tap hold=10; no gap after Tap itself,
        // so intervals come from Repeat's intervalMs: 10 + 25 = 35ms per iteration.
        assertEquals(listOf(0L, 35L, 70L), downs)
    }
}