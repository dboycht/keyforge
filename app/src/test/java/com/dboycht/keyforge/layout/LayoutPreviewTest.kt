package com.dboycht.keyforge.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.dboycht.keyforge.keyboard.KeyboardPalette
import com.dboycht.keyforge.keyboard.KeyboardThemes
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Draws every shipped layout to an SVG so its shape can be reviewed without a phone.
 *
 * Why a picture: a layout is a table of widths, and a table does not show whether the rows read as a
 * keyboard or as a spreadsheet - which is exactly how two broken layouts survived review (the deleted
 * `full` had 0.5-unit keys; the deleted `split` was missing l, o and p). The picture is drawn from the
 * same [KeyboardLayout] data the keyboard renders from, so it cannot drift: change a key width and the
 * picture changes with it. It is not a screenshot that has to be kept up to date.
 *
 * Why SVG rather than PNG: Android unit tests compile with `-no-jdk`, so `java.awt` and
 * `javax.imageio` do not exist here at all; text is the only thing this side can emit. A browser
 * renders the output, and `msedge --headless --screenshot` turns it into a PNG when one is needed.
 *
 * This is a **shape** check only. Whether a key feels right under a thumb is the user's call
 * (HANDOVER.md §5: UI verification is done by the user, never by injecting input).
 *
 * Output: `app/build/layout-preview/<id>.svg` plus `index.html` listing all of them.
 */
class LayoutPreviewTest {

    /** Pixels per key unit. Wide enough that a 15-unit layout is 390px across. */
    private val unit = 26
    private val rowHeight = 30
    private val rowGap = 3
    private val padding = 12
    private val header = 44

    /** The app's own label rule (`KeyboardView.labelSizeFor`): 0.36 of the key unit, 9..17sp. */
    private val appLabelSize = (unit * 0.36f).coerceIn(9f, 17f).toInt()

    @Test
    fun `renders every layout to an svg preview`() {
        val dir = File("build/layout-preview")
        assertTrue("cannot create $dir", dir.isDirectory || dir.mkdirs())

        Keyboards.all.forEach { layout ->
            val file = File(dir, "${layout.id}.svg")
            file.writeText(svg(layout, KeyboardThemes.default), Charsets.UTF_8)
            assertTrue("${file.name} was not written", file.length() > 0L)
        }

        // One extra picture per palette, on the same layout, so a colour scheme can be judged the
        // same way a layout is - by looking at it - without involving the phone.
        KeyboardThemes.all.forEach { palette ->
            val file = File(dir, "theme-${palette.id}.svg")
            file.writeText(svg(Keyboards.PC_60, palette), Charsets.UTF_8)
            assertTrue("${file.name} was not written", file.length() > 0L)
        }

        val index = File(dir, "index.html")
        index.writeText(indexHtml(), Charsets.UTF_8)
        assertTrue("index.html was not written", index.length() > 0L)

        println("layout previews written to ${dir.absoluteFile}")
    }

