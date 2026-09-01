package dev.morphhid.core.hid

import dev.morphhid.core.profile.DeviceSpec
import dev.morphhid.core.profile.HidCollectionSpec
import dev.morphhid.core.profile.HidSpec
import dev.morphhid.core.profile.Profile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bytesOf(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

class HidDescriptorCompilerTest {

    private fun keyboardProfile(): Profile = Profile(
        device = DeviceSpec(
            name = "Test Keyboard",
            hid = HidSpec(subclass = "keyboard", collections = listOf(HidCollectionSpec.Keyboard())),
        ),
    )

    @Test
    fun `boot keyboard descriptor matches the canonical layout`() {
        val compiled = HidDescriptorCompiler().compile(keyboardProfile())

        val expected = bytesOf(
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x06,             // Usage (Keyboard)
            0xA1, 0x01,             // Collection (Application)
            0x85, 0x01,             //   Report ID (1)
            0x05, 0x07,             //   Usage Page (Key Codes)
            0x19, 0xE0,             //   Usage Min (224)
            0x29, 0xE7,             //   Usage Max (231)
            0x15, 0x00,             //   Logical Min (0)
            0x25, 0x01,             //   Logical Max (1)
            0x75, 0x01,             //   Report Size (1)
            0x95, 0x08,             //   Report Count (8)
            0x81, 0x02,             //   Input (Data, Var, Abs) - modifiers
            0x95, 0x01,             //   Report Count (1)
            0x75, 0x08,             //   Report Size (8)
            0x81, 0x01,             //   Input (Const, Array) - reserved byte
            0x05, 0x08,             //   Usage Page (LEDs)
            0x19, 0x01,             //   Usage Min (Num Lock)
            0x29, 0x05,             //   Usage Max (Kana)
            0x15, 0x00,
            0x25, 0x01,
            0x75, 0x01,
            0x95, 0x05,
            0x91, 0x02,             //   Output (Data, Var, Abs)
            0x95, 0x03,
            0x91, 0x01,             //   Output (Const, Array) - padding
            0x05, 0x07,             //   Usage Page (Key Codes)
            0x19, 0x00,             //   Usage Min (0)
            0x29, 0x65,             //   Usage Max (101)
            0x15, 0x00,             //   Logical Min (0)
            0x25, 0x65,             //   Logical Max (101)
            0x75, 0x08,             //   Report Size (8)
            0x95, 0x06,             //   Report Count (6)
            0x81, 0x00,             //   Input (Data, Array)
            0xC0,                   // End Collection
        )
        assertArrayEquals(expected, compiled.descriptor)

        assertEquals(0x40.toByte(), compiled.subclassByte)
        assertEquals(1, compiled.reports.size)
        val layout = compiled.reports[0]
        assertEquals(8, layout.inputBytes)   // mods + reserved + 6 keys
        assertEquals(1, layout.outputBytes)  // LEDs

        val keyA = compiled.findControl("keyboard.a")
        assertNotNull(keyA)
        assertEquals(0x04, keyA!!.usage)

        val leftCtrl = compiled.findControl("keyboard.leftCtrl")
        assertEquals(ControlKind.MODIFIER, leftCtrl!!.kind)
        assertEquals(0, leftCtrl.bitOffset)

        val caps = compiled.findControl("led.capsLock")
        assertEquals(ControlKind.LED, caps!!.kind)
        assertTrue(caps.isOutput)
        val compose = compiled.findControl("led.compose")
        assertEquals(ControlKind.LED, compose!!.kind)
        assertTrue(compose.isOutput)
        val kana = compiled.findControl("led.kana")
        assertEquals(ControlKind.LED, kana!!.kind)
        assertTrue(kana.isOutput)
    }

    @Test
    fun `pointer report layout matches button-then-axes expectation`() {
        val profile = Profile(
            device = DeviceSpec(
                name = "Mouse",
                hid = HidSpec(
                    subclass = "mouse",
                    collections = listOf(
                        HidCollectionSpec.Pointer(buttons = 3, relativeAxes = listOf("x", "y", "wheel")),
                    ),
                ),
            ),
        )
        val compiled = HidDescriptorCompiler().compile(profile)
        val layout = compiled.reports.single()
        assertEquals(4, layout.inputBytes) // buttons(1) + x + y + wheel
        assertEquals(ControlKind.BUTTON, compiled.findControl("pointer.button1")!!.kind)
        assertEquals(0, compiled.findControl("pointer.button1")!!.bitOffset) // byte 0
        val wheel = compiled.findControl("pointer.wheel")
        assertNotNull(wheel)
        assertEquals(24, wheel!!.bitOffset) // byte 3
    }

    @Test
    fun `consumer usage bitmap spans min to max usage`() {
        val profile = Profile(
            device = DeviceSpec(
                name = "Media",
                hid = HidSpec(
                    subclass = "none",
                    collections = listOf(
                        HidCollectionSpec.Consumer(usages = listOf("playPause", "volumeUp", "volumeDown", "mute")),
                    ),
                ),
            ),
        )
        val compiled = HidDescriptorCompiler().compile(profile)
        val layout = compiled.reports.single()
        // lo=0xCD(205) hi=0xEA(234) -> 30 bits -> 4 bytes
        assertEquals(4, layout.inputBytes)
        val volumeUp = compiled.findControl("consumer.volumeUp")
        assertNotNull(volumeUp)
        assertEquals(0xE9 - 0xCD, volumeUp!!.bitOffset)
    }

    @Test
    fun `gamepad exposes buttons hat and axes in order`() {
        val profile = Profile(
            device = DeviceSpec(
                name = "Pad",
                hid = HidSpec(
                    subclass = "none",
                    collections = listOf(
                        HidCollectionSpec.Gamepad(
                            buttons = 16,
                            axes = listOf("lx", "ly", "rx", "ry"),
                            hat = true,
                        ),
                    ),
                ),
            ),
        )
        val compiled = HidDescriptorCompiler().compile(profile)
        val layout = compiled.reports.single()
        // buttons(2 bytes) + hat(1 byte incl. padding) + 4 axes = 7
        assertEquals(7, layout.inputBytes)
        assertEquals(16, compiled.findControl("gamepad.hat")!!.bitOffset) // byte 2
        assertEquals(24, compiled.findControl("gamepad.lx")!!.bitOffset)  // byte 3
        assertEquals(-127, compiled.findControl("gamepad.ry")!!.logicalMin)
    }

    @Test
    fun `report ids are auto-assigned sequentially and duplicates rejected`() {
        val profile = Profile(
            device = DeviceSpec(
                name = "Combo",
                hid = HidSpec(
                    collections = listOf(
                        HidCollectionSpec.Keyboard(reportId = 2),
                        HidCollectionSpec.Pointer(),
                    ),
                ),
            ),
        )
        val compiled = HidDescriptorCompiler().compile(profile)
        assertEquals(listOf(2, 3), compiled.reports.map { it.reportId })

        val bad = Profile(
            device = DeviceSpec(
                name = "Bad",
                hid = HidSpec(
                    collections = listOf(
                        HidCollectionSpec.Keyboard(reportId = 1),
                        HidCollectionSpec.Consumer(usages = listOf("mute"), reportId = 1),
                    ),
                ),
            ),
        )
        try {
            HidDescriptorCompiler().compile(bad)
            throw AssertionError("expected duplicate reportId to fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    @Test
    fun `pointer descriptor uses a buttonless flat layout when no buttons`() {
        val profile = Profile(
            device = DeviceSpec(
                name = "AxisOnly",
                hid = HidSpec(
                    subclass = "none",
                    collections = listOf(
                        HidCollectionSpec.Pointer(buttons = 0, relativeAxes = listOf("x", "y")),
                    ),
                ),
            ),
        )
        val compiled = HidDescriptorCompiler().compile(profile)
        // Flat: only the Application collection.
        assertEquals(1, compiled.descriptor.toList().count { it == 0xC0.toByte() })
        // 2-byte report: x + y deltas.
        assertEquals(2, compiled.reports.single().inputBytes)
        assertTrue(compiled.findControl("pointer.button1") == null)
    }

    @Test
    fun `pointer descriptor nests a physical collection when buttons exist`() {
        val profile = Profile(
            device = DeviceSpec(
                name = "Mouse",
                hid = HidSpec(
                    subclass = "mouse",
                    collections = listOf(
                        HidCollectionSpec.Pointer(buttons = 3, relativeAxes = listOf("x", "y")),
                    ),
                ),
            ),
        )
        val compiled = HidDescriptorCompiler().compile(profile)
        assertEquals(2, compiled.descriptor.toList().count { it == 0xC0.toByte() })
        // Buttons byte first, then x, y.
        assertEquals(3, compiled.reports.single().inputBytes)
    }

    @Test
    fun `modifier aliases resolve to the same usages`() {
        val compiled = HidDescriptorCompiler().compile(keyboardProfile())
        assertEquals(0xE1, compiled.findControl("keyboard.shift")!!.usage)
        assertEquals(0xE0, compiled.findControl("keyboard.ctrl")!!.usage)
        assertEquals(0xE2, compiled.findControl("keyboard.alt")!!.usage)
        assertEquals(0xE3, compiled.findControl("keyboard.win")!!.usage)
        // Aliases and their long forms hit the same usage, so they share state.
        assertEquals(
            compiled.findControl("keyboard.shift")!!.usage,
            compiled.findControl("keyboard.leftShift")!!.usage,
        )
    }

    @Test
    fun `fingerprint is stable and changes with descriptor`() {
        val c1 = HidDescriptorCompiler().compile(keyboardProfile())
        val c2 = HidDescriptorCompiler().compile(keyboardProfile())
        assertEquals(c1.fingerprint, c2.fingerprint)

        val combo = Profile(
            device = DeviceSpec(
                name = "Combo",
                hid = HidSpec(
                    collections = listOf(
                        HidCollectionSpec.Keyboard(),
                        HidCollectionSpec.Pointer(),
                    ),
                ),
            ),
        )
        val c3 = HidDescriptorCompiler().compile(combo)
        assertTrue(c1.fingerprint != c3.fingerprint)
    }
}