package app.sift.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sift.BuildConfig
import app.sift.backend.AccessState
import app.sift.data.AppInfo
import app.sift.data.Batch
import app.sift.data.BlockMode
import app.sift.data.StoreData
import app.sift.data.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    access: AccessState,
    store: StoreData,
    apps: List<AppInfo>,
    onTab: (Tab) -> Unit,
    nav: Nav,
    vm: MainViewModel,
) {
    val historyCount = store.history.size
    val ctx = LocalContext.current
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }
    var confirmMode by remember { mutableStateOf<BlockMode?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
        uri?.let(vm::exportSettings)
    }
    // Some providers hand back "application/octet-stream" for a .json file, so accept anything.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        confirmRestore = uri
    }

    TabScaffold(Tab.SETTINGS, onTab) {
        LazyColumn {
            item { SectionLabel("Access") }
            item {
                ListItem(
                    headlineContent = { Text(if (access.ready) "Connected" else "Not connected") },
                    supportingContent = { Text("Notification access and device pairing") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = clearListItem(),
                    modifier = Modifier.clickable { nav.push("setup") },
                )
            }

            item { SectionLabel("Blocking") }
            item {
                Column {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        BlockMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = store.blockMode == mode,
                                onClick = { if (store.blockMode != mode) confirmMode = mode },
                                shape = SegmentedButtonDefaults.itemShape(i, BlockMode.entries.size),
                            ) { Text(mode.label, maxLines = 1) }
                        }
                    }
                    Text(
                        store.blockMode.summary,
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionLabel("Data") }
            item {
                ListItem(
                    headlineContent = { Text("Back up settings") },
                    supportingContent = { Text("Channel settings, categories, rules and blocks. No notification history.") },
                    trailingContent = { Icon(Icons.Outlined.FileUpload, null) },
                    colors = clearListItem(),
                    modifier = Modifier.clickable { exporter.launch(backupFileName()) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Restore settings") },
                    supportingContent = { Text("Replace your settings from a backup file") },
                    trailingContent = { Icon(Icons.Outlined.FileDownload, null) },
                    colors = clearListItem(),
                    modifier = Modifier.clickable { importer.launch(arrayOf("*/*")) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Apps excluded from Logs") },
                    supportingContent = {
                        val count = store.logExcludedApps.size
                        Text(if (count == 0) "All apps are included" else "$count ${if (count == 1) "app" else "apps"} excluded")
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = clearListItem(),
                    modifier = Modifier.clickable { nav.push("log-exclusions") },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Change history") },
                    supportingContent = {
                        Text(
                            when (historyCount) {
                                0 -> "No changes yet"
                                1 -> "1 change you can undo"
                                else -> "$historyCount changes you can undo"
                            },
                        )
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = clearListItem(),
                    modifier = Modifier.clickable { nav.push("history") },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Rescan apps") },
                    supportingContent = { Text("${apps.size} apps scanned") },
                    trailingContent = { Icon(Icons.Default.Refresh, null) },
                    colors = clearListItem(),
                    modifier = Modifier.clickable(enabled = access.ready) { vm.scan() },
                )
            }

            item { SectionLabel("Appearance") }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = store.theme == mode,
                            onClick = { vm.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Material You") },
                    supportingContent = { Text("Use colours from your wallpaper instead of the app\u2019s own palette") },
                    trailingContent = { Switch(checked = store.materialYou, onCheckedChange = vm::setMaterialYou, colors = quietSwitchColors()) },
                    colors = clearListItem(),
                    modifier = Modifier.clickable { vm.setMaterialYou(!store.materialYou) },
                )
            }

            item { SectionLabel("About") }
            item {
                ListItem(
                    headlineContent = { Wordmark(MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("Version ${BuildConfig.VERSION_NAME} · Everything stays on this device") },
                    leadingContent = { BrandMark(38.dp) },
                    colors = clearListItem(),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Source code") },
                    supportingContent = { Text(REPO_LABEL) },
                    leadingContent = { Icon(Icons.Outlined.Code, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)) },
                    colors = clearListItem(),
                    modifier = Modifier.clickable { openLink(ctx, REPO_URL) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Report an issue") },
                    supportingContent = { Text("Bugs, wrong categories and feature ideas") },
                    leadingContent = { Icon(Icons.Outlined.BugReport, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)) },
                    colors = clearListItem(),
                    modifier = Modifier.clickable { openLink(ctx, ISSUES_URL) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Sponsor") },
                    supportingContent = { Text("Sift is free and has no ads. Support its development on GitHub Sponsors.") },
                    leadingContent = { Icon(Icons.Outlined.FavoriteBorder, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)) },
                    colors = clearListItem(),
                    modifier = Modifier.clickable { openLink(ctx, SPONSOR_URL) },
                )
            }
        }
    }

    confirmMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirmMode = null },
            title = { Text("Switch to ${mode.label.lowercase()}?") },
            text = {
                val owned = store.logBlocked.size
                val channels = "$owned ${if (owned == 1) "channel" else "channels"}"
                Text(
                    mode.summary + "\n\n" + if (mode == BlockMode.BLOCK_FULLY) {
                        "Sift will turn off the $channels it blocks, and \u201ckeep\u201d rules will stop working for them."
                    } else {
                        "Sift will set the $channels it blocks back to minimised, so it can remove and log them again."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.setBlockMode(mode); confirmMode = null }) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { confirmMode = null }) { Text("Cancel") } },
        )
    }
    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore settings?") },
            text = {
                Text(
                    "This replaces your categories, rules, blocks and appearance with the ones in the file. " +
                        "Your notification log is left alone. Sift will rescan your apps and re-apply the restored blocks.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.importSettings(uri); confirmRestore = null }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
}

private const val BACKUP_MIME = "application/json"
const val REPO_URL = "https://github.com/semi-column/sift"
private const val REPO_LABEL = "github.com/semi-column/sift"
private const val ISSUES_URL = "$REPO_URL/issues"
private const val SPONSOR_URL = "https://github.com/sponsors/semi-column"

private fun backupFileName(): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return "sift-settings-$day.json"
}

/** Hands the URL to a browser. Sift has no internet permission and never fetches anything itself. */
private fun openLink(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

@Composable
fun HistoryScreen(history: List<Batch>, nav: Nav, vm: MainViewModel) {
    DetailScaffold("Change history", nav::back) {
        if (history.isEmpty()) {
            EmptyState("No changes yet", "Every change you make shows up here and can be undone.")
            return@DetailScaffold
        }
        LazyColumn {
            items(history, key = { it.id }) { b ->
                ListItem(
                    headlineContent = { Text(b.title) },
                    supportingContent = {
                        Text("${b.changes.size} ${if (b.changes.size == 1) "channel" else "channels"} · ${DateUtils.getRelativeTimeSpanString(b.time)}")
                    },
                    trailingContent = { TextButton(onClick = { vm.undo(b) }) { Text("Undo") } },
                    colors = clearListItem(),
                )
            }
        }
    }
}
