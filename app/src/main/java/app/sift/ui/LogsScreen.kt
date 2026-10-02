package app.sift.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sift.backend.AccessState
import app.sift.data.AppInfo
import app.sift.data.BlockMode
import app.sift.data.Category
import app.sift.data.ChannelAction
import app.sift.data.ChannelInfo
import app.sift.data.HistoryEntry
import app.sift.data.Outcome
import app.sift.data.Rule
import app.sift.data.RuleAction

private enum class ShowFilter(val label: String) { ALL("All"), SHOWN("Shown"), BLOCKED("Blocked") }

private val HistoryEntry.blocked get() = outcome.keptOut

private fun HistoryEntry.blockLabel() = when (outcome) {
    Outcome.RULE -> "Removed by rule \u201c$reason\u201d"
    else -> "Blocked" + channelName.ifBlank { null }?.let { " \u00b7 $it" }.orEmpty()
}

/** Shown, but only because a "keep" rule overrode its category block. */
private fun HistoryEntry.allowLabel() = "Kept by rule \u201c$reason\u201d"

@Composable
fun LogsScreen(
    entries: List<HistoryEntry>,
    apps: List<AppInfo>,
    blockMode: BlockMode,
    access: AccessState,
    onTab: (Tab) -> Unit,
    nav: Nav,
    vm: MainViewModel,
) {
    var show by rememberSaveable { mutableStateOf(ShowFilter.ALL) }
    var pkg by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    var open by remember { mutableStateOf<HistoryEntry?>(null) }
    var ruleDraft by remember { mutableStateOf<Rule?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val filtered = remember(entries, show, pkg, category) {
        entries.filter {
            (show == ShowFilter.ALL || (show == ShowFilter.BLOCKED) == it.blocked) &&
                (pkg == null || it.pkg == pkg) && (category == null || it.category == category)
        }
    }

    TabScaffold(
        Tab.LOGS, onTab,
        actions = {
            if (entries.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.Delete, "Clear history") }
        },
    ) {
        Column {
            if (entries.isEmpty()) {
                EmptyState(
                    "No notifications yet",
                    if (access.listenerConnected) {
                        "Every notification that arrives from now on is listed here for 7 days, including the ones this app blocks."
                    } else {
                        "History needs notification access."
                    },
                ) { if (!access.listenerConnected) FilledTonalButton(onClick = { nav.push("setup") }) { Text("Set up access") } }
                return@Column
            }
            Filters(entries, show, { show = it }, pkg, { pkg = it }, category, { category = it })
            if (filtered.isEmpty()) {
                EmptyState("No matches", "Try different filters.")
                return@Column
            }
            HistoryList(filtered) { open = it }
        }
    }

    open?.let { e ->
        EntrySheet(
            e,
            channel = vm.channelFor(e),
            filteredToApp = pkg == e.pkg,
            onDismiss = { open = null },
            onAction = { vm.setChannelFromLog(e, it); open = null },
            onRule = { words ->
                ruleDraft = Rule(
                    id = System.currentTimeMillis(),
                    name = "",
                    keywords = words,
                    pkg = e.pkg,
                    category = e.category,
                    action = if (e.outcome == Outcome.BLOCKED) RuleAction.ALLOW else RuleAction.DISMISS,
                )
                open = null
            },
            onShowApp = { pkg = e.pkg; show = ShowFilter.ALL; category = null; open = null },
            onOpenApp = { open = null; nav.push("app/${e.pkg}") },
            onDelete = { vm.deleteEntry(e); open = null },
        )
    }
    ruleDraft?.let { rule ->
        RuleEditorSheet(
            rule,
            isNew = true,
            apps = apps,
            history = entries,
            blockMode = blockMode,
            onDismiss = { ruleDraft = null },
            onSave = { vm.saveRule(it); vm.say("Rule \u201c${it.name}\u201d created"); ruleDraft = null },
            onDelete = null,
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Removes all ${entries.size} saved notifications. Your settings aren't affected.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Filters(
    entries: List<HistoryEntry>,
    show: ShowFilter,
    onShow: (ShowFilter) -> Unit,
    pkg: String?,
    onPkg: (String?) -> Unit,
    category: Category?,
    onCategory: (Category?) -> Unit,
) {
    val appsInLog = remember(entries) {
        entries.groupBy { it.pkg }.map { (p, list) -> Triple(p, list.first().app, list.size) }.sortedByDescending { it.third }
    }
    val categoriesInLog = remember(entries) {
        entries.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShowFilter.entries.forEach { f ->
            val count = when (f) {
                ShowFilter.ALL -> entries.size
                ShowFilter.SHOWN -> entries.count { !it.blocked }
                ShowFilter.BLOCKED -> entries.count { it.blocked }
            }
            QuietChip("${f.label} $count", show == f) { onShow(f) }
        }
        MenuChip(
            label = pkg?.let { p -> appsInLog.firstOrNull { it.first == p }?.second ?: p } ?: "App",
            selected = pkg != null,
            options = listOf<Pair<String?, String>>(null to "All apps") + appsInLog.map { it.first to "${it.second} (${it.third})" },
            onPick = onPkg,
        )
        MenuChip(
            label = category?.label ?: "Category",
            selected = category != null,
            options = listOf<Pair<Category?, String>>(null to "All categories") + categoriesInLog.map { it.first to "${it.first.label} (${it.second})" },
            onPick = onCategory,
        )
    }
}

@Composable
private fun HistoryList(entries: List<HistoryEntry>, onOpen: (HistoryEntry) -> Unit) {
    val ctx = LocalContext.current
    val byDay = remember(entries) { entries.groupBy { dayLabel(ctx, it.time) } }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        byDay.forEach { (day, list) ->
            item(key = "day-$day") {
                val blocked = list.count { it.blocked }
                SectionLabel(day + if (blocked > 0) "  \u00b7  $blocked blocked" else "")
            }
            items(list, key = { "${it.key}@${it.time}" }) { e -> EntryRow(e) { onOpen(e) } }
        }
    }
}

@Composable
private fun EntryRow(e: HistoryEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val blocked = e.blocked
    ListItem(
        headlineContent = {
            Text(
                e.title.ifBlank { e.app },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Column {
                if (e.text.isNotBlank()) {
                    Text(e.text, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val allowed = e.outcome == Outcome.ALLOWED
                Text(
                    when {
                        blocked -> "${e.app} \u00b7 ${e.blockLabel()}"
                        allowed -> "${e.app} \u00b7 ${e.allowLabel()}"
                        else -> listOf(e.app, e.channelName).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        blocked -> MaterialTheme.colorScheme.error
                        allowed -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            Box {
                Box(Modifier.alpha(if (blocked) 0.4f else 1f)) { AppIcon(e.pkg) }
                if (blocked) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(18.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Close, "Blocked", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onError) }
                }
            }
        },
        trailingContent = {
            Text(
                DateUtils.formatDateTime(ctx, e.time, DateUtils.FORMAT_SHOW_TIME),
                style = MaterialTheme.typography.labelSmall.merge(TabularFigures),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = clearListItem(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private val stopWords = setOf(
    "the", "and", "for", "you", "your", "with", "this", "that", "from", "are", "was", "has", "have", "our", "not",
    "now", "get", "all", "just", "will", "can", "its", "into", "been", "more", "here", "there", "what", "when",
    "who", "how", "new", "one", "out", "they", "them", "their", "than", "then", "also", "via", "had", "but",
)

/** Words (and a short title as a phrase) the user can tap to build a rule. */
private fun keywordCandidates(e: HistoryEntry): List<String> {
    val title = e.title.trim().takeIf { it.split(' ').size in 2..5 }
    val words = Regex("[\\p{L}\\p{N}%\\p{Sc}']+").findAll("${e.title} ${e.text}")
        .map { it.value.trim('\'').lowercase() }
        .filter { it.length >= 3 && it.any(Char::isLetter) || it.endsWith('%') }
        .filter { it !in stopWords }
    return (listOfNotNull(title) + words).distinctBy { it.lowercase() }.take(20)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(
    e: HistoryEntry,
    channel: ChannelInfo?,
    filteredToApp: Boolean,
    onDismiss: () -> Unit,
    onAction: (ChannelAction) -> Unit,
    onRule: (List<String>) -> Unit,
    onShowApp: () -> Unit,
    onOpenApp: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val candidates = remember(e) { keywordCandidates(e) }
    var picked by remember(e) { mutableStateOf(emptyList<String>()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(e.pkg, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(e.app, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        DateUtils.formatDateTime(ctx, e.time, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (e.outcome) {
                    Outcome.SHOWN -> Pill("Shown")
                    Outcome.ALLOWED -> Pill("Kept", color = MaterialTheme.colorScheme.primary)
                    Outcome.BLOCKED -> Pill("Blocked", color = MaterialTheme.colorScheme.error)
                    Outcome.RULE -> Pill("Rule", color = MaterialTheme.colorScheme.error)
                }
            }

            Surface(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val category = channel?.category ?: e.category
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryIcon(category, 20.dp)
                        Text(
                            listOf(category.label, e.channelName).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (e.title.isNotBlank()) {
                        Text(e.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (e.text.isNotBlank()) {
                        Text(e.text, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (e.outcome == Outcome.ALLOWED) {
                        Text(
                            e.allowLabel(),
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (e.outcome == Outcome.RULE) {
                        Text(
                            "Removed by rule \u201c${e.reason}\u201d",
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            SheetLabel(
                if (channel != null) {
                    "All \u201c${channel.channel.name}\u201d notifications"
                } else {
                    "This channel isn't available anymore"
                },
            )
            if (channel != null) {
                val status = channel.status()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile("Allow", Icons.Default.NotificationsActive, status == Status.ALLOWED, Modifier.weight(1f)) {
                        onAction(ChannelAction.ALERT)
                    }
                    ActionTile("Silent", Icons.AutoMirrored.Filled.VolumeOff, status == Status.SILENT, Modifier.weight(1f)) {
                        onAction(ChannelAction.SILENT)
                    }
                    ActionTile("Block", Icons.Default.Block, status == Status.BLOCKED, Modifier.weight(1f), danger = true) {
                        onAction(ChannelAction.BLOCK)
                    }
                }
            }

            val keeping = e.outcome == Outcome.BLOCKED
            SheetLabel(if (keeping) "Let some of these through" else "Only notifications like this one")
            Text(
                when {
                    keeping && candidates.isEmpty() ->
                        "Create a rule to keep notifications that mention words you choose, even though this category is blocked."
                    keeping ->
                        "Tap the words worth keeping, then create a rule. Matching notifications stay in the shade instead of being removed \u2014 silently, without a pop-up."
                    candidates.isEmpty() -> "Create a rule to remove notifications that mention words you choose."
                    else -> "Tap the words that give it away, then create a rule. It catches them in any channel."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (candidates.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { w ->
                        QuietChip(w, w in picked) { picked = if (w in picked) picked - w else picked + w }
                    }
                }
            }
            FilledTonalButton(onClick = { onRule(picked) }, Modifier.padding(top = 8.dp)) {
                Icon(Icons.Default.FilterAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        picked.isEmpty() -> if (keeping) "Create keep rule" else "Create rule"
                        picked.size == 1 -> if (keeping) "Keep ones saying 1 word" else "Create rule with 1 word"
                        else -> if (keeping) "Keep ones saying ${picked.size} words" else "Create rule with ${picked.size} words"
                    },
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!filteredToApp) TextButton(onClick = onShowApp) { Text("More from ${e.app}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TextButton(onClick = onOpenApp) { Text("App settings") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete from Logs") }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(top = 24.dp, bottom = 10.dp),
        style = OvertypeLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    current: Boolean,
    modifier: Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = !current,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = when {
            !current -> colors.surface
            danger -> colors.errorContainer
            else -> colors.primaryContainer
        },
        contentColor = when {
            !current -> colors.onSurface
            danger -> colors.onErrorContainer
            else -> colors.onPrimaryContainer
        },
        border = if (current) null else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge)
            Text(
                if (current) "Current" else " ",
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
        }
    }
}

private fun dayLabel(ctx: Context, time: Long): String = when {
    DateUtils.isToday(time) -> "Today"
    DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> DateUtils.formatDateTime(ctx, time, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)
}
