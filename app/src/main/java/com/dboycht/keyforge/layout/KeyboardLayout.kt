package com.dboycht.keyforge.layout

import android.view.KeyEvent

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
}

/** What a key is for, so the renderer can shade it without knowing key codes. */
internal enum class KeyKind {
    NORMAL,
    MODIFIER,
    ACTION,
}

/** A named layout: rows of keys plus the metadata a picker needs. */
internal data class KeyboardLayout(
    val id: String,
    val displayName: String,
    val rows: List<List<KeySpec>>,
) {
    val allKeys: List<KeySpec> get() = rows.flatten()

    /** Total width in key units (the widest row), used to scale the renderer. */
    val widthUnits: Float
        get() = rows.maxOfOrNull { row -> row.sumOf { it.widthUnits.toDouble() }.toFloat() } ?: 1f

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
                if (key.usage == null) problems += "key '${key.label}' (code ${key.keyCode}) has no HID usage"
            }
        }
        // Every row must be the same total width, otherwise the rendered grid has
        // ragged edges (caught a real mistake in the phone layout while writing it).
        val widths = rows.map { row -> row.sumOf { it.widthUnits.toDouble() } }
        val firstWidth = widths.firstOrNull()
        if (firstWidth != null) {
            widths.forEachIndexed { rowIndex, w ->
                if (kotlin.math.abs(w - firstWidth) > 0.001) {
                    problems += "layout '$id' row $rowIndex is $w units wide, expected $firstWidth"
                }
            }
        }
        return problems
    }
}
