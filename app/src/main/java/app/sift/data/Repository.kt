package app.sift.data

import android.app.NotificationManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import app.sift.App
import app.sift.backend.Channels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class Repository(private val app: App) {
    private val pm = app.packageManager
    private val raw = MutableStateFlow<Map<String, RawApp>>(emptyMap())

    /** (done, total) while a full scan is running. */
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)

    val apps: StateFlow<List<AppInfo>> = combine(raw, app.store.data) { r, d ->
        r.values.map { toInfo(it, d) }.sortedBy { it.label.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(app.scope, SharingStarted.Eagerly, emptyList())

    fun toInfo(r: RawApp, d: StoreData) = AppInfo(
        pkg = r.pkg, uid = r.uid, label = r.label, system = r.system, error = r.error,
        channels = r.channels.map { ch ->
            val key = keyOf(r.pkg, ch.id)
            val group = ch.group?.let(r.groupNames::get)
            val override = d.overrides[key]
            val (auto, confidence) = Classifier.classify(ch, group, d.hints[key].orEmpty(), r.system, r.appCategory)
            ChannelInfo(
                r.pkg, r.uid, r.label, ch, group, override ?: auto, if (override != null) 100 else confidence, override != null,
                // Ours, under either block mode (MIN = hide & log, NONE = block fully). Tolerant of
                // the window between a mode change and the channels being re-applied.
                logged = key in d.logBlocked && ch.importance <= NotificationManager.IMPORTANCE_MIN,
            )
        },
    )

    fun currentChannels(): List<ChannelInfo> = raw.value.values.flatMap { toInfo(it, app.store.data.value).channels }

    suspend fun scanAll(adoptBlocked: Boolean = true): Int = withContext(Dispatchers.IO) {
        app.access.requireReady()
        val installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        val result = LinkedHashMap<String, RawApp>()
        try {
            installed.forEachIndexed { i, ai ->
                progress.value = i + 1 to installed.size
                result[ai.packageName] = load(ai)
            }
            val failures = result.values.filter { it.error != null }
            if (failures.size == result.size && failures.isNotEmpty()) error(failures.first().error!!)
            raw.value = result
        } finally {
            progress.value = null
        }
        if (adoptBlocked) app.engine.adoptBlocked(result.values)
        app.engine.enforceNew(result.values)
        result.size
    }

    suspend fun rescan(pkg: String): RawApp? = withContext(Dispatchers.IO) {
        if (!app.access.state.value.ready) return@withContext null
        val ai = runCatching { pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)) }.getOrNull()
            ?: return@withContext null
        load(ai).also { r -> raw.update { it + (pkg to r) } }
    }

    private fun load(ai: ApplicationInfo): RawApp {
        val pkg = ai.packageName
        val label = ai.loadLabel(pm).toString()
        // Preinstalled apps with a launcher icon (Photos, Chrome...) are user-facing, not "system".
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && pm.getLaunchIntentForPackage(pkg) == null
        return try {
            RawApp(
                pkg, ai.uid, label, system, ai.category,
                error = null,
                channels = Channels.list(pkg, ai.uid),
                groupNames = Channels.groups(pkg, ai.uid).associate { it.id to it.name.toString() },
            )
        } catch (e: Exception) {
            RawApp(pkg, ai.uid, label, system, ai.category, e.message ?: e.javaClass.simpleName, emptyList(), emptyMap())
        }
    }
}
