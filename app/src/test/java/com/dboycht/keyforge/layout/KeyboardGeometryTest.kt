package com.dboycht.keyforge.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the vertical geometry used by multi-touch hit testing.
 *
 * The renderer gets horizontal positions from `Row(weight = ...)`; only the
 * vertical axis needs arithmetic, and an off-by-one there means a finger lands on
 * the wrong row - visible as "the key above what I pressed lights up".
 */
class KeyboardGeometryTest {

    private val layout = Keyboards.PHONE_STYLE
    private val heightPx = 500f

    @Test
    fun `row height divides the height across the rows plus gaps`() {
        val rowHeight = KeyboardGeometry.rowHeightPx(layout, heightPx)
        val rows = layout.rows.size
        val total = rowHeight * rows + rowHeight * KeyboardGeometry.ROW_GAP_FRACTION * (rows - 1)
        assertEquals(heightPx.toDouble(), total.toDouble(), 0.5)
    }

    @Test
    fun `top of the keyboard is the first row`() {
        assertEquals(0, KeyboardGeometry.rowIndexAt(layout, 0f, heightPx))
        assertEquals(0, KeyboardGeometry.rowIndexAt(layout, 1f, heightPx))
    }

    @Test
    fun `middle of each row maps to that row`() {
        val rowHeight = KeyboardGeometry.rowHeightPx(layout, heightPx)
        val gap = rowHeight * KeyboardGeometry.ROW_GAP_FRACTION
        var top = 0f
        layout.rows.indices.forEach { index ->
            val middle = top + rowHeight / 2f
            assertEquals("row $index middle", index, KeyboardGeometry.rowIndexAt(layout, middle, heightPx))
            top += rowHeight + gap
        }
    }

    @Test
    fun `below the last row clamps to the last row instead of dropping the press`() {
        assertEquals(
            layout.rows.lastIndex,
            KeyboardGeometry.rowIndexAt(layout, heightPx * 2f, heightPx),
        )
    }

    @Test
    fun `empty layout has no rows`() {
        assertNull(KeyboardGeometry.rowIndexAt(KeyboardLayout("empty", "empty", emptyList()), 10f, heightPx))
    }

    @Test
    fun `key fractions reflect the unit widths`() {
        val row = listOf(
            KeySpec("ctrl", android.view.KeyEvent.KEYCODE_CTRL_LEFT, 1.25f, null, KeyKind.MODIFIER),
            KeySpec("space", android.view.KeyEvent.KEYCODE_SPACE, 4f),
            KeySpec("enter", android.view.KeyEvent.KEYCODE_ENTER, 1.25f, null, KeyKind.ACTION),
        )
        val fractions = KeyboardGeometry.keyFractions(row)
        assertEquals(3, fractions.size)
        assertEquals(0f, fractions[0].first, 0.0001f)
        // space spans 4/6.5 of the row.
        assertEquals(4f / 6.5f, fractions[1].second - fractions[1].first, 0.0001f)
        assertEquals(1f, fractions[2].second, 0.0001f)
    }

    @Test
    fun `isWidthOf tolerates float noise`() {
        val key = KeySpec("space", android.view.KeyEvent.KEYCODE_SPACE, 6.25f)
        assertTrue(KeyboardGeometry.isWidthOf(key, 6.25f))
    }
}
