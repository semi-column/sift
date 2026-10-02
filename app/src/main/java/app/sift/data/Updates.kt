package app.sift.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import app.sift.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class AvailableUpdate(val tag: String, val notes: String, val url: String)

data class UpdateState(
    val automatic: Boolean = true,
    val checking: Boolean = false,
    val available: AvailableUpdate? = null,
    val lastChecked: Long = 0,
    val dismissedTag: String? = null,
    val error: String? = null,
)

class Updates(context: Context) {
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(
        UpdateState(
            automatic = preferences.getBoolean("automatic", true),
            available = preferences.getString("release", null)?.let { runCatching { parseUpdate(it, BuildConfig.VERSION_NAME) }.getOrNull() },
            lastChecked = preferences.getLong("lastChecked", 0),
            dismissedTag = preferences.getString("dismissedTag", null),
        ),
    )
    val state = mutableState.asStateFlow()

    fun setAutomatic(enabled: Boolean) {
        preferences.edit { putBoolean("automatic", enabled) }
        mutableState.update { it.copy(automatic = enabled) }
    }

    fun dismiss() {
        val tag = state.value.available?.tag ?: return
        preferences.edit { putString("dismissedTag", tag) }
        mutableState.update { it.copy(dismissedTag = tag) }
    }

    suspend fun check(force: Boolean = false) {
        if (!mutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val elapsed = now - preferences.getLong("lastAttempt", 0)
            if (!force && (!state.value.automatic || elapsed in 0 until 86_400_000L)) return
            preferences.edit { putLong("lastAttempt", now) }
            mutableState.update { it.copy(checking = true, error = null) }
            try {
                val release = withContext(Dispatchers.IO) { fetchRelease() }
                val available = release?.let { parseUpdate(it, BuildConfig.VERSION_NAME) }
                preferences.edit {
                    putString("release", release)
                    putLong("lastChecked", now)
                }
                mutableState.update { it.copy(available = available, lastChecked = now) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("SiftUpdates", "Update check failed", error)
                mutableState.update { it.copy(error = error.message ?: "Couldn't check for updates") }
            } finally {
                mutableState.update { it.copy(checking = false) }
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun fetchRelease(): String? {
        val connection = URL("https://api.github.com/repos/semi-column/sift/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Sift/${BuildConfig.VERSION_NAME}")
            when (connection.responseCode) {
                404 -> return null
                403, 429 -> error("GitHub's request limit was reached. Try again later.")
                200 -> Unit
                else -> error("Couldn't check for updates (HTTP ${connection.responseCode})")
            }
            return connection.inputStream.use { input ->
                val bytes = input.readNBytes(262_145)
                require(bytes.size <= 262_144) { "GitHub's response was too large" }
                bytes.decodeToString()
            }
        } catch (error: java.io.IOException) {
            throw java.io.IOException("Couldn't reach GitHub. Check your connection and try again.", error)
        } finally {
            connection.disconnect()
        }
    }
}

@Serializable
private data class ReleaseAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val url: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

private fun versionParts(version: String): List<Long>? {
    val stable = version.removePrefix("v")
    if (!Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(stable)) return null
    val parts = stable.split('.').map { it.toLongOrNull() ?: return null }
    return parts
}

private val releaseJson = Json { ignoreUnknownKeys = true }

fun parseUpdate(text: String, installedVersion: String): AvailableUpdate? {
    val release = releaseJson.decodeFromString<GitHubRelease>(text)
    if (release.draft || release.prerelease) return null
    val latest = versionParts(release.tag) ?: return null
    val installed = versionParts(installedVersion.removeSuffix("-debug")) ?: return null
    val difference = latest.zip(installed).firstOrNull { (new, old) -> new != old } ?: return null
    if (difference.first < difference.second) return null
    val page = URI(release.url)
    require(page.scheme == "https" && page.host == "github.com" && page.userInfo == null &&
        page.path == "/semi-column/sift/releases/tag/${release.tag}") { "Unexpected release URL" }
    val hasApk = release.assets.any { asset ->
        val download = URI(asset.url)
        asset.name.endsWith(".apk", ignoreCase = true) && asset.size > 0 &&
            download.scheme == "https" && download.host == "github.com" && download.userInfo == null &&
            download.path.startsWith("/semi-column/sift/releases/download/${release.tag}/")
    }
    if (!hasApk) return null
    return AvailableUpdate(release.tag, release.body.orEmpty(), release.url)
}