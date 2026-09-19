package com.dboycht.keyforge.text

import com.dboycht.keyforge.hid.HidReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the text -> key-press translation.
 *
 * The interesting cases are the ones a keyboard protocol genuinely cannot carry:
 * CJK, emoji and full-width punctuation must be REPORTED, never silently dropped,
 * because the app's own README promises the user is told rather than misled.
 */
class TextForwarderTest {

    private fun downs(plan: TextForwarder.ForwardPlan) =
        plan.steps.filterIsInstance<TextForwarder.Step.Down>().map { it.usage }

    private fun ups(plan: TextForwarder.ForwardPlan) =
        plan.steps.filterIsInstance<TextForwarder.Step.Up>().map { it.usage }

    @Test
    fun `lowercase letters are one down and one up each`() {
        val plan = TextForwarder.plan("ab")
        assertEquals(listOf(0x04, 0x05), downs(plan))
        assertEquals(listOf(0x04, 0x05), ups(plan))
        assertTrue(plan.skipped.isEmpty())
        assertEquals(2, plan.forwardedCount)
    }

    @Test
    fun `uppercase letters press shift around the letter`() {
        val plan = TextForwarder.plan("A")
        // Shift down, A down, A up, Shift up.
        assertEquals(listOf(0xE1, 0x04, 0x04, 0xE1), downs(plan) + ups(plan))
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `a shifted symbol goes through the same shift sequence`() {
        val plan = TextForwarder.plan("!")
        assertTrue(downs(plan).contains(0xE1))
        assertTrue(downs(plan).contains(0x1E))
        assertTrue(ups(plan).contains(0x1E))
        assertTrue(ups(plan).contains(0xE1))
        // The modifier must be pressed before the key and released after it.
        val order = plan.steps.mapNotNull {
            when (it) {
                is TextForwarder.Step.Down -> "down 0x%02X".format(it.usage)
                is TextForwarder.Step.Up -> "up 0x%02X".format(it.usage)
                else -> null
            }
        }
        assertEquals(listOf("down 0xE1", "down 0x1E", "up 0x1E", "up 0xE1"), order)
    }

    @Test
    fun `digits map to the number row including zero`() {
        val plan = TextForwarder.plan("10")
        assertEquals(listOf(0x1E, 0x27), downs(plan))
    }

    @Test
    fun `whitespace and editing characters are mappable`() {
        val plan = TextForwarder.plan(" \n\t")
        assertTrue(plan.skipped.isEmpty())
        assertEquals(listOf(0x2C, 0x28, 0x2B), downs(plan))
    }

    @Test
    fun `every plan step is paired so no key is left down`() {
        val plan = TextForwarder.plan("Hello, World! 123")
        assertEquals(downs(plan).sorted(), ups(plan).sorted())
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `chinese characters are reported not dropped`() {
        val plan = TextForwarder.plan("你好")
        assertEquals(2, plan.skipped.size)
        assertTrue(plan.skipped.all { it.reason.contains("HID 扫描码") })
        assertTrue(plan.steps.isEmpty())
        assertTrue(plan.nothingForwarded)
    }

    @Test
    fun `emoji and full-width punctuation are reported with their own reasons`() {
        val emoji = TextForwarder.plan("\uD83D\uDE00")
        assertTrue("surrogate pairs must be reported", emoji.skipped.isNotEmpty())

        val fullWidth = TextForwarder.plan("，")
        assertEquals(1, fullWidth.skipped.size)
        assertTrue(fullWidth.skipped.first().reason.contains("全角"))
    }

    @Test
    fun `mixed text forwards the ASCII part and reports the rest`() {
        val plan = TextForwarder.plan("ok 中文 end")
        assertTrue(plan.forwardedCount > 0)
        assertEquals(2, plan.skipped.size)
        // The forwardable characters still produce a complete plan.
        assertEquals(downs(plan).sorted(), ups(plan).sorted())
    }

    @Test
    fun `skipped entries carry their original index`() {
        val plan = TextForwarder.plan("a中b")
        assertEquals(1, plan.skipped.single().index)
        assertEquals('中', plan.skipped.single().char)
    }

    @Test
    fun `pauses are inserted so a host does not coalesce reports`() {
        val plan = TextForwarder.plan("ab")
        val pauses = plan.steps.filterIsInstance<TextForwarder.Step.Pause>()
        assertTrue("expected pauses between characters", pauses.isNotEmpty())
        assertTrue(pauses.all { it.ms > 0 })
    }

    @Test
    fun `empty text produces an empty plan`() {
        val plan = TextForwarder.plan("")
        assertTrue(plan.isEmpty)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `shift usage used for shifted characters is a modifier usage`() {
        val plan = TextForwarder.plan("@")
        val shiftDown = plan.steps.filterIsInstance<TextForwarder.Step.Down>()
            .first { it.usage == 0xE1 }
        assertTrue(HidReport.Modifier.isModifierUsage(shiftDown.usage))
        assertFalse(HidReport.Modifier.isModifierUsage(0x1F))
    }
}
