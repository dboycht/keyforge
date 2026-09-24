package com.dboycht.keyforge.layout

/**
 * One key in a keyboard layout.
 *
 * This is **data, not a widget**: a layout is a list of rows of these, and the
 * renderer knows nothing about which key means what. That is what makes "add a
 * third layout" a data change instead of a UI rewrite.
 *
 * [widthUnits] is in "key units" (1.0 = a letter key), the same convention real
 * keyboard specs use, so a 60% layout is expressed the way its spec reads.
 */
internal data class KeySpec(
    val label: String,
    val keyCode: Int,
    val widthUnits: Float = 1f,
    /** Optional second label (the shifted character), rendered smaller. */
    val shiftLabel: String? = null,
    /** Visual grouping only; the renderer uses it to pick a background shade. */
    val kind: KeyKind = KeyKind.NORMAL,
) {
    /** HID usage ID for this key, resolved through the single mapping table. */
    val usage: Int?
        get() = com.dboycht.keyforge.hid.HidKeyMap.usageFor(keyCode)

    val isModifier: Boolean
        get() = com.dboycht.keyforge.hid.HidKeyMap.isModifierKeyCode(keyCode)

    /**
     * What holding this key should do.
     *
     * **This is the fix for "held keys behave like a pulse, not like a real keyboard"**: an ordinary
     * key now goes down and *stays* down while the finger is on it ([com.dboycht.keyforge.keyboard.
     * RepeatMode.HOLD]), and the host generates the repeated characters itself - exactly what a USB
     * keyboard does. Previously every repeat tick sent a release, so a game saw "tapped repeatedly"
     * and a held movement key made the character stutter.
     *
     * Two exceptions keep the old "one keystroke" semantics:
     * - the **modifier keys**, which latch here - repeating Shift/Ctrl is meaningless and they never
     *   travel in a key slot anyway;
     * - the **spacers**, which are not keys at all.
     */
    val repeatMode: com.dboycht.keyforge.keyboard.RepeatMode
        get() = if (!supportsAutoRepeat) {
            com.dboycht.keyforge.keyboard.RepeatMode.ONESHOT
        } else {
            com.dboycht.keyforge.keyboard.RepeatMode.HOLD
        }

    /**
     * Whether holding this key should auto-repeat at all.
     *
     * The timing that governs it lives in [AUTO_REPEAT_DELAY_MS] / [AUTO_REPEAT_INTERVAL_MS]; the
     * *behaviour* (hold versus pulse) lives in [repeatMode].
     */
    val supportsAutoRepeat: Boolean
        get() = !isModifier && kind != KeyKind.SPACER

    companion object {
        /** Time the key must stay down before it starts repeating. */
        const val AUTO_REPEAT_DELAY_MS = 400L

        /** Gap between repeats once repeating has started. */
        const val AUTO_REPEAT_INTERVAL_MS = 60L
    }
}

/** What a key is for, so the renderer can shade it without knowing key codes. */
internal enum class KeyKind {
    NORMAL,
    MODIFIER,
    ACTION,

    /**
     * A gap, not a key: it occupies width so the keys either side line up (used to split a
     * layout into a left-hand and a right-hand half) and draws nothing.
     *
     * It still needs a key code because [KeySpec] requires one; [KeySpec.usage] resolves it,
     * but the renderer never draws or sends it.
     */
    SPACER,
}

/** A named layout: rows of keys plus the metadata a picker needs. */
internal data class KeyboardLayout(
    val id: String,
    val displayName: String,
    val rows: List<List<KeySpec>>,
    /**
     * How far each row is indented, in key units, so the rows are **staggered** instead of aligned
     * into a grid.
     *
     * This is what makes a keyboard look like a keyboard rather than a spreadsheet: real keyboards
     * (and every phone keyboard) shift each row sideways so the keys sit under the natural reach of
     * the fingers, and the two ends of a row can then be wider keys. A layout built from perfectly
     * left-aligned rows reads as a "square grid, hard to operate" - which is what the user reported.
     *
     * No shipped layout currently uses a non-zero indent (the staggered phone keyboard was removed
     * at the user's request), but the mechanism stays: it is what the renderer and the picker's
     * thumbnail both honour, and re-adding a staggered layout is then a pure data change.
     *
     * Empty means "no stagger": every row starts at x=0, which is right for the layouts that really
     * are grids, like the 60% keyboard.
     */
    val rowOffsets: List<Float> = emptyList(),
) {
    val allKeys: List<KeySpec> get() = rows.flatten()

    /** Indent of [rowIndex], defaulting to none. */
    fun offsetFor(rowIndex: Int): Float = rowOffsets.getOrElse(rowIndex) { 0f }

    /**
     * Total width in key units (the widest row **including its indent**), used to scale the
     * renderer.
     */
    val widthUnits: Float
        get() = rows.indices.maxOfOrNull { index ->
            offsetFor(index) + rows[index].sumOf { it.widthUnits.toDouble() }.toFloat()
        } ?: 1f

    /**
     * Structural check used by the unit tests.
     *
     * Note on duplicates: the same HID usage appearing twice is legitimate - the
     * 60% layout has both Shift keys and both Alt/Win keys, which are distinct key
     * codes that intentionally resolve to the same report bit. What must hold is
     * that a row is non-empty, widths are positive, and every key is mappable.
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (rows.isEmpty()) problems += "layout '$id' has no rows"
        rows.forEachIndexed { rowIndex, row ->
            if (row.isEmpty()) problems += "layout '$id' row $rowIndex is empty"
            row.forEach { key ->
                if (key.widthUnits <= 0f) problems += "key '${key.label}' has width ${key.widthUnits}"
                // A spacer is a gap, not a key: it has no key code to map, on purpose.
                if (key.kind != KeyKind.SPACER && key.usage == null) {
                    problems += "key '${key.label}' (code ${key.keyCode}) has no HID usage"
                }
            }
        }
        // Every row must be the same total width **including its indent**, otherwise the renderer
        // squeezes the wider rows and the keys change size from row to row. This check has caught
        // real mistakes three times while layouts were being written (in the phone, staggered and
        // split layouts), so it earns its keep even with only three layouts shipping.
        val widths = rows.indices.map { index ->
            offsetFor(index) + rows[index].sumOf { it.widthUnits.toDouble() }
        }
        val firstWidth = widths.firstOrNull()
        if (firstWidth != null) {
            widths.forEachIndexed { rowIndex, w ->
                if (kotlin.math.abs(w - firstWidth) > 0.001) {
                    problems += "layout '$id' row $rowIndex is $w units wide (with indent " +
                        "${offsetFor(rowIndex)}), expected $firstWidth"
                }
            }
        }
        rowOffsets.forEachIndexed { index, offset ->
            if (offset < 0f) problems += "layout '$id' row $index has a negative indent $offset"
        }
        return problems
    }
}
