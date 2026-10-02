package app.sift.engine

import android.app.NotificationManager
import app.sift.App
import app.sift.backend.Channels
import app.sift.data.Batch
import app.sift.data.BlockMode
import app.sift.data.ChannelAction
import app.sift.data.ChannelChange
import app.sift.data.ChannelInfo
import app.sift.data.RawApp
import app.sift.data.keyOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BulkEngine(private val app: App) {
    data class Outcome(val changed: Int, val unchanged: Int, val locked: Int, val failed: Int, val error: String?, val batch: Batch?) {
        fun describe(verb: String) = buildString {
            append(if (changed == 0) "Nothing changed" else "$verb $changed ${if (changed == 1) "channel" else "channels"}")
            val skipped = locked + failed
            if (skipped > 0) append(" · $skipped couldn't be changed")
            if (changed == 0 && error != null) append(": $error")
        }
    }

    private data class Target(val pkg: String, val uid: Int, val channelId: String, val importance: Int, val logged: Boolean)

    private val mutex = Mutex()

    suspend fun apply(title: String, targets: List<ChannelInfo>, action: ChannelAction): Outcome {
        val blocking = action == ChannelAction.BLOCK
        // How low "blocked" goes depends on the block mode; everything else is fixed by the action.
        val importance = if (blocking) app.store.data.value.blockMode.blockedImportance else action.importance
        return setImportance(
            title, targets.map { Target(it.pkg, it.uid, it.channel.id, importance, blocking) }, record = true,
        )
    }

    /**
     * Re-applies the blocked importance to every channel we own. Used when the block mode changes
     * and after a settings restore, so "blocked" on screen matches what Android is actually doing.
     */
    suspend fun reapplyBlockMode(): Outcome {
        val d = app.store.data.value
        val targets = app.repo.currentChannels()
            .filter { it.key in d.logBlocked }
            .map { Target(it.pkg, it.uid, it.channel.id, d.blockMode.blockedImportance, logged = true) }
        if (targets.isEmpty()) return Outcome(0, 0, 0, 0, null, null)
        // Not recorded: the mode toggle is its own undo, and a batch would fight the new mode.
        return setImportance("Block mode: ${d.blockMode.label}", targets, record = false)
    }

    suspend fun restoreChannelSettings(importance: Map<String, Int>): Outcome {
        val settings = app.store.data.value
        val targets = app.repo.currentChannels().mapNotNull { channel ->
            val blocked = channel.key in settings.logBlocked
            val level = if (blocked) settings.blockMode.blockedImportance else importance[channel.key]
            level?.let { Target(channel.pkg, channel.uid, channel.channel.id, it, blocked) }
        }
        if (targets.isEmpty()) return Outcome(0, 0, 0, 0, null, null)
        return setImportance("Restore channel settings", targets, record = false)
    }

    suspend fun undo(batch: Batch): Outcome {
        val outcome = setImportance(
            "Undo", batch.changes.map { Target(it.pkg, it.uid, it.channelId, it.before, it.loggedBefore) }, record = false,
        )
        app.store.update { it.copy(history = it.history.filterNot { b -> b.id == batch.id }) }
        return outcome
    }

    /** Applies category policies to channels never seen before, then marks them as known. */
    suspend fun enforceNew(raws: Collection<RawApp>) {
        val d = app.store.data.value
        val fresh = raws.flatMap { app.repo.toInfo(it, d).channels }.filter { it.key !in d.known }
        if (fresh.isEmpty()) return
        app.store.update { it.copy(known = it.known + fresh.map { c -> c.key }) }
        fresh.groupBy { it.category }.forEach { (cat, list) ->
            val action = d.policies[cat] ?: return@forEach
            apply("Auto ${action.label.lowercase()}: new ${cat.label} channels", list, action)
        }
    }

    suspend fun onChannelSeen(pkg: String, channelId: String) {
        if (keyOf(pkg, channelId) in app.store.data.value.known) return
        val raw = app.repo.rescan(pkg) ?: return
        enforceNew(listOf(raw))
    }

    /**
     * Android drops notifications from channels it blocks before we can see them, so blocked channels
     * (e.g. turned off in Android Settings) are switched to our own block, which keeps them in Logs.
     */
    suspend fun adoptBlocked(raws: Collection<RawApp>) {
        // Only worth doing when we can log what those channels would have dropped. Under
        // "block fully" an off channel is already exactly what we want, so leave it alone.
        if (app.store.data.value.blockMode == BlockMode.BLOCK_FULLY) return
        val targets = raws.flatMap { r ->
            r.channels.filter { it.importance == NotificationManager.IMPORTANCE_NONE }
                .map { Target(r.pkg, r.uid, it.id, NotificationManager.IMPORTANCE_MIN, logged = true) }
        }
        if (targets.isNotEmpty()) setImportance("", targets, record = false)
    }

    suspend fun adoptBlocked(pkg: String) {
        app.repo.rescan(pkg)?.let { adoptBlocked(listOf(it)) }
    }

    private data class Attempt(val target: Target, val name: String, val before: Int, val wasLogged: Boolean)

    private suspend fun setImportance(title: String, targets: List<Target>, record: Boolean): Outcome =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                app.access.requireReady()
                val logBlocked = app.store.data.value.logBlocked
                val changes = mutableListOf<ChannelChange>()
                var unchanged = 0
                var locked = 0
                var failed = 0
                var error: String? = null

                for ((owner, group) in targets.groupBy { it.pkg to it.uid }) {
                    val (pkg, uid) = owner
                    val current = try {
                        Channels.list(pkg, uid).associateBy { it.id }
                    } catch (e: Exception) {
                        failed += group.size
                        error = error ?: e.message
                        continue
                    }
                    val attempted = mutableListOf<Attempt>()
                    for (t in group) {
                        val ch = current[t.channelId] ?: continue
                        val wasLogged = keyOf(pkg, t.channelId) in logBlocked && ch.importance <= NotificationManager.IMPORTANCE_MIN
                        if (ch.importance == t.importance && wasLogged == t.logged) {
                            unchanged++
                            continue
                        }
                        val before = ch.importance
                        try {
                            if (before != t.importance) {
                                ch.importance = t.importance
                                Channels.update(pkg, uid, ch)
                            }
                            attempted += Attempt(t, ch.name.toString(), before, wasLogged)
                        } catch (e: Exception) {
                            failed++
                            error = error ?: e.message
                        }
                    }
                    // The system silently ignores some changes (e.g. blocking non-blockable channels), so verify.
                    val after = runCatching { Channels.list(pkg, uid).associateBy { it.id } }.getOrDefault(emptyMap())
                    for (a in attempted) {
                        if (after[a.target.channelId]?.importance == a.target.importance) {
                            changes += ChannelChange(
                                pkg, uid, a.target.channelId, a.name, a.before, a.target.importance,
                                loggedBefore = a.wasLogged, loggedAfter = a.target.logged,
                            )
                        } else {
                            locked++
                        }
                    }
                    if (attempted.isNotEmpty()) app.repo.rescan(pkg)
                }

                val nowLogged = changes.filter { it.loggedAfter }.map { keyOf(it.pkg, it.channelId) }
                val noLongerLogged = changes.filter { !it.loggedAfter }.map { keyOf(it.pkg, it.channelId) }.toSet()
                if (nowLogged.isNotEmpty() || noLongerLogged.isNotEmpty()) {
                    app.store.update { it.copy(logBlocked = it.logBlocked - noLongerLogged + nowLogged) }
                }

                val batch = if (record && changes.isNotEmpty()) {
                    Batch(System.nanoTime(), System.currentTimeMillis(), title, changes).also(app.store::addBatch)
                } else {
                    null
                }
                Outcome(changes.size, unchanged, locked, failed, error, batch)
            }
        }
}
