package com.dboycht.keyforge.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the text of the About dialog.
 *
 * Two failure modes this catches, both of which have bitten this workspace before:
 * - **markdown markers leaking into user-visible text** (a `**bold**` shows up literally in a dialog,
 *   because nothing renders markdown there), and
 * - **stale support claims**: the app must not promise a host it has never worked with, so the host
 *   line is checked to still say Windows 11 + MediaTek is *not* usable while the README says so.
 *
 * Links are checked to be https and on this repository, so a typo cannot send users somewhere else.
 */
class AboutInfoTest {

    @Test
    fun `every line is non-empty and free of markdown markers`() {
        AboutInfo.userVisibleLines.forEach { line ->
            assertTrue("a line is blank", line.isNotBlank())
            assertTrue("$line contains **", !line.contains("**"))
            assertTrue("$line contains a backtick", !line.contains("`"))
            assertTrue("$line contains ~~", !line.contains("~~"))
            assertTrue("$line starts like a heading", !line.trimStart().startsWith("#"))
            assertTrue("$line contains a markdown link", !line.contains("]("))
        }
    }

    @Test
    fun `the hosts line keeps the measured verdicts`() {
        // Only what was really measured may be claimed: iPad works, Windows 11 + MediaTek does not,
        // the rest is explicitly untested. If a later round measures another host, this test is where
        // the claim is updated - deliberately, rather than by editing prose nobody reads.
        assertTrue("iPad must be reported as working", AboutInfo.HOSTS.contains("iPad"))
        assertTrue("Windows must be reported as not working", AboutInfo.HOSTS.contains("不可用"))
        assertTrue("untested hosts must say so", AboutInfo.HOSTS.contains("未实测"))
    }

    @Test
    fun `the limitation says non-ASCII cannot be sent`() {
        // The honesty rule: an HID keyboard carries scan codes only. The dialog must not imply the app
        // can type Chinese itself.
        assertTrue("emoji/Chinese limitation missing", AboutInfo.LIMITATION.contains("emoji"))
        assertTrue(
            "the text must point at the host's own input method",
            AboutInfo.LIMITATION.contains("输入法"),
        )
    }

    @Test
    fun `links are https and point at this repository`() {
        listOf(AboutInfo.REPO_URL, AboutInfo.RELEASES_URL).forEach { url ->
            assertTrue("$url is not https", url.startsWith("https://"))
            assertTrue("$url is not on the project repository", url.startsWith("https://github.com/dboycht/keyforge"))
        }
        assertTrue("the releases link should be the latest release", AboutInfo.RELEASES_URL.endsWith("/latest"))
    }
}
