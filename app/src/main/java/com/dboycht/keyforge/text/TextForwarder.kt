package com.dboycht.keyforge.text

/**
 * Turns a string into a sequence of HID key actions.
 *
 * Why this layer exists: the keyboard protocol can only carry **key presses**, so
 * a host receives "Shift+1" and decides for itself that this means "!". Anything
 * without a key (Chinese, emoji, curly quotes) simply cannot be sent - and the
 * honest behaviour is to report it, not to silently drop it (the README states
 * this limit; the UI must not pretend otherwise).
 *
 * The result is data ([ForwardPlan]), so it can be unit tested on the JVM and
 * replayed by the session layer without the UI being involved.
 */
internal object TextForwarder {

    /** One step the session has to perform, in order. */
    internal sealed interface Step {
        /** Press a usage ID (a modifier usage lands in report byte 0). */
        data class Down(val usage: Int, val label: String) : Step

        /** Release a usage ID. */
        data class Up(val usage: Int, val label: String) : Step

        /** Wait [ms] so the host sees down/up as separate events. */
        data class Pause(val ms: Long) : Step
    }

    /** A character that could not be forwarded, with the reason to show the user. */
    internal data class Skipped(val char: Char, val index: Int, val reason: String)

    /** The full plan plus what could not be represented. */
    internal data class ForwardPlan(
        val steps: List<Step>,
        val skipped: List<Skipped>,
    ) {
        val isEmpty: Boolean get() = steps.isEmpty()

        /** How many characters produced key presses (modifiers are not characters). */
        val forwardedCount: Int
            get() = steps.count {
                it is Step.Down && !com.dboycht.keyforge.hid.HidReport.Modifier.isModifierUsage(it.usage)
            }

        /** True when nothing could be forwarded at all. */
        val nothingForwarded: Boolean get() = forwardedCount == 0
    }

    /** Gap inserted between characters so a host does not coalesce two reports. */
    const val INTER_KEY_PAUSE_MS = 24L

    /** Gap between a modifier press and the key press it modifies. */
    const val MODIFIER_PAUSE_MS = 16L

    /**
     * US-layout characters that need Shift, mapped to the base key they sit on.
     * Only characters reachable on a US keyboard are listed; everything else is
     * reported as unmappable.
     */
    private val SHIFTED: Map<Char, Pair<Int, String>> = mapOf(
        '!' to (0x1E to "1"), '@' to (0x1F to "2"), '#' to (0x20 to "3"),
        '$' to (0x21 to "4"), '%' to (0x22 to "5"), '^' to (0x23 to "6"),
        '&' to (0x24 to "7"), '*' to (0x25 to "8"), '(' to (0x26 to "9"),
        ')'.to (0x27 to "0"), '_' to (0x2D to "-"), '+' to (0x2E to "="),
        '{' to (0x2F to "["), '}' to (0x30 to "]"), '|' to (0x31 to "\\"),
        ':' to (0x33 to ";"), '"' to (0x34 to "'"), '~' to (0x35 to "`"),
        '<' to (0x36 to ","), '>' to (0x37 to "."), '?' to (0x38 to "/"),
    )

    /** Unshifted punctuation, keyed by the character itself. */
    private val PLAIN: Map<Char, Pair<Int, String>> = mapOf(
        ' ' to (0x2C to "Space"), '\n' to (0x28 to "Enter"), '\t' to (0x2B to "Tab"),
        '\b' to (0x2A to "Backspace"),
        '-' to (0x2D to "-"), '=' to (0x2E to "="),
        '[' to (0x2F to "["), ']' to (0x30 to "]"), '\\' to (0x31 to "\\"),
        ';' to (0x33 to ";"), '\'' to (0x34 to "'"), '`' to (0x35 to "`"),
        ',' to (0x36 to ","), '.' to (0x37 to "."), '/' to (0x38 to "/"),
    )

    /**
     * Builds the plan for [text].
     *
     * [typed] is the raw text the user sees in the phone-side editor; the plan
     * only covers what can actually be transmitted.
     */
    fun plan(text: String, interKeyPauseMs: Long = INTER_KEY_PAUSE_MS): ForwardPlan {
        val steps = mutableListOf<Step>()
        val skipped = mutableListOf<Skipped>()

        text.forEachIndexed { index, char ->
            val shifted = SHIFTED[char]
            val plain = PLAIN[char]

            // Letters come in two flavours: lowercase needs no modifier, uppercase
            // needs Shift held around the letter (an earlier revision sent the plain
            // letter for 'A' as well, so requested capitals came out lowercase -
            // caught by TextForwarderTest).
            val letter: Triple<Int, String, Boolean>? = when {
                char in 'a'..'z' -> Triple(0x04 + (char - 'a'), char.uppercaseChar().toString(), false)
                char in 'A'..'Z' -> Triple(0x04 + (char - 'A'), char.toString(), true)
                char in '1'..'9' -> Triple(0x1E + (char - '1'), char.toString(), false)
                char == '0' -> Triple(0x27, "0", false)
                else -> null
            }

            when {
                letter != null -> {
                    val (usage, label, needsShift) = letter
                    if (needsShift) {
                        steps += Step.Down(MODIFIER_LEFT_SHIFT, "Shift")
                        steps += Step.Pause(MODIFIER_PAUSE_MS)
                    }
                    steps += Step.Down(usage, label)
                    steps += Step.Pause(interKeyPauseMs)
                    steps += Step.Up(usage, label)
                    if (needsShift) {
                        steps += Step.Up(MODIFIER_LEFT_SHIFT, "Shift")
                    }
                }

                shifted != null -> {
                    steps += Step.Down(MODIFIER_LEFT_SHIFT, "Shift")
                    steps += Step.Pause(MODIFIER_PAUSE_MS)
                    steps += Step.Down(shifted.first, shifted.second)
                    steps += Step.Pause(interKeyPauseMs)
                    steps += Step.Up(shifted.first, shifted.second)
                    steps += Step.Up(MODIFIER_LEFT_SHIFT, "Shift")
                }

                plain != null -> {
                    steps += Step.Down(plain.first, plain.second)
                    steps += Step.Pause(interKeyPauseMs)
                    steps += Step.Up(plain.first, plain.second)
                }

                else -> skipped += Skipped(char, index, reasonFor(char))
            }

            if (letter != null || shifted != null || plain != null) {
                steps += Step.Pause(interKeyPauseMs)
            }
        }

        return ForwardPlan(steps = steps, skipped = skipped)
    }

    /** A short, user-facing reason why a character cannot be sent. */
    private fun reasonFor(char: Char): String = when {
        char.code in 0x4E00..0x9FFF -> "中文字符没有 HID 扫描码（需在目标设备上用它的输入法输入）"
        char.code in 0x3000..0x303F || char.code in 0xFF00..0xFFEF -> "全角标点没有 HID 扫描码"
        char.code >= 0xD800 && char.code <= 0xDFFF -> "代理对字符（emoji 等）无法用键盘协议发送"
        char.isWhitespace() -> "该空白字符没有对应按键（仅支持空格/制表/回车）"
        else -> "该字符在 US 键盘布局上无对应按键"
    }

    private const val MODIFIER_LEFT_SHIFT = 0xE1
}
