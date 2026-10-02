package app.sift.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    private fun release(tag: String = "v0.2.0", flags: String = "", assets: Boolean = true): String = """
        {"tag_name":"$tag","html_url":"https://github.com/semi-column/sift/releases/tag/$tag",
        "body":"New features",$flags "assets": ${if (assets) """[{"name":"Sift.apk","size":100,"browser_download_url":"https://github.com/semi-column/sift/releases/download/$tag/Sift.apk"}]""" else "[]"}}
    """.trimIndent()

    @Test
    fun detectsNewStableReleaseWithApk() {
        assertEquals("v0.2.0", parseUpdate(release(), "0.1.0")?.tag)
        assertEquals("v0.2.0", parseUpdate(release(), "0.1.0-debug")?.tag)
    }

    @Test
    fun comparesVersionNumbersNotStrings() {
        assertEquals("v0.10.0", parseUpdate(release("v0.10.0"), "0.9.0")?.tag)
        assertNull(parseUpdate(release("v0.2.0"), "0.10.0"))
        assertNull(parseUpdate(release("v0.2.0"), "0.2.0"))
    }

    @Test
    fun ignoresDraftsPrereleasesAndMissingApks() {
        assertNull(parseUpdate(release(flags = "\"draft\":true,"), "0.1.0"))
        assertNull(parseUpdate(release(flags = "\"prerelease\":true,"), "0.1.0"))
        assertNull(parseUpdate(release("v0.2.0-beta"), "0.1.0"))
        assertNull(parseUpdate(release(assets = false), "0.1.0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUntrustedReleaseLink() {
        parseUpdate(release().replace("https://github.com/", "https://example.com/"), "0.1.0")
    }
}