// Raw protocol fixtures intentionally preserve their original single-line payloads.
@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.mod.panelshare

import eu.kanade.tachiyomi.ui.mod.appupdate.ModApkDownload
import eu.kanade.tachiyomi.ui.mod.appupdate.ModRelease
import eu.kanade.tachiyomi.ui.mod.appupdate.ModReleaseNote
import eu.kanade.tachiyomi.ui.mod.appupdate.ModVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModReleaseTest {
    private val id = "abcdefghijk123456"

    @Test fun `skipping releases keeps every missed changelog while downloading the newest APK`() {
        val latest = ModRelease("2.3", id, "New in 2.3")
        val previous =
            listOf(
                ModReleaseNote("2.0", "Old"),
                ModReleaseNote("2.1", "Installed"),
                ModReleaseNote("2.2", "New in 2.2"),
                ModReleaseNote("2.3", "Previous 2.3 notes"),
                ModReleaseNote("2.4", "Future"),
            )
        assertEquals(
            listOf(ModReleaseNote("2.3", "New in 2.3"), ModReleaseNote("2.2", "New in 2.2")),
            latest.notesSince("Mihon Enhanced 2.1-8336", previous),
        )
        assertEquals(listOf(ModReleaseNote("2.3", "New in 2.3")), latest.notesSince("Mihon Enhanced 2.2", previous))
        assertTrue(latest.notesSince("Mihon Enhanced 2.3", previous).isEmpty())
        assertEquals("https://drive.usercontent.google.com/download?id=$id&export=download", latest.downloadUrl)
    }

    @Test fun `release comparison uses numeric versions and accepts installed debug suffix`() {
        assertTrue(ModRelease("2.10", id, "").newerThan("Mihon Enhanced 2.9-8336"))
        assertFalse(ModRelease("2.1", id, "").newerThan("Mihon Enhanced 2.1-8336"))
        assertTrue(ModRelease("2.1.1", id, "").newerThan("Mihon Enhanced 2.1"))
        assertFalse(ModRelease("2.1", id, "").newerThan("unknown"))
        assertTrue(ModVersion.parse("3.0")!!.number > ModVersion.parse("2.999.999")!!.number)
    }

    @Test fun `drive file links normalize without accepting arbitrary or spoofed hosts`() {
        assertEquals(id, ModRelease.driveFileId("https://drive.google.com/file/d/$id/view?usp=sharing"))
        assertEquals(id, ModRelease.driveFileId("https://drive.google.com/uc?id=$id&export=download"))
        for (link in listOf(
            "http://drive.google.com/uc?id=$id",
            "https://drive.google.com.evil.example/uc?id=$id",
            "https://evil.example/$id",
            "https://drive.google.com/drive/folders/$id",
            "https://drive.google.com/uc?id=bad",
        )) {
            assertNull(ModRelease.driveFileId(link))
        }
    }

    @Test fun `confirmation follows only a drive form with the same file identity`() {
        val html = """<form id="download-form" action="https://drive.usercontent.google.com/download"><input name="id" value="$id"><input name="confirm" value="t"><input name="uuid" value="test"></form>"""
        assertEquals(
            "https://drive.usercontent.google.com/download?id=$id&confirm=t&uuid=test",
            ModApkDownload.confirmationLink(html, "https://drive.google.com/", id),
        )
        assertNull(
            ModApkDownload.confirmationLink(
                html.replace("drive.usercontent.google.com", "evil.example"),
                "https://drive.google.com/",
                id,
            ),
        )
        assertNull(ModApkDownload.confirmationLink(html, "https://drive.google.com/", "differentfileid"))
        assertNull(ModApkDownload.confirmationLink("quota exceeded", "https://drive.google.com/", id))
    }

    @Test fun `publisher rejects malformed releases`() {
        for (version in listOf("2", "2.1-debug", "Mihon Enhanced 2.1", "1000.0", "-2.1")) {
            assertThrows(IllegalArgumentException::class.java) { ModRelease(version, id, "") }
        }
        assertThrows(IllegalArgumentException::class.java) { ModRelease("2.2", "../bad", "") }
        assertThrows(IllegalArgumentException::class.java) { ModRelease("2.2", id, "x".repeat(4001)) }
    }
}
