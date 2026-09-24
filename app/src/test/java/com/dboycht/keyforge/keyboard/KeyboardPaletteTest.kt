package com.dboycht.keyforge.keyboard

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the keyboard colour palettes.
 *
 * Colour choice looks like taste, but one part of it is measurable and decides whether a theme is
 * *usable*: the contrast between a key's ink and its fill. A palette whose modifier keys come out
 * unreadable is not a matter of preference, and the author cannot eyeball every palette on the phone
 * - so the numbers are checked here, and the preview images
 * (`LayoutPreviewTest` renders one picture per palette) cover the part that is taste.
 *
 * Thresholds are WCAG 2.1: 4.5:1 for body text, and keys only have to be *distinguishable* from the
 * backdrop (the keyboard is a picture of a keyboard, not text).
 */
class KeyboardPaletteTest {

    private val palettes = KeyboardThemes.all

    @Test
    fun `every palette has a distinct id and a name`() {
        assertTrue("expected several palettes", palettes.size >= 2)
        assertEquals(
            "palette ids must be unique: ${palettes.map { it.id }}",
            palettes.size,
            palettes.map { it.id }.toSet().size,
        )
        palettes.forEach { assertTrue("${it.id} needs a display name", it.displayName.isNotBlank()) }
    }

    @Test
    fun `the default palette is one of the shipped ones`() {
        assertTrue("the default must be offered by the picker", KeyboardThemes.default in palettes)
    }

    @Test
    fun `byId finds every palette and falls back safely`() {
        palettes.forEach { assertEquals(it.id, KeyboardThemes.byId(it.id).id) }
        assertEquals(KeyboardThemes.default.id, KeyboardThemes.byId(null).id)
        assertEquals(KeyboardThemes.default.id, KeyboardThemes.byId("no-such-palette").id)
    }

    @Test
    fun `every key label is readable on every fill`() {
        palettes.forEach { palette ->
            assertContrast("${palette.id} normal", palette.keyText, palette.keyFill, MIN_TEXT_CONTRAST)
            assertContrast("${palette.id} modifier", palette.modifierText, palette.modifierFill, MIN_TEXT_CONTRAST)
            assertContrast("${palette.id} action", palette.actionText, palette.actionFill, MIN_TEXT_CONTRAST)
            assertContrast("${palette.id} pressed", palette.pressedText, palette.pressedFill, MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun `every key family is distinguishable from the backdrop`() {
        // The keys are separated by a gap that shows the backdrop, so a fill that matches the backdrop
        // turns those keys into holes. All three families are checked because the first "light"
        // palette only failed on the modifier keys - the letter keys were white and fine - and that is
        // exactly the case a test that only looks at `keyFill` misses.
        palettes.forEach { palette ->
            assertContrast("${palette.id} normal keys on backdrop", palette.keyFill, palette.background, 1.4)
            assertContrast("${palette.id} modifier keys on backdrop", palette.modifierFill, palette.background, 1.4)
            assertContrast("${palette.id} action keys on backdrop", palette.actionFill, palette.background, 1.4)
        }
    }

    @Test
    fun `the pressed key is visibly different from an unpressed one`() {
        // Otherwise the key highlight - the only feedback that a press registered - is invisible.
        palettes.forEach { palette ->
            assertContrast("${palette.id} pressed vs normal", palette.pressedFill, palette.keyFill, 1.4)
            assertContrast("${palette.id} pressed vs modifier", palette.pressedFill, palette.modifierFill, 1.4)
            assertContrast("${palette.id} pressed vs action", palette.pressedFill, palette.actionFill, 1.4)
        }
    }

    @Test
    fun `the three key families differ from each other`() {
        // The shading is what tells a modifier from a letter key at a glance.
        palettes.forEach { palette ->
            assertContrast("${palette.id} normal vs modifier", palette.keyFill, palette.modifierFill, 1.1)
            assertContrast("${palette.id} modifier vs action", palette.modifierFill, palette.actionFill, 1.1)
        }
    }

    private fun assertContrast(what: String, a: Color, b: Color, minimum: Double) {
        val ratio = contrastRatio(a, b)
        assertTrue("$what: contrast %.2f is below %.2f".format(ratio, minimum), ratio >= minimum)
    }

    /** WCAG 2.1 relative-luminance contrast ratio. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private companion object {
        const val MIN_TEXT_CONTRAST = 4.5
    }
}
