package com.dboycht.keyforge.keyboard

import androidx.compose.ui.graphics.Color
import com.dboycht.keyforge.layout.KeyKind

/**
 * The colours a keyboard is drawn with.
 *
 * Split out of [KeyboardView] for one reason: the keyboard is the part of this app the user stares
 * at for minutes at a time, and "which colours" must be a **data** question, not nine literals buried
 * in a draw call. That also makes the palettes testable - `KeyboardPaletteTest` checks the contrast
 * of every text-on-fill pair, which is the one property of a colour scheme that is objectively
 * measurable (and the one that makes a theme unusable rather than merely ugly).
 *
 * The app chrome (the settings page, dialogs) keeps using the Material theme; only the keyboard
 * itself - keys and the backdrop behind them - follows the user's palette.
 */
internal data class KeyboardPalette(
    val id: String,
    val displayName: String,
    val background: Color,
    val keyFill: Color,
    val keyText: Color,
    val modifierFill: Color,
    val modifierText: Color,
    val actionFill: Color,
    val actionText: Color,
    /** Fill and ink of a key while the finger is on it (and of a latched modifier). */
    val pressedFill: Color,
    val pressedText: Color,
) {
    /** How a key looks, i.e. the per-kind colours a renderer asks for. */
    fun fillFor(kind: KeyKind): Color = when (kind) {
        KeyKind.NORMAL, KeyKind.SPACER -> keyFill
        KeyKind.MODIFIER -> modifierFill
        KeyKind.ACTION -> actionFill
    }

    fun textFor(kind: KeyKind): Color = when (kind) {
        KeyKind.NORMAL, KeyKind.SPACER -> keyText
        KeyKind.MODIFIER -> modifierText
        KeyKind.ACTION -> actionText
    }
}

/**
 * The palettes the picker offers, in display order; the first one is the default.
 *
 * Chosen so the families stay readable in both extremes: a dark scheme has to keep the modifier keys
 * *visible* against the background (they are the darkest keys on a dark keyboard), and a light scheme
 * has to keep them visible against white. The tests enforce the contrast; these values were picked to
 * pass it, and previewed as images before shipping (`LayoutPreviewTest` renders every palette).
 */
internal object KeyboardThemes {

    val NIGHT: KeyboardPalette = KeyboardPalette(
        id = "night",
        displayName = "夜幕（默认）",
        background = Color(0xFF101014),
        keyFill = Color(0xFF3A3A44),
        keyText = Color(0xFFECECF2),
        modifierFill = Color(0xFF4E4E5C),
        modifierText = Color(0xFFF2F2F7),
        actionFill = Color(0xFF2E2E38),
        actionText = Color(0xFFE6E6EE),
        pressedFill = Color(0xFFB9A7FF),
        pressedText = Color(0xFF1A1A1E),
    )

    val LIGHT: KeyboardPalette = KeyboardPalette(
        id = "light",
        displayName = "明亮",
        // Grey, not near-white: white keys on a near-white page would read as one slab, because the
        // keys are separated only by the gap that lets the background show through.
        background = Color(0xFFD5D7DE),
        keyFill = Color(0xFFFFFFFF),
        keyText = Color(0xFF1B1B1F),
        // The modifier keys have to be clearly darker than the backdrop: at #D5D5DE (the first value
        // tried here) they matched the background and read as holes in the keyboard - the preview
        // picture showed it in one glance, and the contrast test now checks all three families.
        modifierFill = Color(0xFFA8ACBA),
        modifierText = Color(0xFF23232A),
        actionFill = Color(0xFF8E93A3),
        actionText = Color(0xFF1B1B1F),
        pressedFill = Color(0xFF5B3FD1),
        pressedText = Color(0xFFFFFFFF),
    )

    val MIDNIGHT: KeyboardPalette = KeyboardPalette(
        id = "midnight",
        displayName = "午夜蓝",
        background = Color(0xFF070D1A),
        keyFill = Color(0xFF22314F),
        keyText = Color(0xFFDCE6F7),
        modifierFill = Color(0xFF33486F),
        modifierText = Color(0xFFEAF1FF),
        // Action keys are one step lighter than the first value tried here (#182640): on this dark
        // backdrop they measured 1.29:1 and read as holes rather than keys.
        actionFill = Color(0xFF223356),
        actionText = Color(0xFFCFDCF0),
        pressedFill = Color(0xFF4C8DFF),
        pressedText = Color(0xFF04101F),
    )

    val SAKURA: KeyboardPalette = KeyboardPalette(
        id = "sakura",
        displayName = "樱花",
        background = Color(0xFF1C1216),
        keyFill = Color(0xFFF6D9E3),
        keyText = Color(0xFF3A1F2B),
        modifierFill = Color(0xFFD9A8BC),
        modifierText = Color(0xFF2E1620),
        actionFill = Color(0xFFB57F96),
        actionText = Color(0xFF24101A),
        // A deep pink rather than a lighter one: on a pink keyboard a "pressed" that is only a
        // brighter pink is too close to the keys it sits on (measured: 1.27:1 against the modifier
        // fill, which the contrast test rejected).
        pressedFill = Color(0xFFC2185B),
        pressedText = Color(0xFFFFFFFF),
    )

    /** Every palette the picker offers, in display order. */
    val all: List<KeyboardPalette> = listOf(NIGHT, LIGHT, MIDNIGHT, SAKURA)

    /** The palette used when the user has never chosen one (or the saved id no longer exists). */
    val default: KeyboardPalette = all.first()

    /** Looks up a palette by id, falling back to [default]. */
    fun byId(id: String?): KeyboardPalette = all.firstOrNull { it.id == id } ?: default
}
