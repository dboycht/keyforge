package com.dboycht.keyforge.layout

import kotlin.math.abs

/**
 * Vertical geometry of a keyboard layout.
 *
 * Only the vertical axis needs arithmetic: the renderer lays rows out with
 * `Row(weight = widthUnits)`, so horizontal positions come from Compose. What it
 * *cannot* get for free is "which row is this finger on", which is what multi
 * touch needs when two fingers rest on two different rows at once.
 *
 * Pure functions (no Compose, no Android) so the arithmetic is unit-testable.
 */
internal object KeyboardGeometry {

    /** Vertical gap between rows, as a fraction of one row's height. */
    const val ROW_GAP_FRACTION = 0.06f

    /** Height of a single row plus the gap that follows it. */
    private data class RowMetrics(val rowHeightPx: Float, val gapPx: Float)

    private fun metrics(layout: KeyboardLayout, heightPx: Float): RowMetrics {
        val rows = layout.rows.size.coerceAtLeast(1)
        // Solve h*rows + gap*(rows-1) = height, with gap = ROW_GAP_FRACTION*h.
        val h = heightPx / (rows + ROW_GAP_FRACTION * (rows - 1)).coerceAtLeast(1f)
        return RowMetrics(h, h * ROW_GAP_FRACTION)
    }

    /** Height in pixels of one row for a keyboard [heightPx] tall. */
    fun rowHeightPx(layout: KeyboardLayout, heightPx: Float): Float =
        metrics(layout, heightPx).rowHeightPx

    /**
     * Index of the row containing [y] (pixels, relative to the keyboard's top),
     * clamped to the layout's range; `null` only when the layout has no rows.
     *
     * Clamping rather than rejecting is deliberate: a finger slightly below the
     * last row is still on the keyboard, and dropping the press would look like a
     * dead key.
     */
    fun rowIndexAt(layout: KeyboardLayout, y: Float, heightPx: Float): Int? {
        if (layout.rows.isEmpty()) return null
        val m = metrics(layout, heightPx)
        var top = 0f
        layout.rows.forEachIndexed { index, _ ->
            val bottom = top + m.rowHeightPx
            if (y <= bottom) return index
            top = bottom + m.gapPx
        }
        // Below the last row: clamp to the last row instead of dropping the press.
        return layout.rows.lastIndex
    }

    /**
     * Horizontal share of the row that each key occupies, expressed as
     * `(startFraction, endFraction)` pairs. Used by tests to prove that a 1.5u key
     * really is 1.5 times a 1u key; the renderer gets the same effect from
     * `Row` weights.
     */
    fun keyFractions(row: List<KeySpec>): List<Pair<Float, Float>> {
        val total = row.sumOf { it.widthUnits.toDouble() }.toFloat()
        if (total <= 0f) return emptyList()
        var cursor = 0f
        return row.map { key ->
            val end = cursor + key.widthUnits / total
            val span = cursor to end
            cursor = end
            span
        }
    }

    /** True when a key's width is [expectedUnits] key units (tolerance for Float math). */
    fun isWidthOf(key: KeySpec, expectedUnits: Float): Boolean =
        abs(key.widthUnits - expectedUnits) < 0.001f
}
