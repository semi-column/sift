package app.sift.ui

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sift.backend.AccessState
import app.sift.data.AppInfo
import app.sift.data.Category
import app.sift.data.ChannelAction
import app.sift.data.ChannelInfo
import app.sift.data.HistoryEntry
import app.sift.data.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val categoryGroups = listOf(
    "Often noise" to listOf(Category.PROMO, Category.RECOMMENDATIONS, Category.SOCIAL, Category.NEWS),
    "Usually important" to listOf(
        Category.MESSAGES, Category.CALLS, Category.SECURITY, Category.PAYMENTS, Category.ORDERS, Category.REMINDERS,
    ),
    "Everything else" to listOf(Category.MEDIA, Category.SYSTEM, Category.OTHER),
)

// Blocking these by accident means missed OTPs, calls or alarms, so ask first.
private val askBeforeBlocking = setOf(Category.MESSAGES, Category.CALLS, Category.SECURITY, Category.PAYMENTS, Category.REMINDERS)

private fun List<ChannelInfo>.allBlocked() = isNotEmpty() && all { it.status() == Status.BLOCKED }

/** How long to wait for the scanned state to catch up with an optimistic switch position. */
private const val SETTLE_MILLIS = 1_500L

/**
 * Optimistic positions for the "allow this category" switches.
 *
 * Applying a category walks every affected package and rescans it, and `apps` is recombined on
 * another dispatcher on top of that - so the scanned truth arrives well after the tap, and a
 * frame or two after the job itself ends. Each switch therefore shows its target position
 * straight away and holds it until the scan agrees. Clearing the override when the job
 * completes is what made the thumb snap back and then flip again.
 */
@Stable
private class CategorySwitches(private val scope: CoroutineScope, private val blocked: State<(Category) -> Boolean>) {
    private var pending by mutableStateOf(emptyMap<Category, Boolean>())
    private var busy by mutableStateOf(emptySet<Category>())

    fun isOn(cat: Category) = pending[cat] ?: !blocked.value(cat)

    fun set(cat: Category, on: Boolean, apply: () -> Job) {
        if (cat in busy) return
        busy = busy + cat
        pending = pending + (cat to on)
        scope.launch {
            try {
                apply().join()
                // Bounded: a channel the system refuses to change never agrees, and the switch
                // still has to fall back to the truth rather than lie indefinitely.
                withTimeoutOrNull(SETTLE_MILLIS) { snapshotFlow { !blocked.value(cat) }.first { it == on } }
            } finally {
                busy = busy - cat
                pending = pending - cat
            }
        }
    }
}

@Composable
private fun rememberCategorySwitches(blocked: (Category) -> Boolean): CategorySwitches {
    val latest = rememberUpdatedState(blocked)
    val scope = rememberCoroutineScope()
    return remember { CategorySwitches(scope, latest) }
}

