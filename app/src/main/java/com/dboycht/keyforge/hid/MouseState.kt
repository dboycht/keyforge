package com.dboycht.keyforge.hid

/**
 * The state machine behind the mouse reports: which buttons are currently held.
 *
 * Counterpart of [KeyboardState] for Report ID 2. Every operation returns the
 * report the session should put on the wire, so a down/up pair can never
 * disagree about what is held - the same shape that keeps the keyboard path
 * unit-testable.
 *
 * Deliberately forgiving semantics: pressing a held button or releasing one
 * that is not held are no-ops that still return the current report, because
 * duplicate/cancelled UI events are normal and must not corrupt the bitmap.
 */
internal class MouseState {

    private var held: Int = 0

    /** Bitmap of currently held buttons (see [MouseReport.Button]). */
    val buttons: Int get() = held

    /** True when no button is held. */
    val isIdle: Boolean get() = held == 0

    /**
     * Presses [button] (one [MouseReport.Button] bit) and returns the report to
     * send. Pressing an already-held button keeps the bitmap unchanged.
     */
    fun press(button: Int): MouseReport {
        requireValid(button)
        held = held or button
        return MouseReport.ofButtons(held)
    }

    /**
     * Releases [button] and returns the report to send. Releasing a button that
     * was not held keeps the bitmap unchanged.
     */
    fun release(button: Int): MouseReport {
        requireValid(button)
        held = held and button.inv()
        return MouseReport.ofButtons(held)
    }

    /** Report carrying [x]/[y] movement together with the current buttons. */
    fun move(x: Int, y: Int): MouseReport = MouseReport.of(held, x, y, 0)

    /** Report carrying a [wheel] delta together with the current buttons. */
    fun scroll(wheel: Int): MouseReport = MouseReport.of(held, 0, 0, wheel)

    /** Clears every button and returns the idle report. */
    fun releaseAll(): MouseReport {
        held = 0
        return MouseReport.idle()
    }

    /** Clears every button without producing a report (session teardown). */
    fun reset() {
        held = 0
    }

    private fun requireValid(button: Int) {
        require(MouseReport.Button.isValid(button)) {
            "not a single button bit: 0x%02X".format(button)
        }
    }
}
