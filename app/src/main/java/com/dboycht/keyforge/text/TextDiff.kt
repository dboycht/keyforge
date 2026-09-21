package com.dboycht.keyforge.text

/**
 * Works out what a text field gained or lost between two edits.
 *
 * Why this exists: the forwarding box must send **each character as it is typed**, not the whole
 * field again. A naive "send the current text on every change" would retype the entire line after
 * every keystroke (type `h`, `he`, `hel`, `hell`, ... -> the host receives `hhehelhell...`).
 *
 * The types are pure data and the arithmetic is pure logic, so it is unit tested rather than
 * verified by typing on a device.
 */
internal object TextDiff {

    /** What happened in one edit. */
    internal data class Change(
        /** Text appended at the end (the common case: the user typed). */
        val inserted: String,
        /** How many characters were removed from the end. */
        val deletedFromEnd: Int,
        /**
         * True when the edit cannot be expressed as "append at the end" / "backspace at the end" -
         * for example the caret jumped into the middle of the line, or the whole field was
         * replaced. Callers should then fall back to a slower path instead of guessing.
         */
        val needsResync: Boolean,
    ) {
        val isBackspace: Boolean get() = inserted.isEmpty() && deletedFromEnd > 0
        val isAppend: Boolean get() = inserted.isNotEmpty() && deletedFromEnd == 0
        val isNoop: Boolean get() = inserted.isEmpty() && deletedFromEnd == 0 && !needsResync
    }

    /**
     * Classifies the edit that turned [old] into [new].
     *
     * Deliberately conservative: anything that is not a clean append or a clean backspace is
     * reported as [Change.needsResync] rather than approximated. Sending the wrong keystrokes to
     * someone's host is worse than sending none.
     */
    fun between(old: String, new: String): Change {
        if (old == new) return Change("", 0, needsResync = false)

        // Append: the old text is a prefix of the new one.
        if (new.startsWith(old)) {
            return Change(new.substring(old.length), 0, needsResync = false)
        }

        // Backspace: the new text is a prefix of the old one.
        if (old.startsWith(new)) {
            return Change("", old.length - new.length, needsResync = false)
        }

        // Anything else - the caret moved into the middle, or an IME replaced a composing region -
        // cannot be reproduced by typing at the end of the line. Report it honestly so the caller
        // can resync instead of sending keystrokes that do not match what the user sees.
        return Change("", 0, needsResync = true)
    }
}