@Composable
fun HomeScreen(
    apps: List<AppInfo>,
    history: List<HistoryEntry>,
    policies: Map<Category, ChannelAction>,
    access: AccessState,
    progress: Pair<Int, Int>?,
    onTab: (Tab) -> Unit,
    vm: MainViewModel,
    onOpen: (Category) -> Unit,
) {
    val byCategory = remember(apps) { apps.flatMap { it.channels }.groupBy { it.category } }
    var confirm by remember { mutableStateOf<Category?>(null) }
    val switches = rememberCategorySwitches { byCategory[it].orEmpty().allBlocked() }

    fun setBlocked(cat: Category, block: Boolean) = switches.set(cat, on = !block) {
        vm.setCategoryBlocked(cat, byCategory[cat].orEmpty(), block)
    }

    TabScaffold(
        Tab.CATEGORIES, onTab, progress,
        actions = {
            if (progress == null) IconButton(onClick = vm::scan) { Icon(Icons.Default.Refresh, "Rescan") }
        },
    ) {
        if (byCategory.isEmpty()) {
            EmptyState(
                if (progress != null) "Scanning your apps…" else "Nothing here yet",
                if (progress != null) "${progress.first} of ${progress.second}" else "Tap refresh to scan your apps.",
            )
            return@TabScaffold
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            // Always present: inserting it later would leave it scrolled out of view above the list.
            item(key = "today") { TodayCard(history) { onTab(Tab.LOGS) } }
            categoryGroups.forEach { (title, cats) ->
                val present = cats.filter { it in byCategory }
                if (present.isEmpty()) return@forEach
                item(key = title) {
                    Column {
                        SectionLabel(title)
                        Group {
                            present.forEach { cat ->
                                val channels = byCategory.getValue(cat)
                                CategoryRow(
                                    cat, channels, policies[cat],
                                    on = switches.isOn(cat),
                                    // Deliberately not disabled while applying: `enabled = false`
                                    // swaps in the disabled track colour, which flashes. Repeat
                                    // taps are ignored by CategorySwitches instead.
                                    enabled = access.ready && progress == null,
                                    onToggle = { on ->
                                        if (!on && cat in askBeforeBlocking) confirm = cat else setBlocked(cat, block = !on)
                                    },
                                    onClick = { onOpen(cat) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { cat ->
        val channels = byCategory[cat].orEmpty()
        val appCount = channels.distinctBy { it.pkg }.size
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { CategoryIcon(cat) },
            title = { Text("Block all ${cat.label.lowercase()}?") },
            text = {
                Text("${cat.description} from $appCount ${if (appCount == 1) "app" else "apps"} will be hidden and kept in Logs. You can undo this.")
            },
            confirmButton = {
                TextButton(onClick = { setBlocked(cat, block = true); confirm = null }) {
                    Text("Block", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TodayCard(history: List<HistoryEntry>, onClick: () -> Unit) {
    val today = remember(history) { history.filter { DateUtils.isToday(it.time) } }
    val blocked = today.count { it.outcome.keptOut }
    val noisiest = remember(today) { today.groupingBy { it.app }.eachCount().maxByOrNull { it.value } }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TODAY",
                    Modifier.weight(1f),
                    style = OvertypeLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    "Open Logs",
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.padding(top = 12.dp)) {
                Stat(today.size, "arrived", Modifier.weight(1f))
                Stat(blocked, "kept out", Modifier.weight(1f))
            }
            noisiest?.let { (app, count) ->
                Text(
                    "Noisiest · $app · $count",
                    Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value.toString(), style = MaterialTheme.typography.headlineLarge.merge(TabularFigures))
        Text(
            label,
            Modifier.padding(top = 1.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryRow(
    cat: Category,
    channels: List<ChannelInfo>,
    policy: ChannelAction?,
    on: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val appCount = channels.distinctBy { it.pkg }.size
    val apps = "$appCount ${if (appCount == 1) "app" else "apps"}"
    ListItem(
        headlineContent = { Text(cat.label) },
        supportingContent = {
            Text(
                if (channels.allBlocked()) {
                    "Off · $apps"
                } else {
                    "$apps · ${statusSummary(channels)}" + policy?.let { " · new: ${policyLabel(it).lowercase()}" }.orEmpty()
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { CategoryIcon(cat) },
        trailingContent = { Switch(checked = on, onCheckedChange = onToggle, enabled = enabled, colors = quietSwitchColors()) },
        colors = clearListItem(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private val policyOptions = listOf(null, ChannelAction.ALERT, ChannelAction.SILENT, ChannelAction.BLOCK)

private fun policyLabel(a: ChannelAction?) = when (a) {
    null -> "Leave as is"
    ChannelAction.ALERT -> "Allow"
    ChannelAction.SILENT -> "Silence"
    else -> "Block"
}

@Composable
fun CategoryScreen(
    cat: Category,
    apps: List<AppInfo>,
    policy: ChannelAction?,
    nav: Nav,
    vm: MainViewModel,
) {
    val channels = remember(apps, cat) { apps.flatMap { a -> a.channels.filter { it.category == cat } } }
    val switches = rememberCategorySwitches { channels.allBlocked() }
    var filter by rememberSaveable { mutableStateOf<Status?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }

    val visible = channels.filter { filter == null || it.status() == filter }.groupBy { it.pkg }
    val visibleKeys = visible.values.flatten().map { it.key }.toSet()

    fun toggle(keys: Collection<String>) {
        selected = if (keys.all { it in selected }) selected - keys.toSet() else selected + keys
    }

    DetailScaffold(
        cat.label, nav::back,
        bottomBar = {
            if (selected.isNotEmpty()) {
                SelectionBar(selected.size, onClear = { selected = emptySet() }) { action ->
                    vm.bulk(channels.filter { it.key in selected }, action, cat.label)
                    selected = emptySet()
                }
            }
        },
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryIcon(cat, 56.dp)
                    Text(
                        cat.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Group(Modifier.padding(vertical = 8.dp)) {
                    ListItem(
                        headlineContent = { Text("Allow ${cat.label.lowercase()}") },
                        supportingContent = { Text(if (!switches.isOn(cat)) "Off for every app" else statusSummary(channels)) },
                        trailingContent = {
                            Switch(
                                checked = switches.isOn(cat),
                                onCheckedChange = { on ->
                                    switches.set(cat, on) { vm.setCategoryBlocked(cat, channels, block = !on) }
                                },
                                colors = quietSwitchColors(),
                            )
                        },
                        colors = clearListItem(),
                    )
                    ListItem(
                        headlineContent = { Text("New channels") },
                        supportingContent = { Text("When an app adds one in this category") },
                        trailingContent = { PickerButton(policyLabel(policy), policyOptions, ::policyLabel) { vm.setPolicy(cat, it) } },
                        colors = clearListItem(),
                    )
                }
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (listOf<Status?>(null) + Status.entries).forEach { s ->
                        val count = channels.count { s == null || it.status() == s }
                        QuietChip("${s?.label ?: "All"} $count", filter == s) { filter = s }
                    }
                }
            }
            if (visible.isEmpty()) {
                item { Text("No channels match this filter.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item {
                    SelectRow(
                        state = triState(visibleKeys, selected),
                        onClick = { toggle(visibleKeys) },
                    ) { Text("Select all", style = MaterialTheme.typography.titleSmall) }
                }
            }
            visible.forEach { (pkg, list) ->
                val keys = list.map { it.key }
                item(key = pkg) {
                    AppSelectRow(
                        list, triState(keys, selected),
                        expanded = pkg in expanded,
                        onToggle = { toggle(keys) },
                        onExpand = { expanded = if (pkg in expanded) expanded - pkg else expanded + pkg },
                    )
                }
                if (pkg in expanded && list.size > 1) {
                    items(list, key = { it.key }) { c ->
                        SelectRow(
                            state = ToggleableState(c.key in selected),
                            onClick = { toggle(listOf(c.key)) },
                            indent = true,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.channel.name.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                c.channel.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            StatusText(c.status().label, c.status())
                        }
                    }
                }
            }
        }
    }
}

private fun triState(keys: Collection<String>, selected: Set<String>) = when {
    keys.isEmpty() || keys.none { it in selected } -> ToggleableState.Off
    keys.all { it in selected } -> ToggleableState.On
    else -> ToggleableState.Indeterminate
}

@Composable
private fun SelectRow(
    state: ToggleableState,
    onClick: () -> Unit,
    indent: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = if (indent) 56.dp else 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == ToggleableState.Indeterminate) {
            TriStateCheckbox(state = state, onClick = onClick, colors = quietCheckboxColors())
        } else {
            Checkbox(checked = state == ToggleableState.On, onCheckedChange = { onClick() }, colors = quietCheckboxColors())
        }
        Spacer(Modifier.width(4.dp))
        content()
    }
}

@Composable
private fun AppSelectRow(
    list: List<ChannelInfo>,
    state: ToggleableState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
) {
    val statuses = list.map { it.status() }.distinct()
    val status = statuses.singleOrNull()
    SelectRow(state, onToggle) {
        AppIcon(list.first().pkg, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(list.first().appLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (list.size == 1) list.first().channel.name.toString() else "${list.size} channels",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusText(status?.label ?: "Mixed", status)
        if (list.size > 1) {
            IconButton(onClick = onExpand) {
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (expanded) "Collapse" else "Expand")
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onAction: (ChannelAction) -> Unit) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Clear selection") }
        Text("$count selected", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { onAction(ChannelAction.ALERT) }) { Text("Allow") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onAction(ChannelAction.SILENT) }) { Text("Silence") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { onAction(ChannelAction.BLOCK) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) { Text("Block") }
        Spacer(Modifier.width(8.dp))
    }
}
