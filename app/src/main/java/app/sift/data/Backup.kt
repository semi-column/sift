package app.sift.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A settings backup.
 *
 * Deliberately carries settings only. The notification log lives in its own store and is never
 * exported: it is the most private thing the app holds, it is already excluded from Android
 * backups and device transfers, and it expires after 7 days anyway.
 */
@Serializable
data class Backup(
    val app: String = APP,
    val version: Int = VERSION,
    val settings: StoreData,
    val channelImportance: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val APP = "Sift"
        const val VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        private fun StoreData.portable() = copy(hints = emptyMap(), history = emptyList(), known = emptySet())

        fun encode(settings: StoreData, channelImportance: Map<String, Int> = emptyMap()): String =
            json.encodeToString(Backup(settings = settings.portable(), channelImportance = channelImportance))

        /**
         * Returns the settings in [text], or throws with a message worth showing the user.
         * A backup from a newer version is refused rather than silently half-applied.
         */
        fun decode(text: String): Backup {
            val backup = runCatching {
                val fields = json.parseToJsonElement(text).jsonObject
                require(fields.keys.containsAll(listOf("app", "version", "settings")))
                json.decodeFromString<Backup>(text)
            }.getOrNull()
                ?: throw IllegalArgumentException("That file isn't a Sift backup")
            require(backup.app == APP) { "That file isn't a Sift backup" }
            require(backup.version <= VERSION) {
                "That backup was made by a newer version of Sift (v${backup.version})"
            }
            require(backup.version > 0) { "That backup has an invalid version" }
            require(backup.channelImportance.all { (key, importance) -> '|' in key && importance in 0..5 }) {
                "That backup has invalid channel settings"
            }
            require(backup.settings.rules.map { it.id }.distinct().size == backup.settings.rules.size) {
                "That backup has duplicate rule IDs"
            }
            return backup.copy(settings = backup.settings.portable())
        }
    }
}
