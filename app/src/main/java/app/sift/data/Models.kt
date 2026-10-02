package app.sift.data

import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.serialization.Serializable

@Serializable
enum class Category(val label: String, val description: String) {
    PROMO("Promotions", "Offers, deals, coupons and marketing pushes"),
    RECOMMENDATIONS("Recommendations", "Suggestions, trending and \"for you\" picks"),
    NEWS("News & content", "Headlines, new posts, episodes and live updates"),
    SOCIAL("Social activity", "Likes, comments, follows and mentions"),
    MESSAGES("Messages", "Chats, direct messages and email"),
    CALLS("Calls", "Incoming, missed calls and voicemail"),
    ORDERS("Orders & delivery", "Order status, shipping, rides and bookings"),
    PAYMENTS("Payments", "Transactions, bills, bank and wallet alerts"),
    SECURITY("Security", "OTPs, sign-ins and account alerts"),
    REMINDERS("Reminders", "Alarms, timers, calendar and tasks"),
    MEDIA("Media & ongoing", "Playback, navigation and running services"),
    SYSTEM("Updates & system", "Downloads, sync, backups and errors"),
    OTHER("Other", "Channels that didn't match any category"),
}

/** What blocking a channel actually does to it. */
@Serializable
enum class BlockMode(val label: String, val summary: String) {
    HIDE_AND_LOG(
        "Hide & log",
        "Blocked notifications are removed the moment they arrive and kept in Logs, so you can still see what you missed.",
    ),
    BLOCK_FULLY(
        "Block fully",
        "Blocked channels are switched off in Android. Nothing arrives at all, so nothing can be logged and rules can't see them.",
    ),
    ;

    /** The importance a blocked channel is set to. MIN still delivers, which is what lets us log it. */
    val blockedImportance: Int
        get() = when (this) {
            HIDE_AND_LOG -> NotificationManager.IMPORTANCE_MIN
            BLOCK_FULLY -> NotificationManager.IMPORTANCE_NONE
        }
}

@Serializable
enum class ChannelAction(val label: String, val description: String, val verb: String, val importance: Int) {
    POPUP("Pop up", "Sound, and appears on screen", "Set to pop up", NotificationManager.IMPORTANCE_HIGH),
    ALERT("Alert", "Sound or vibration", "Allowed", NotificationManager.IMPORTANCE_DEFAULT),
    SILENT("Silent", "In the shade, without sound", "Silenced", NotificationManager.IMPORTANCE_LOW),
    MINIMIZE("Minimized", "Collapsed at the bottom of the shade", "Minimized", NotificationManager.IMPORTANCE_MIN),

    // Minimized so Android still delivers them to us; the listener removes each one on arrival and logs it.
    BLOCK("Blocked", "Never shown, kept in Logs", "Blocked", NotificationManager.IMPORTANCE_MIN),
}

fun importanceLabel(importance: Int) = when (importance) {
    NotificationManager.IMPORTANCE_NONE -> "Blocked"
    NotificationManager.IMPORTANCE_MIN -> "Minimized"
    NotificationManager.IMPORTANCE_LOW -> "Silent"
    NotificationManager.IMPORTANCE_DEFAULT -> "Alerting"
    NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MAX -> "Pop-up"
    else -> "Unspecified"
}

fun keyOf(pkg: String, channelId: String) = "$pkg|$channelId"

/** Channels + groups exactly as read from the system for one app. */
data class RawApp(
    val pkg: String,
    val uid: Int,
    val label: String,
    val system: Boolean,
    val appCategory: Int,
    val error: String?,
    val channels: List<NotificationChannel>,
    val groupNames: Map<String, String>,
)

data class ChannelInfo(
    val pkg: String,
    val uid: Int,
    val appLabel: String,
    val channel: NotificationChannel,
    val groupName: String?,
    val category: Category,
    val confidence: Int,
    val overridden: Boolean,
    /** Blocked by us: minimized in the system, removed and logged on arrival. */
    val logged: Boolean,
) {
    val key get() = keyOf(pkg, channel.id)
}

data class AppInfo(
    val pkg: String,
    val uid: Int,
    val label: String,
    val system: Boolean,
    val error: String?,
    val channels: List<ChannelInfo>,
)

@Serializable
data class ChannelChange(
    val pkg: String,
    val uid: Int,
    val channelId: String,
    val channelName: String,
    val before: Int,
    val after: Int,
    val loggedBefore: Boolean = false,
    val loggedAfter: Boolean = false,
)

@Serializable
data class Batch(val id: Long, val time: Long, val title: String, val changes: List<ChannelChange>)

@Serializable
enum class RuleAction(val label: String, val description: String) {
    DISMISS("Remove", "Removed as soon as it arrives, and kept in Logs"),
    SNOOZE("Snooze", "Hidden for an hour, then shown again"),

    // Can only stop *our* removal: Android has already delivered the notification at the blocked
    // channel's importance, so a kept one sits silently in the shade rather than alerting.
    ALLOW("Keep", "Let through even when its category is blocked. Arrives silently, without a pop-up"),
}

@Serializable
data class Rule(
    val id: Long,
    val name: String,
    val keywords: List<String>,
    val pkg: String? = null,
    /** Only notifications Sift filed under this category; null means any. */
    val category: Category? = null,
    val action: RuleAction = RuleAction.DISMISS,
    val enabled: Boolean = true,
)

fun matchingRule(rules: List<Rule>, pkg: String, category: Category, text: String): Rule? {
    val matches = rules.filter { rule ->
        rule.enabled && (rule.pkg.isNullOrBlank() || rule.pkg == pkg) &&
            (rule.category == null || rule.category == category) &&
            rule.keywords.any { it.isNotBlank() && text.contains(it.trim(), ignoreCase = true) }
    }
    return matches.firstOrNull { it.action == RuleAction.ALLOW } ?: matches.firstOrNull()
}

@Serializable
enum class Outcome {
    SHOWN,
    BLOCKED,
    RULE,

    /** Its category is blocked, but a "keep" rule let it through. */
    ALLOWED,
    ;

    /** Whether Sift kept it out of the shade. Allowed notifications were let through. */
    val keptOut get() = this == BLOCKED || this == RULE
}

/** One notification as it arrived; kept for 7 days. */
@Serializable
data class HistoryEntry(
    val time: Long,
    val key: String,
    val pkg: String,
    val app: String,
    val channelId: String,
    val channelName: String,
    val category: Category,
    val title: String,
    val text: String,
    val outcome: Outcome,
    /** Rule name for [Outcome.RULE]. */
    val reason: String? = null,
)

@Serializable
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

@Serializable
data class StoreData(
    val overrides: Map<String, Category> = emptyMap(),
    val policies: Map<Category, ChannelAction> = emptyMap(),
    val known: Set<String> = emptySet(),
    val hints: Map<String, Set<String>> = emptyMap(),
    val history: List<Batch> = emptyList(),
    val rules: List<Rule> = emptyList(),
    /** Channel keys we block. Kept across block-mode changes so we know which channels are ours. */
    val logBlocked: Set<String> = emptySet(),
    val blockMode: BlockMode = BlockMode.HIDE_AND_LOG,
    val logExcludedApps: Set<String> = emptySet(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Wallpaper-based colours instead of the brand palette. */
    val materialYou: Boolean = false,
)
