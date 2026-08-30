package dev.morphhid.core.hid

/**
 * HID usage tables used to compile profiles and resolve friendly control ids
 * like "keyboard.a", "keyboard.leftCtrl", "consumer.mute", "pointer.button1".
 *
 * Usages follow the USB HID Usage Tables (HUT v1.12) for the Keyboard/Keypad
 * page (0x07), Consumer page (0x0C) and LED page (0x08).
 */
object UsagePages {
    const val GENERIC_DESKTOP: Int = 0x01
    const val LED: Int = 0x08
    const val BUTTON: Int = 0x09
    const val KEYBOARD: Int = 0x07
    const val CONSUMER: Int = 0x0C
}

object GenericDesktopUsages {
    const val POINTER = 0x01
    const val MOUSE = 0x02
    const val JOYSTICK = 0x04
    const val GAMEPAD = 0x05
    const val KEYBOARD = 0x06
    const val X = 0x30
    const val Y = 0x31
    const val Z = 0x32
    const val RX = 0x33
    const val RY = 0x34
    const val RZ = 0x35
    const val SLIDER = 0x36
    const val DIAL = 0x37
    const val WHEEL = 0x38
    const val HAT_SWITCH = 0x39
}

object LedUsages {
    const val NUM_LOCK = 0x01
    const val CAPS_LOCK = 0x02
    const val SCROLL_LOCK = 0x03
    const val COMPOSE = 0x04
    const val KANA = 0x05
}

/** Keyboard/Keypad page (0x07) usages, keyed by friendly name. */
object KeyboardUsage {
    val BY_NAME: Map<String, Int> = buildMap {
        // Letters
        for (c in 'a'..'z') put(c.toString(), 0x04 + (c - 'a'))
        // Digits
        for (i in 1..9) put(i.toString(), 0x1D + i)
        put("0", 0x27)
        // Control keys
        put("enter", 0x28); put("return", 0x28)
        put("escape", 0x29); put("esc", 0x29)
        put("backspace", 0x2A)
        put("tab", 0x2B)
        put("space", 0x2C)
        // Punctuation (US layout)
        put("minus", 0x2D); put("hyphen", 0x2D)
        put("equal", 0x2E); put("equals", 0x2E)
        put("leftBracket", 0x2F)
        put("rightBracket", 0x30)
        put("backslash", 0x31)
        put("semicolon", 0x33)
        put("quote", 0x34); put("apostrophe", 0x34)
        put("grave", 0x35); put("backtick", 0x35)
        put("comma", 0x36)
        put("period", 0x37); put("dot", 0x37)
        put("slash", 0x38)
        put("capsLock", 0x39)
        // Function keys
        for (i in 1..12) put("f$i", 0x3A + (i - 1))
        // Navigation
        put("printScreen", 0x46)
        put("scrollLock", 0x47)
        put("pause", 0x48)
        put("insert", 0x49)
        put("home", 0x4A)
        put("pageUp", 0x4B)
        put("delete", 0x4C); put("del", 0x4C)
        put("end", 0x4D)
        put("pageDown", 0x4E)
        put("right", 0x4F)
        put("left", 0x50)
        put("down", 0x51)
        put("up", 0x52)
        // Numpad
        put("numLock", 0x53)
        put("kpSlash", 0x54)
        put("kpAsterisk", 0x55)
        put("kpMinus", 0x56)
        put("kpPlus", 0x57)
        put("kpEnter", 0x58)
        for (i in 1..9) put("kp$i", 0x58 + i)
        put("kp0", 0x62)
        put("kpDot", 0x63)
        // Modifiers
        put("leftCtrl", 0xE0)
        put("leftShift", 0xE1)
        put("leftAlt", 0xE2)
        put("leftGui", 0xE3); put("winKey", 0xE3); put("cmdKey", 0xE3)
        put("rightCtrl", 0xE4)
        put("rightShift", 0xE5)
        put("rightAlt", 0xE6)
        put("rightGui", 0xE7)
        // Friendly aliases (map to the left-hand modifier).
        put("shift", 0xE1)
        put("ctrl", 0xE0)
        put("alt", 0xE2)
        put("gui", 0xE3)
        put("win", 0xE3)
    }

    val MODIFIER_USAGES: Set<Int> = (0xE0..0xE7).toSet()
    const val ERROR_ROLLOVER = 0x01
}

/** Consumer page (0x0C) usages, keyed by friendly name (16-bit usage values). */
object ConsumerUsage {
    val BY_NAME: Map<String, Int> = mapOf(
        "menu" to 0x40,
        "power" to 0x30,
        "play" to 0xB0,
        "pause" to 0xB1,
        "record" to 0xB2,
        "fastForward" to 0xB3,
        "rewind" to 0xB4,
        "nextTrack" to 0xB5,
        "prevTrack" to 0xB6,
        "stop" to 0xB7,
        "eject" to 0xB8,
        "playPause" to 0xCD,
        "mute" to 0xE2,
        "volumeUp" to 0xE9,
        "volumeDown" to 0xEA,
        "browserBack" to 0x224,
        "browserForward" to 0x225,
        "browserRefresh" to 0x227,
        "browserHome" to 0x223,
        "calculator" to 0x192,
        "mail" to 0x18A,
    )
}

/** Gamepad axis friendly names -> Generic Desktop usage. */
object GamepadAxisUsage {
    val BY_NAME: Map<String, Int> = mapOf(
        "lx" to GenericDesktopUsages.X,
        "ly" to GenericDesktopUsages.Y,
        "rx" to GenericDesktopUsages.RX,
        "ry" to GenericDesktopUsages.RY,
        "z" to GenericDesktopUsages.Z,
        "rz" to GenericDesktopUsages.RZ,
    )
}

/**
 * US keyboard layout mapping for the `type` macro step: character ->
 * (key name, requiresShift). Non-representable characters throw at
 * validation time.
 */
object TextLayout {
    data class KeyCap(val key: String, val shift: Boolean)

    private val direct: Map<Char, String> = buildMap {
        for (c in 'a'..'z') put(c, c.toString())
        for (c in '0'..'9') put(c, c.toString())
        put(' ', "space")
        put('\n', "enter")
        put('\r', "enter")
        put('\t', "tab")
        put('-', "minus")
        put('=', "equal")
        put('[', "leftBracket")
        put(']', "rightBracket")
        put('\\', "backslash")
        put(';', "semicolon")
        put('\'', "quote")
        put('`', "grave")
        put(',', "comma")
        put('.', "period")
        put('/', "slash")
    }

    private val shifted: Map<Char, String> = mapOf(
        '!' to "1", '@' to "2", '#' to "3", '$' to "4", '%' to "5",
        '^' to "6", '&' to "7", '*' to "8", '(' to "9", ')' to "0",
        '_' to "minus", '+' to "equal",
        '{' to "leftBracket", '}' to "rightBracket", '|' to "backslash",
        ':' to "semicolon", '"' to "quote", '~' to "grave",
        '<' to "comma", '>' to "period", '?' to "slash",
    )

    fun keyCapFor(c: Char): KeyCap? {
        if (c in 'A'..'Z') return KeyCap(c.lowercaseChar().toString(), shift = true)
        direct[c]?.let { return KeyCap(it, shift = false) }
        shifted[c]?.let { return KeyCap(it, shift = true) }
        return null
    }

    fun canType(c: Char): Boolean = keyCapFor(c) != null

    fun canTypeAll(text: String): Boolean = text.all { canType(it) }
}