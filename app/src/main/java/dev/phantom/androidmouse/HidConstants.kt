package dev.phantom.androidmouse

/**
 * HID Report Descriptor and report-building utilities.
 *
 * The descriptor declares two logical devices:
 *   Report ID 1 — Mouse    (4 bytes: buttons | dx | dy | wheel)
 *   Report ID 2 — Keyboard (8 bytes: modifiers | reserved | key[0..5])
 *
 * Layout verified against the USB HID Usage Tables 1.3 spec. One wrong bit here
 * and Linux silently ignores the device — keep this file byte-perfect.
 */
object HidConstants {

    const val REPORT_ID_MOUSE: Int = 1
    const val REPORT_ID_KEYBOARD: Int = 2

    // HID descriptor encoded as a ByteArray.
    // Helper: ints are truncated to their low byte (sign-extended as needed by HID).
    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    val DESCRIPTOR: ByteArray = bytes(
        // ── Mouse ────────────────────────────────────────────────────────────
        0x05, 0x01,  // Usage Page (Generic Desktop)
        0x09, 0x02,  // Usage (Mouse)
        0xA1, 0x01,  // Collection (Application)
        0x09, 0x01,  //   Usage (Pointer)
        0xA1, 0x00,  //   Collection (Physical)
        0x85, 0x01,  //     Report ID (1)

        // Buttons 1–3
        0x05, 0x09,  //     Usage Page (Button)
        0x19, 0x01,  //     Usage Minimum (1)
        0x29, 0x03,  //     Usage Maximum (3)
        0x15, 0x00,  //     Logical Minimum (0)
        0x25, 0x01,  //     Logical Maximum (1)
        0x75, 0x01,  //     Report Size (1 bit)
        0x95, 0x03,  //     Report Count (3)
        0x81, 0x02,  //     Input (Data, Variable, Absolute)

        // 5 padding bits → round up to a full byte
        0x75, 0x05,  //     Report Size (5 bits)
        0x95, 0x01,  //     Report Count (1)
        0x81, 0x03,  //     Input (Constant)

        // X / Y relative movement  (-127..127)
        0x05, 0x01,  //     Usage Page (Generic Desktop)
        0x09, 0x30,  //     Usage (X)
        0x09, 0x31,  //     Usage (Y)
        0x15, 0x81,  //     Logical Minimum (-127)
        0x25, 0x7F,  //     Logical Maximum (127)
        0x75, 0x08,  //     Report Size (8 bits)
        0x95, 0x02,  //     Report Count (2)
        0x81, 0x06,  //     Input (Data, Variable, Relative)

        // Vertical scroll wheel  (-127..127)
        0x09, 0x38,  //     Usage (Wheel)
        0x15, 0x81,  //     Logical Minimum (-127)
        0x25, 0x7F,  //     Logical Maximum (127)
        0x75, 0x08,  //     Report Size (8 bits)
        0x95, 0x01,  //     Report Count (1)
        0x81, 0x06,  //     Input (Data, Variable, Relative)

        0xC0,        //   End Collection (Physical)
        0xC0,        // End Collection (Application)

        // ── Keyboard ─────────────────────────────────────────────────────────
        0x05, 0x01,  // Usage Page (Generic Desktop)
        0x09, 0x06,  // Usage (Keyboard)
        0xA1, 0x01,  // Collection (Application)
        0x85, 0x02,  //   Report ID (2)

        // Modifier keys: LCtrl LShift LAlt LGUI RCtrl RShift RAlt RGUI
        0x05, 0x07,  //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0,  //   Usage Minimum (Left Control)
        0x29, 0xE7,  //   Usage Maximum (Right GUI)
        0x15, 0x00,  //   Logical Minimum (0)
        0x25, 0x01,  //   Logical Maximum (1)
        0x75, 0x01,  //   Report Size (1 bit)
        0x95, 0x08,  //   Report Count (8)
        0x81, 0x02,  //   Input (Data, Variable, Absolute)

        // Reserved byte
        0x75, 0x08,  //   Report Size (8 bits)
        0x95, 0x01,  //   Report Count (1)
        0x81, 0x03,  //   Input (Constant)

        // Key array: up to 6 simultaneous non-modifier keys
        0x05, 0x07,  //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,  //   Usage Minimum (0)
        0x29, 0x65,  //   Usage Maximum (101)
        0x15, 0x00,  //   Logical Minimum (0)
        0x25, 0x65,  //   Logical Maximum (101)
        0x75, 0x08,  //   Report Size (8 bits)
        0x95, 0x06,  //   Report Count (6)
        0x81, 0x00,  //   Input (Data, Array, Absolute)

        0xC0,        // End Collection (Application)
    )

    // ── Mouse button bit masks ────────────────────────────────────────────────
    const val BTN_LEFT:   Int = 0x01
    const val BTN_RIGHT:  Int = 0x02
    const val BTN_MIDDLE: Int = 0x04

    // ── Keyboard modifier bit masks ───────────────────────────────────────────
    const val MOD_LEFT_CTRL:  Int = 0x01
    const val MOD_LEFT_SHIFT: Int = 0x02
    const val MOD_LEFT_ALT:   Int = 0x04
    const val MOD_LEFT_GUI:   Int = 0x08
    const val MOD_RIGHT_CTRL: Int = 0x10
    const val MOD_RIGHT_SHIFT:Int = 0x20
    const val MOD_RIGHT_ALT:  Int = 0x40
    const val MOD_RIGHT_GUI:  Int = 0x80