    private fun svg(layout: KeyboardLayout, palette: KeyboardPalette): String {
        val rows = layout.rows.size
        val keyboardWidth = (layout.widthUnits * unit).toInt() + padding * 2
        // Two header lines, and the canvas is widened if a line needs more room than the keys do:
        // a clipped title is a worse preview than a slightly wider one.
        val titleLine = "${layout.id} · ${layout.displayName}"
        val sizeLine = "${layout.widthUnits.toInt()} 格宽 × $rows 行" +
            if (layout.textCapable) "" else " · 非打字布局（游戏用）"
        val width = maxOf(keyboardWidth, maxOf(estimateWidth(titleLine, 12), estimateWidth(sizeLine, 11)) + padding * 2)
        val height = header + rows * rowHeight + (rows - 1) * rowGap + padding
        val sb = StringBuilder()
        sb.append(
            """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" """ +
                """viewBox="0 0 $width $height" font-family="Segoe UI, Arial, sans-serif">""",
        )
        sb.append("""<rect width="$width" height="$height" fill="${hex(palette.background)}"/>""")
        sb.append(
            text(
                x = padding,
                y = padding + 13,
                content = titleLine,
                size = 12,
                // The palette's own key ink, so the title stays readable on a light palette too.
                color = hex(palette.keyText),
                anchor = "start",
                weight = "bold",
            ),
        )
        sb.append(
            text(
                x = padding,
                y = padding + 27,
                content = sizeLine,
                size = 11,
                color = hex(palette.keyText),
                anchor = "start",
            ),
        )

        layout.rows.forEachIndexed { rowIndex, row ->
            val y = padding + header + rowIndex * (rowHeight + rowGap)
            var x = padding + (layout.offsetFor(rowIndex) * unit).toInt()
            row.forEach { key ->
                val rawWidth = (key.widthUnits * unit).toInt()
                val boxWidth = rawWidth - 2
                when (key.kind) {
                    // The gap of a split layout: drawn as a faint band so the two halves are visible
                    // here, even though the real keyboard draws nothing at all. Tinted from the
                    // palette so it is visible on a light scheme as well as a dark one.
                    KeyKind.SPACER ->
                        sb.append(
                            """<rect x="$x" y="$y" width="$boxWidth" height="$rowHeight" rx="5" """ +
                                """fill="${hex(palette.modifierFill)}" fill-opacity="0.35"/>""",
                        )
                    else -> {
                        val fill = hex(palette.fillFor(key.kind))
                        val ink = hex(palette.textFor(key.kind))
                        sb.append("""<rect x="$x" y="$y" width="$boxWidth" height="$rowHeight" rx="5" fill="$fill"/>""")
                        val center = x + boxWidth / 2
                        val shift = key.shiftLabel
                        val labelY = if (shift == null) y + rowHeight / 2 + 4 else y + rowHeight / 2
                        sb.append(
                            text(
                                x = center,
                                y = labelY,
                                content = key.label,
                                // The app's own rule (KeyboardView.labelSizeFor): the label scales
                                // with the key unit. Only shrink below it if it still would not fit.
                                size = fittedSize(key.label, boxWidth - 2, maxSize = appLabelSize),
                                color = ink,
                            ),
                        )
                        if (shift != null) {
                            sb.append(
                                text(
                                    x = center,
                                    y = labelY + 11,
                                    content = shift,
                                    size = fittedSize(
                                        shift,
                                        boxWidth - 2,
                                        maxSize = (appLabelSize * 0.68f).toInt().coerceAtLeast(5),
                                        minSize = 5,
                                    ),
                                    color = ink,
                                    opacity = "0.7",
                                ),
                            )
                        }
                    }
                }
                x += rawWidth
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Largest font size whose text still fits [maxWidth], never below 8.
     *
     * Deliberately the same rule the app uses (`KeyboardView.labelSizeFor`: 0.62 em per glyph, 8sp
     * floor). The first version of this preview invented its own rule and shrank a one-unit `Shift`
     * to 8px, which *looked* like a broken layout; a preview that disagrees with the renderer sends
     * the reader after the wrong bug (ERROR.md E20 §preview).
     */
    private fun fittedSize(text: String, maxWidth: Int, maxSize: Int, minSize: Int = 8): Int {
        val byWidth = (maxWidth / (0.62f * text.length.coerceAtLeast(1))).toInt()
        return byWidth.coerceIn(minSize, maxSize)
    }

    /** Rough text width: CJK and other wide glyphs take one em, the rest about 0.6 em. */
    private fun estimateWidth(text: String, size: Int): Int =
        text.sumOf { ch -> if (ch.code > 0x2E80) size else (size * 0.6f).toInt() }

    private fun text(
        x: Int,
        y: Int,
        content: String,
        size: Int,
        color: String,
        anchor: String = "middle",
        weight: String = "normal",
        opacity: String? = null,
    ): String {
        val alpha = if (opacity == null) "" else """ opacity="$opacity""""
        return """<text x="$x" y="$y" font-size="$size" fill="$color" text-anchor="$anchor" """ +
            """font-weight="$weight"$alpha>${escape(content)}</text>"""
    }

    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** `#RRGGBB` for a Compose colour. `toArgb` drops any alpha, which these palettes do not use. */
    private fun hex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

    private fun indexHtml(): String {
        val items = Keyboards.all.joinToString("\n") { layout ->
            """  <section><h2>${escape(layout.id)} · ${escape(layout.displayName)}</h2>""" + "\n" +
                """    <img src="${layout.id}.svg" alt="${escape(layout.id)}"></section>"""
        }
        val themeItems = KeyboardThemes.all.joinToString("\n") { palette ->
            """  <section><h2>配色 · ${escape(palette.displayName)} (${escape(palette.id)})""" +
                """ —— 以「电脑全键盘」为例</h2>""" + "\n" +
                """    <img src="theme-${palette.id}.svg" alt="${escape(palette.id)}"></section>"""
        }
        return """
            |<!doctype html>
            |<html lang="zh">
            |<head>
            |  <meta charset="utf-8">
            |  <title>keyforge 布局与配色预览</title>
            |  <style>
            |    body { background: #0D0D10; color: #E8E4EC; font-family: "Segoe UI", Arial, sans-serif; margin: 24px; }
            |    h1 { font-size: 18px; }
            |    h2 { font-size: 13px; color: #A9A9B6; font-weight: normal; margin: 18px 0 6px; }
            |    img { display: block; }
            |  </style>
            |</head>
            |<body>
            |  <h1>keyforge 预览（全部由 layout/Keyboards.kt 与 keyboard/KeyboardPalette.kt 的数据生成，改数据即改图）</h1>
            |$items
            |$themeItems
            |</body>
            |</html>
            |""".trimMargin()
    }
}
