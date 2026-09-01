package dev.morphhid.app.bluetooth

import dev.morphhid.core.hid.CompiledHid
import dev.morphhid.core.hid.HidDescriptorCompiler
import dev.morphhid.core.profile.DeviceSpec
import dev.morphhid.core.profile.HidCollectionSpec
import dev.morphhid.core.profile.HidSpec
import dev.morphhid.core.profile.Profile

/**
 * One fixed HID descriptor registered once. Profiles are only local UI and
 * control mappings on top of this descriptor, so switching profiles never
 * re-registers Bluetooth HID and never invalidates the host's cached SDP.
 */
object UniversalHid {
    val compiled: CompiledHid by lazy {
        HidDescriptorCompiler().compile(
            Profile(
                device = DeviceSpec(
                    name = "MorphHID Universal",
                    description = "Universal HID device",
                    provider = "MorphHID",
                    hid = HidSpec(
                        subclass = "combo",
                        collections = listOf(
                            HidCollectionSpec.Keyboard(reportId = 1),
                            HidCollectionSpec.Pointer(
                                reportId = 2,
                                buttons = 3,
                                relativeAxes = listOf("x", "y", "wheel"),
                            ),
                            HidCollectionSpec.Consumer(
                                reportId = 3,
                                usages = listOf(
                                    "playPause",
                                    "volumeUp",
                                    "volumeDown",
                                    "mute",
                                    "nextTrack",
                                    "prevTrack",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