    /**
     * Basic ASCII → HID keycode + modifier table.
     * Returns Pair(keycode, modifiers).  Returns null for unmapped characters.
     */
    fun charToHid(char: Char): Pair<Int, Int>? = when (char) {
        'a' -> 0x04 to 0
        'b' -> 0x05 to 0
        'c' -> 0x06 to 0
        'd' -> 0x07 to 0
        'e' -> 0x08 to 0
        'f' -> 0x09 to 0
        'g' -> 0x0A to 0
        'h' -> 0x0B to 0
        'i' -> 0x0C to 0
        'j' -> 0x0D to 0
        'k' -> 0x0E to 0
        'l' -> 0x0F to 0
        'm' -> 0x10 to 0
        'n' -> 0x11 to 0
        'o' -> 0x12 to 0
        'p' -> 0x13 to 0
        'q' -> 0x14 to 0
        'r' -> 0x15 to 0
        's' -> 0x16 to 0
        't' -> 0x17 to 0
        'u' -> 0x18 to 0
        'v' -> 0x19 to 0
        'w' -> 0x1A to 0
        'x' -> 0x1B to 0
        'y' -> 0x1C to 0
        'z' -> 0x1D to 0
        'A' -> 0x04 to MOD_LEFT_SHIFT
        'B' -> 0x05 to MOD_LEFT_SHIFT
        'C' -> 0x06 to MOD_LEFT_SHIFT
        'D' -> 0x07 to MOD_LEFT_SHIFT
        'E' -> 0x08 to MOD_LEFT_SHIFT
        'F' -> 0x09 to MOD_LEFT_SHIFT
        'G' -> 0x0A to MOD_LEFT_SHIFT
        'H' -> 0x0B to MOD_LEFT_SHIFT
        'I' -> 0x0C to MOD_LEFT_SHIFT
        'J' -> 0x0D to MOD_LEFT_SHIFT
        'K' -> 0x0E to MOD_LEFT_SHIFT
        'L' -> 0x0F to MOD_LEFT_SHIFT
        'M' -> 0x10 to MOD_LEFT_SHIFT
        'N' -> 0x11 to MOD_LEFT_SHIFT
        'O' -> 0x12 to MOD_LEFT_SHIFT
        'P' -> 0x13 to MOD_LEFT_SHIFT
        'Q' -> 0x14 to MOD_LEFT_SHIFT
        'R' -> 0x15 to MOD_LEFT_SHIFT
        'S' -> 0x16 to MOD_LEFT_SHIFT
        'T' -> 0x17 to MOD_LEFT_SHIFT
        'U' -> 0x18 to MOD_LEFT_SHIFT
        'V' -> 0x19 to MOD_LEFT_SHIFT
        'W' -> 0x1A to MOD_LEFT_SHIFT
        'X' -> 0x1B to MOD_LEFT_SHIFT
        'Y' -> 0x1C to MOD_LEFT_SHIFT
        'Z' -> 0x1D to MOD_LEFT_SHIFT
        '1' -> 0x1E to 0
        '2' -> 0x1F to 0
        '3' -> 0x20 to 0
        '4' -> 0x21 to 0
        '5' -> 0x22 to 0
        '6' -> 0x23 to 0
        '7' -> 0x24 to 0
        '8' -> 0x25 to 0
        '9' -> 0x26 to 0
        '0' -> 0x27 to 0
        '\n' -> 0x28 to 0   // Enter
        '\t' -> 0x2B to 0   // Tab
        ' '  -> 0x2C to 0   // Space
        '-'  -> 0x2D to 0
        '='  -> 0x2E to 0
        '['  -> 0x2F to 0
        ']'  -> 0x30 to 0
        '\\'  -> 0x31 to 0
        ';'  -> 0x33 to 0
        '\'' -> 0x34 to 0
        '`'  -> 0x35 to 0
        ','  -> 0x36 to 0
        '.'  -> 0x37 to 0
        '/'  -> 0x38 to 0
        '!'  -> 0x1E to MOD_LEFT_SHIFT
        '@'  -> 0x1F to MOD_LEFT_SHIFT
        '#'  -> 0x20 to MOD_LEFT_SHIFT
        '$'  -> 0x21 to MOD_LEFT_SHIFT
        '%'  -> 0x22 to MOD_LEFT_SHIFT
        '^'  -> 0x23 to MOD_LEFT_SHIFT
        '&'  -> 0x24 to MOD_LEFT_SHIFT
        '*'  -> 0x25 to MOD_LEFT_SHIFT
        '('  -> 0x26 to MOD_LEFT_SHIFT
        ')'  -> 0x27 to MOD_LEFT_SHIFT
        '_'  -> 0x2D to MOD_LEFT_SHIFT
        '+'  -> 0x2E to MOD_LEFT_SHIFT
        '{'  -> 0x2F to MOD_LEFT_SHIFT
        '}'  -> 0x30 to MOD_LEFT_SHIFT
        '|'  -> 0x31 to MOD_LEFT_SHIFT
        ':'  -> 0x33 to MOD_LEFT_SHIFT
        '"'  -> 0x34 to MOD_LEFT_SHIFT
        '~'  -> 0x35 to MOD_LEFT_SHIFT
        '<'  -> 0x36 to MOD_LEFT_SHIFT
        '>'  -> 0x37 to MOD_LEFT_SHIFT
        '?'  -> 0x38 to MOD_LEFT_SHIFT
        else -> null
    }
}
