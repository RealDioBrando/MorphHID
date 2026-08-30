package dev.morphhid.core.hid

import dev.morphhid.core.profile.DeviceSpec
import dev.morphhid.core.profile.HidCollectionSpec
import dev.morphhid.core.profile.HidSpec
import dev.morphhid.core.profile.Profile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReportCodecTest {

    private lateinit var compiled: CompiledHid
    private lateinit var codec: ReportCodec

    @Before
    fun setUp() {
        compiled = HidDescriptorCompiler().compile(
            Profile(
                device = DeviceSpec(
                    name = "Test",
                    hid = HidSpec(
                        collections = listOf(
                            HidCollectionSpec.Keyboard(),
                            HidCollectionSpec.Pointer(buttons = 3, relativeAxes = listOf("x", "y")),
                            HidCollectionSpec.Consumer(
                                usages = listOf("playPause", "volumeUp", "volumeDown", "mute"),
                            ),
                            HidCollectionSpec.Gamepad(
                                buttons = 16,
                                axes = listOf("lx", "ly"),
                                hat = true,
                            ),
                        ),
                    ),
                ),
            ),
        )
        codec = ReportCodec(compiled)
    }

    private fun singleFlush(): Pair<Int, ByteArray> {
        val reports = codec.flushDirty()
        assertEquals(1, reports.size)
        return reports[0]
    }

    @Test
    fun `pressing a key fills the first slot`() {
        assertTrue(codec.press("keyboard.a"))
        val (id, payload) = singleFlush()
        assertEquals(1, id)
        assertArrayEquals(byteArrayOf(0, 0, 0x04, 0, 0, 0, 0, 0), payload)
    }

    @Test
    fun `modifier plus key lands in modifier byte`() {
        codec.press("keyboard.leftShift")
        codec.press("keyboard.a")
        val (_, payload) = singleFlush()
        // leftShift = usage 0xE1 -> bit 1 -> 0x02
        assertEquals(0x02, payload[0].toInt() and 0xFF)
        assertEquals(0x04, payload[2].toInt() and 0xFF)
    }

    @Test
    fun `more keys than slots produce ErrorRollOver`() {
        val keys = listOf("q", "w", "e", "r", "t", "y", "u")
        keys.forEach { codec.press("keyboard." + it) }
        val (_, payload) = singleFlush()
        for (i in 2..7) {
            assertEquals(0x01, payload[i].toInt() and 0xFF)
        }
    }

    @Test
    fun `release removes the key and re-sends`() {
        codec.press("keyboard.a")
        codec.flushDirty()
        assertTrue(codec.release("keyboard.a"))
        val (_, payload) = singleFlush()
        assertEquals(0, payload[2].toInt() and 0xFF)
        // Releasing again is a no-op.
        assertFalse(codec.release("keyboard.a"))
        assertTrue(codec.flushDirty().isEmpty())
    }

    @Test
    fun `mouse buttons and relative deltas accumulate`() {
        codec.press("pointer.button2")
        var (id, payload) = singleFlush()
        assertEquals(2, id)
        assertEquals(0x02, payload[0].toInt() and 0xFF)

        codec.release("pointer.button2")
        codec.addRelative("pointer.x", 3)
        codec.addRelative("pointer.x", 4)
        codec.addRelative("pointer.y", -2)
        val (_, move) = singleFlush()
        assertEquals(0, move[0].toInt() and 0xFF) // button released previously
        assertEquals(7, move[1].toInt())
        assertEquals(-2, move[2].toInt())

        // Deltas are cleared after flush.
        assertTrue(codec.flushDirty().isEmpty())
    }

    @Test
    fun `consumer bits map to the right offsets`() {
        codec.press("consumer.volumeUp")
        val (id, payload) = singleFlush()
        assertEquals(3, id)
        // volumeUp = 0xE9, lo = 0xCD -> bit 28 -> byte 3, bit 4
        assertArrayEquals(byteArrayOf(0, 0, 0, 0x10), payload)
    }

    @Test
    fun `gamepad hat and axes encode`() {
        codec.press("gamepad.button13")
        codec.setHat("gamepad.hat", 3) // SE
        codec.setAxisNormalized("gamepad.lx", 0.5f)
        val (id, payload) = singleFlush()
        assertEquals(4, id)
        assertEquals(0x10, payload[1].toInt() and 0xFF) // button 13 -> bit 12
        assertEquals(3, payload[2].toInt() and 0x0F)    // hat
        assertEquals(64, payload[3].toInt())            // 0.5 * 127
    }

    @Test
    fun `led output report decodes to control states`() {
        val leds = codec.decodeOutput(1, byteArrayOf(0x03))
        assertEquals(mapOf("led.numLock" to true, "led.capsLock" to true, "led.scrollLock" to false), leds)
    }

    @Test
    fun `releaseAll neutralizes every report`() {
        codec.press("keyboard.a")
        codec.press("pointer.button1")
        codec.press("consumer.mute")
        codec.press("gamepad.button1")
        codec.setAxisNormalized("gamepad.lx", 1f)
        codec.setHat("gamepad.hat", 2)

        codec.releaseAll()
        val reports = codec.flushDirty()
        assertEquals(4, reports.size)
        val keyboard = reports.first { it.first == 1 }.second
        assertEquals(0, keyboard[0].toInt() and 0xFF)
        assertEquals(0, keyboard[2].toInt() and 0xFF)
        val gamepad = reports.first { it.first == 4 }.second
        assertEquals(0, gamepad[0].toInt() and 0xFF)
        assertEquals(8, gamepad[2].toInt() and 0x0F) // neutral hat
        assertEquals(0, gamepad[3].toInt())          // centered axis
        assertFalse(codec.isPressed("keyboard.a"))
    }
}