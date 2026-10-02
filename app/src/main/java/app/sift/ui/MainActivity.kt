package app.sift.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sift.App
import app.sift.data.Category
import app.sift.data.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/** How long an undoable message stays up. Material's `Long` is 10s, which feels stuck. */
private const val UNDO_MILLIS = 6_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val store by App.of(this).store.data.collectAsStateWithLifecycle()
            val dark = when (store.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's theme, not the system's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            SiftTheme(dark, store.materialYou) { AppRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        App.of(this).access.refresh()
    }
}

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    CATEGORIES("Categories", Icons.Outlined.Category, Icons.Filled.Category),
    APPS("Apps", Icons.Outlined.Apps, Icons.Filled.Apps),
    LOGS("Logs", Icons.Outlined.Inbox, Icons.Filled.Inbox),
    RULES("Rules", Icons.Outlined.FilterAlt, Icons.Filled.FilterAlt),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** Minimal back stack: routes are "category/NAME", "app/PKG", "history", "setup". */
class Nav(private val stack: MutableList<String>) {
    fun push(route: String) = stack.add(route)
    fun back() {
        stack.removeLastOrNull()
    }
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val access by vm.access.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val store by vm.store.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.CATEGORIES) }
    var inSetup by rememberSaveable { mutableStateOf(!access.configured) }
    val stack = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    val nav = remember(stack) { Nav(stack) }

    LaunchedEffect(Unit) {
        var showing: Job? = null
        vm.message.filterNotNull().collect { m ->
            vm.message.value = null
            // Newest message wins. Cancelling the previous one dismisses it immediately
            // (showSnackbar clears the host in its `finally`), so a new message never has to
            // wait out the old one's full duration behind the host's mutex.
            showing?.cancelAndJoin()
            showing = launch {
                val undoable = m.undo != null || m.onUndo != null
                val result = if (undoable) {
                    // Material's Long is 10s, which reads as stuck. Indefinite plus our own
                    // timeout gives an undo window long enough to use but short enough to ignore.
                    withTimeoutOrNull(UNDO_MILLIS) {
                        snackbar.showSnackbar(m.text, "Undo", duration = SnackbarDuration.Indefinite)
                    }
                } else {
                    snackbar.showSnackbar(m.text, duration = SnackbarDuration.Short)
                }
                if (result == SnackbarResult.ActionPerformed) {
                    m.undo?.let(vm::undo)
                    m.onUndo?.invoke()
                }
            }
        }
    }
    LaunchedEffect(access.ready) { if (access.ready) vm.scanOnce() }
    BackHandler(stack.isNotEmpty()) { nav.back() }

    val setup = inSetup || !access.configured
    val route = stack.lastOrNull()

    Box(Modifier.fillMaxSize()) {
        when {
            setup -> SetupScreen(access, vm, onBack = null, onContinue = { inSetup = false })
            route == null -> when (tab) {
                Tab.CATEGORIES -> HomeScreen(apps, history, store.policies, access, progress, onTab = { tab = it }, vm) {
                    nav.push("category/${it.name}")
                }
                Tab.APPS -> AppsScreen(apps, history, progress, onTab = { tab = it }) { nav.push("app/$it") }
                Tab.LOGS -> LogsScreen(history, apps, store.blockMode, access, onTab = { tab = it }, nav, vm)
                Tab.RULES -> RulesScreen(store.rules, apps, history, store.blockMode, access, onTab = { tab = it }, nav, vm)
                Tab.SETTINGS -> SettingsScreen(access, store, apps, onTab = { tab = it }, nav, vm)
            }
            route.startsWith("category/") -> {
                val cat = Category.valueOf(route.removePrefix("category/"))
                CategoryScreen(cat, apps, store.policies[cat], nav, vm)
            }
            route.startsWith("app/") -> {
                AppDetailScreen(apps.firstOrNull { it.pkg == route.removePrefix("app/") }, nav, vm)
            }
            route == "history" -> HistoryScreen(store.history, nav, vm)
            route == "log-exclusions" -> LogExclusionsScreen(apps, store.logExcludedApps, nav, vm)
            route == "setup" -> SetupScreen(access, vm, onBack = nav::back, onContinue = null)
        }

        // One host for the whole app. Per-screen hosts were being disposed on every tab change,
        // which restarted SnackbarHost's internal dismiss timer and replayed its enter animation.
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = if (setup) 84.dp else if (route == null) 80.dp else 0.dp),
        )
    }
}
