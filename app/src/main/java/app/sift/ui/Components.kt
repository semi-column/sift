package app.sift.ui

import android.app.NotificationManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.sift.data.Category
import app.sift.data.ChannelAction
import app.sift.data.ChannelInfo
import app.sift.data.importanceLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Status(val label: String) { ALLOWED("Allowed"), SILENT("Silent"), BLOCKED("Blocked") }

fun statusOf(importance: Int) = when (importance) {
    NotificationManager.IMPORTANCE_NONE -> Status.BLOCKED
    NotificationManager.IMPORTANCE_MIN, NotificationManager.IMPORTANCE_LOW -> Status.SILENT
    else -> Status.ALLOWED
}

fun ChannelInfo.status() = if (logged) Status.BLOCKED else statusOf(channel.importance)

fun ChannelInfo.behaviorLabel() = when {
    logged -> "Blocked"
    channel.importance == NotificationManager.IMPORTANCE_NONE -> "Off in Android settings"
    else -> importanceLabel(channel.importance)
}

fun ChannelInfo.isCurrent(a: ChannelAction) = when (a) {
    ChannelAction.BLOCK -> logged || channel.importance == NotificationManager.IMPORTANCE_NONE
    ChannelAction.POPUP -> channel.importance >= a.importance
    else -> !logged && channel.importance == a.importance
}

/** "3 blocked · 2 silent", or "All allowed". */
fun statusSummary(channels: Collection<ChannelInfo>): String {
    val blocked = channels.count { it.status() == Status.BLOCKED }
    val silent = channels.count { it.status() == Status.SILENT }
    return buildList {
        if (blocked > 0) add("$blocked blocked")
        if (silent > 0) add("$silent silent")
    }.joinToString(" · ").ifEmpty { "All allowed" }
}

// Only "blocked" earns a hue. Allowed and silent are both ordinary states, so they are told
// apart by the dot - hollow versus filled - rather than by colour alone.
@Composable
fun Status?.color(): Color = when (this) {
    Status.BLOCKED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun StatusDot(status: Status?, size: Dp = 7.dp) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        Status.ALLOWED -> Box(Modifier.size(size).border(1.5.dp, colors.outline, CircleShape))
        Status.SILENT -> Box(Modifier.size(size).background(colors.onSurfaceVariant, CircleShape))
        Status.BLOCKED -> Box(Modifier.size(size).background(colors.error, CircleShape))
        null -> Box(Modifier.size(size).background(colors.outlineVariant, CircleShape))
    }
}

@Composable
fun StatusText(text: String, status: Status?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(status)
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = status.color(), maxLines = 1)
    }
}

// Outlined throughout: a page of thirteen filled glyphs reads much heavier than the same
// page drawn in line weight.
val Category.icon: ImageVector
    get() = when (this) {
        Category.PROMO -> Icons.Outlined.LocalOffer
        Category.RECOMMENDATIONS -> Icons.Outlined.AutoAwesome
        Category.NEWS -> Icons.Outlined.Newspaper
        Category.SOCIAL -> Icons.Outlined.Groups
        Category.MESSAGES -> Icons.AutoMirrored.Outlined.Chat
        Category.CALLS -> Icons.Outlined.Call
        Category.ORDERS -> Icons.Outlined.LocalShipping
        Category.PAYMENTS -> Icons.Outlined.Payments
        Category.SECURITY -> Icons.Outlined.Shield
        Category.REMINDERS -> Icons.Outlined.Alarm
        Category.MEDIA -> Icons.Outlined.PlayCircle
        Category.SYSTEM -> Icons.Outlined.SystemUpdate
        Category.OTHER -> Icons.Outlined.Notifications
    }

/** A squircle tile holding the category glyph; below 24dp the tile is dropped and the glyph stands alone. */
@Composable
fun CategoryIcon(cat: Category, size: Dp = 40.dp) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    if (size <= 24.dp) {
        Icon(cat.icon, null, Modifier.size(size), tint = tint)
        return
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(cat.icon, null, Modifier.size(size * 0.5f), tint = tint)
    }
}

/** Rounded, hairline-bordered container for a group of rows. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
}

/** Outlined rather than filled: a row of keyword pills should read as text, not as buttons. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text,
        modifier.border(1.dp, color.copy(alpha = 0.4f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
    )
}

// Where colour is allowed to appear, in full:
//   accent - offered actions and transient choices (buttons, FAB, selected filter chips, progress)
//   ink    - persistent state you own (switches, the tab you are on) and all content
//   red    - blocked, and destructive actions
// Category identity is carried by icon shape, never by hue.

/**
 * Switches in ink rather than accent. Most categories are switched on most of the time, so an
 * accent-coloured track would put a saturated pill on nearly every row of the main screen.
 */
@Composable
fun quietSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = MaterialTheme.colorScheme.onSurface,
    checkedThumbColor = MaterialTheme.colorScheme.surface,
    checkedBorderColor = Color.Transparent,
    checkedIconColor = MaterialTheme.colorScheme.onSurface,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledCheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
    disabledCheckedThumbColor = MaterialTheme.colorScheme.surface,
    disabledCheckedBorderColor = Color.Transparent,
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
)

// Selected chips take a pale accent wash rather than a solid fill, so a row of filters stays quiet.
@Composable
fun quietChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surface,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@Composable
fun quietChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = selected,
    borderColor = MaterialTheme.colorScheme.outlineVariant,
)

/** Unchecked boxes sit on the outline colour, so a column of them reads as structure, not as marks. */
@Composable
fun quietCheckboxColors() = CheckboxDefaults.colors(
    uncheckedColor = MaterialTheme.colorScheme.outline,
    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
fun <T> MenuChip(label: String, selected: Boolean, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
            shape = CircleShape,
            colors = quietChipColors(),
            border = quietChipBorder(selected),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 400.dp)) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

/** A [FilterChip] in the app's quiet style: hairline when off, solid ink when on. */
@Composable
fun QuietChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = CircleShape,
        colors = quietChipColors(),
        border = quietChipBorder(selected),
    )
}

/** Borderless filled search field - one less box outline on screens that are mostly list. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) { Icon(Icons.Outlined.Close, "Clear", Modifier.size(20.dp)) }
            }
        },
        singleLine = true,
        shape = CircleShape,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val pm = LocalContext.current.packageManager
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { pm.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap() }.getOrNull()
        }
    }
    icon?.let { Image(it, null, Modifier.size(size)) }
        ?: Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

@Composable
fun EmptyState(title: String, body: String, icon: ImageVector? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            Icon(
                it,
                null,
                Modifier.padding(bottom = 10.dp).size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.let { Box(Modifier.padding(top = 10.dp)) { it() } }
    }
}

/** Uppercase overline. Neutral, not accented - section headers shouldn't compete with content. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 10.dp),
        style = OvertypeLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun clearListItem(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
fun <T> PickerButton(text: String, options: List<T>, optionLabel: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(text) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun flatTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
    scrolledContainerColor = MaterialTheme.colorScheme.surface,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScaffold(
    tab: Tab,
    onTab: (Tab) -> Unit,
    progress: Pair<Int, Int>? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(tab.label) },
                actions = actions,
                scrollBehavior = scroll,
                colors = flatTopBarColors(),
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { onTab(t) },
                            icon = { Icon(if (t == tab) t.selectedIcon else t.icon, null, Modifier.size(22.dp)) },
                            label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        floatingActionButton = fab,
    ) { padding ->
        Column(Modifier.padding(padding)) {
            progress?.let { (done, total) ->
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Box(Modifier.padding(padding)) { content() }
    }
}
