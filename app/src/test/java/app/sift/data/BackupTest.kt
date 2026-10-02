package app.sift.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    @Test
    fun roundTripKeepsSettingsWithoutDeviceHistory() {
        val settings = StoreData(
            rules = listOf(Rule(1, "Keep OTP", listOf("otp"), "example.app", Category.SECURITY, RuleAction.ALLOW)),
            blockMode = BlockMode.BLOCK_FULLY,
            logBlocked = setOf("example.app|security"),
            logExcludedApps = setOf("private.app"),
            hints = mapOf("example.app|security" to setOf("msg")),
            known = setOf("example.app|security"),
            history = listOf(Batch(1, 1, "Old change", emptyList())),
        )
        val levels = mapOf("example.app|silent" to 2, "example.app|popup" to 4)
        val backup = Backup.decode(Backup.encode(settings, levels))
        val restored = backup.settings
        assertEquals(levels, backup.channelImportance)
        assertEquals(settings.copy(hints = emptyMap(), known = emptySet(), history = emptyList()), restored)
        assertTrue(restored.history.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFutureVersion() {
        Backup.decode(Backup.encode(StoreData()).replace("\"version\": 1", "\"version\": 2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnotherApp() {
        Backup.decode(Backup.encode(StoreData()).replace("\"app\": \"Sift\"", "\"app\": \"Other\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingFormatFields() {
        Backup.decode("{\"settings\": {}}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidImportance() {
        Backup.decode(Backup.encode(StoreData(), mapOf("example.app|channel" to 99)))
    }
}