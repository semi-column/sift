package app.sift.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.sift.App
import app.sift.data.Category
import app.sift.data.Classifier
import app.sift.data.HistoryEntry
import app.sift.data.Outcome
import app.sift.data.Rule
import app.sift.data.StoreData
import app.sift.data.RuleAction
import app.sift.data.keyOf
import app.sift.data.matchingRule
import kotlinx.coroutines.launch

class NotifListener : NotificationListenerService() {
    companion object {
        @Volatile
        var instance: NotifListener? = null
            private set

        private val textKeys = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT,
        )
    }

    private val app get() = App.of(this)

    override fun onListenerConnected() {
        instance = this
        app.access.refresh()
        val ranking = currentRanking
        runCatching { activeNotifications }.getOrNull()?.sortedBy { it.postTime }?.forEach { sbn ->
            if (worthLogging(sbn)) record(sbn, channelOf(sbn, ranking), Outcome.SHOWN, time = sbn.postTime)
        }
    }

    override fun onListenerDisconnected() {
        instance = null
        app.access.refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (sbn.packageName == packageName) return
        val channel = channelOf(sbn, rankingMap)
        val channelId = sbn.notification.channelId ?: channel?.id
        // Android 16+ may move a notification into a system bundle channel; that's a free classification hint.
        val bundled = channel != null && channel.id != channelId && channel.id in Classifier.bundleChannelIds
        if (channelId != null) {
            sbn.notification.category?.let { app.store.addHint(sbn.packageName, channelId, it) }
            if (bundled) app.store.addHint(sbn.packageName, channelId, channel.id)
            app.scope.launch { runCatching { app.engine.onChannelSeen(sbn.packageName, channelId) } }
        }
        val d = app.store.data.value
        val clearable = !sbn.isOngoing && sbn.isClearable
        val rule = if (clearable) matchRule(d.rules, sbn, categoryOf(sbn, channel, d)) else null
        val kept = rule?.action == RuleAction.ALLOW

        val blockAndLog = channelId != null && keyOf(sbn.packageName, channelId) in d.logBlocked &&
            (bundled || channel?.importance == NotificationManager.IMPORTANCE_MIN)
        // A "keep" rule beats the category block, which is the whole point of it. Android has
        // already delivered this at the channel's (minimised) importance, so all we can do is
        // not remove it - it stays in the shade silently.
        if (blockAndLog && clearable && !kept) {
            // Group summaries are removed too, but only real notifications are logged.
            cancelNotification(sbn.key)
            if (worthLogging(sbn)) record(sbn, channel, Outcome.BLOCKED)
            return
        }
        if (!worthLogging(sbn)) return

        when (rule?.action) {
            RuleAction.DISMISS -> {
                cancelNotification(sbn.key)
                record(sbn, channel, Outcome.RULE, reason = rule.name)
            }
            RuleAction.SNOOZE -> {
                snoozeNotification(sbn.key, 60 * 60 * 1000L)
                record(sbn, channel, Outcome.RULE, reason = rule.name)
            }
            // Only worth recording as "allowed" if it actually overrode a block.
            RuleAction.ALLOW -> record(sbn, channel, if (blockAndLog) Outcome.ALLOWED else Outcome.SHOWN, reason = rule.name)
            null -> record(sbn, channel, Outcome.SHOWN)
        }
    }

    override fun onNotificationChannelModified(pkg: String, user: UserHandle, channel: NotificationChannel, modificationType: Int) {
        if (user != Process.myUserHandle()) return
        when {
            modificationType == NOTIFICATION_CHANNEL_OR_GROUP_ADDED ->
                app.scope.launch { runCatching { app.engine.onChannelSeen(pkg, channel.id) } }
            // Blocked in Android Settings: switch it to our block so its notifications still get logged.
            modificationType == NOTIFICATION_CHANNEL_OR_GROUP_UPDATED && channel.importance == NotificationManager.IMPORTANCE_NONE ->
                app.scope.launch { runCatching { app.engine.adoptBlocked(pkg) } }
        }
    }

    // Group summaries only wrap their children, which are logged individually.
    private fun worthLogging(sbn: StatusBarNotification) =
        sbn.packageName != packageName && (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0

    private fun channelOf(sbn: StatusBarNotification, rankingMap: RankingMap?): NotificationChannel? {
        val r = Ranking()
        return if (rankingMap?.getRanking(sbn.key, r) == true) r.channel else null
    }

    private fun matchRule(rules: List<Rule>, sbn: StatusBarNotification, category: Category): Rule? {
        if (rules.none { it.enabled }) return null
        val extras = sbn.notification.extras
        val text = textKeys.mapNotNull { extras.getCharSequence(it) }.joinToString(" ")
        return matchingRule(rules, sbn.packageName, category, text)
    }

    /** The category Sift files this notification under; rules can be scoped to it. */
    private fun categoryOf(sbn: StatusBarNotification, channel: NotificationChannel?, d: StoreData): Category {
        val channelId = sbn.notification.channelId ?: channel?.id.orEmpty()
        val key = keyOf(sbn.packageName, channelId)
        val known = app.repo.apps.value.firstOrNull { it.pkg == sbn.packageName }
        return d.overrides[key]
            ?: known?.channels?.firstOrNull { it.channel.id == channelId }?.category
            ?: channel?.let { Classifier.classify(it, null, d.hints[key].orEmpty()).first }
            ?: Category.OTHER
    }

    private fun record(
        sbn: StatusBarNotification,
        channel: NotificationChannel?,
        outcome: Outcome,
        reason: String? = null,
        time: Long = System.currentTimeMillis(),
    ) {
        val d = app.store.data.value
        if (sbn.packageName in d.logExcludedApps) return
        val extras = sbn.notification.extras
        val channelId = sbn.notification.channelId ?: channel?.id.orEmpty()
        val key = keyOf(sbn.packageName, channelId)
        val known = app.repo.apps.value.firstOrNull { it.pkg == sbn.packageName }
        val knownChannel = known?.channels?.firstOrNull { it.channel.id == channelId }
        val category = categoryOf(sbn, channel, d)
        app.history.record(
            HistoryEntry(
                time = time,
                key = sbn.key,
                pkg = sbn.packageName,
                app = known?.label ?: appLabel(sbn.packageName),
                channelId = channelId,
                channelName = knownChannel?.channel?.name?.toString() ?: channel?.name?.toString().orEmpty(),
                category = category,
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                    ?.toString().orEmpty(),
                outcome = outcome,
                reason = reason,
            ),
            inPlace = sbn.isOngoing,
        )
    }

    private fun appLabel(pkg: String) = runCatching {
        packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(packageManager).toString()
    }.getOrDefault(pkg)
}
