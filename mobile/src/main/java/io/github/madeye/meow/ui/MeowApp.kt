package io.github.madeye.meow.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.madeye.meow.AppGraph
import io.github.madeye.meow.R
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.ui.components.MeowScaffold
import io.github.madeye.meow.ui.nav.Dest
import io.github.madeye.meow.ui.nav.TABS
import io.github.madeye.meow.ui.screens.connections.ConnectionsActions
import io.github.madeye.meow.ui.screens.connections.ConnectionsScreen
import io.github.madeye.meow.ui.screens.connections.ConnectionsViewModel
import io.github.madeye.meow.ui.screens.home.HomeScreen
import io.github.madeye.meow.ui.screens.home.HomeViewModel
import io.github.madeye.meow.ui.screens.logs.LogsActions
import io.github.madeye.meow.ui.screens.logs.LogsScreen
import io.github.madeye.meow.ui.screens.logs.LogsViewModel
import io.github.madeye.meow.ui.screens.perapp.PerAppProxyScreen
import io.github.madeye.meow.ui.screens.perapp.PerAppProxyViewModel
import io.github.madeye.meow.ui.screens.rules.RulesScreen
import io.github.madeye.meow.ui.screens.rules.RulesViewModel
import io.github.madeye.meow.ui.screens.settings.SettingsScreen
import io.github.madeye.meow.ui.screens.settings.SettingsViewModel
import io.github.madeye.meow.ui.screens.subscribe.ProfileUi
import io.github.madeye.meow.ui.screens.subscribe.SubscribeEvent
import io.github.madeye.meow.ui.screens.subscribe.SubscribeScreen
import io.github.madeye.meow.ui.screens.subscribe.SubscribeViewModel
import io.github.madeye.meow.ui.screens.subscribe.SubscriptionDialog
import io.github.madeye.meow.ui.screens.yaml.YamlEditorActions
import io.github.madeye.meow.ui.screens.yaml.YamlEditorScreen
import io.github.madeye.meow.ui.screens.yaml.YamlEditorViewModel
import io.github.madeye.meow.ui.screens.yaml.rememberSoraEditorHandle
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.readText
import io.github.madeye.meow.ui.util.rememberClipboardText
import io.github.madeye.meow.ui.util.writeText
import kotlinx.coroutines.launch

/**
 * Root of the Compose UI: four tabs plus the pushed detail screens.
 *
 * @param autoConnect set by the e2e harness via `--ez auto_connect true`.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MeowApp(autoConnect: Boolean = false) {
    val navController = rememberNavController()
    val snackbarHost = remember { SnackbarHostState() }

    Box(
        // Exposes every Modifier.testTag as a resource-id in uiautomator dumps,
        // so e2e can wait on a stable id instead of localized text.
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
    ) {
        MeowNavHost(
            navController = navController,
            snackbarHost = snackbarHost,
            autoConnect = autoConnect,
        )
    }
}

@Composable
private fun MeowNavHost(
    navController: NavHostController,
    snackbarHost: SnackbarHostState,
    autoConnect: Boolean,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onTab = TABS.any { tab ->
        backStackEntry?.destination?.hierarchy?.any { it.hasRoute(tab.dest::class) } == true
    }

    NavHost(navController = navController, startDestination = Dest.Home) {
        composable<Dest.Home> {
            HomeRoute(snackbarHost = snackbarHost, autoConnect = autoConnect) {
                BottomBar(navController, onTab)
            }
        }
        composable<Dest.Subscribe> {
            SubscribeRoute(
                snackbarHost = snackbarHost,
                onEditYaml = { navController.navigate(Dest.YamlEditor(it)) },
                bottomBar = { BottomBar(navController, onTab) },
            )
        }
        composable<Dest.Traffic> {
            TrafficRoute { BottomBar(navController, onTab) }
        }
        composable<Dest.Settings> {
            SettingsRoute(
                onPerAppProxy = { navController.navigate(Dest.PerAppProxy) },
                onConnections = { navController.navigate(Dest.Connections) },
                onRules = { navController.navigate(Dest.Rules) },
                onLogs = { navController.navigate(Dest.Logs) },
                bottomBar = { BottomBar(navController, onTab) },
            )
        }

        composable<Dest.PerAppProxy> { PerAppProxyRoute(onBack = navController::popBackStack) }
        composable<Dest.YamlEditor> { entry ->
            YamlEditorRoute(
                snackbarHost = snackbarHost,
                onBack = navController::popBackStack,
            )
        }
        composable<Dest.Connections> { ConnectionsRoute(onBack = navController::popBackStack) }
        composable<Dest.Rules> { RulesRoute(onBack = navController::popBackStack) }
        composable<Dest.Logs> { LogsRoute(onBack = navController::popBackStack) }
    }
}

@Composable
private fun BottomBar(navController: NavHostController, visible: Boolean) {
    if (!visible) return
    val backStackEntry by navController.currentBackStackEntryAsState()
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        TABS.forEach { tab ->
            val selected = backStackEntry?.destination?.hierarchy
                ?.any { it.hasRoute(tab.dest::class) } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.dest) {
                        // Restores each tab's saved scroll/search state, the
                        // equivalent of Flutter's IndexedStack.
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
                modifier = Modifier.testTag(tab.testTag),
                // M3 defaults the selected pill to secondaryContainer, which is
                // the ginger brand accent here — selection has to read as the
                // blue accent, same as the iOS tab bar.
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.meow.mutedText,
                    unselectedTextColor = MaterialTheme.meow.mutedText,
                ),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Routes
// -----------------------------------------------------------------------------

@Composable
private fun HomeRoute(
    snackbarHost: SnackbarHostState,
    autoConnect: Boolean,
    bottomBar: @Composable () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnService(context)
    }

    fun connect() {
        viewModel.onConnectRequested()
        val intent = android.net.VpnService.prepare(context)
        if (intent == null) startVpnService(context) else vpnPermission.launch(intent)
    }

    // The :vpn process can be killed while backgrounded, leaving stale state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    // Preserves the harness contract: launch with auto_connect and the VPN
    // starts once the service reports it has settled.
    var autoConnectHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoConnect, state.state) {
        if (autoConnect && !autoConnectHandled && state.state == BaseService.State.Stopped) {
            autoConnectHandled = true
            connect()
        }
    }

    MeowScaffold(
        title = stringResource(R.string.app_name),
        bottomBar = bottomBar,
    ) { padding ->
        HomeScreen(
            state = state,
            contentPadding = padding,
            onToggle = { checked ->
                if (checked) connect() else viewModel.onDisconnect(context)
            },
            onToggleExpanded = viewModel::onToggleExpanded,
            onSelectNode = viewModel::onSelectNode,
            onTestGroup = viewModel::onTestGroup,
        )
    }
    SnackbarHost(snackbarHost)
}

private fun startVpnService(context: android.content.Context) {
    // minSdk is 24; startForegroundService is API 26+.
    androidx.core.content.ContextCompat.startForegroundService(
        context,
        Intent(context, io.github.madeye.meow.bg.VpnService::class.java),
    )
}

@Composable
private fun SubscribeRoute(
    snackbarHost: SnackbarHostState,
    onEditYaml: (Long) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val viewModel: SubscribeViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = rememberClipboardText()

    var dialogFor by remember { mutableStateOf<ProfileUi?>(null) }
    var dialogOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ProfileUi?>(null) }

    // Hoisted out of the callbacks: resolving resources through LocalContext
    // inside a lambda skips Compose's configuration tracking.
    val clipboardEmpty = stringResource(R.string.subs_clipboard_empty)
    val exportSaved = stringResource(R.string.subs_export_saved)
    val exportFailedFmt = stringResource(R.string.subs_export_failed)
    val importedFmt = stringResource(R.string.subs_imported)
    val importFailedFmt = stringResource(R.string.subs_import_failed)
    val updatedFmt = stringResource(R.string.subs_updated)
    val refreshFailedFmt = stringResource(R.string.subs_refresh_failed)

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = context.readText(uri)
            if (content != null) {
                viewModel.import(uri.lastPathSegment?.substringAfterLast('/') ?: "config", content)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-yaml"),
    ) { uri ->
        val profile = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val yaml = viewModel.yamlOf(profile.id)
            if (context.writeText(uri, yaml)) {
                viewModel.onExported()
                snackbarHost.showSnackbar(exportSaved)
            } else {
                snackbarHost.showSnackbar(String.format(exportFailedFmt, profile.name))
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is SubscribeEvent.Imported -> String.format(importedFmt, event.name)
                is SubscribeEvent.Updated -> String.format(updatedFmt, event.name)
                is SubscribeEvent.ImportFailed -> String.format(importFailedFmt, event.reason)
                is SubscribeEvent.RefreshFailed -> String.format(refreshFailedFmt, event.reason)
                is SubscribeEvent.Failure -> event.reason
            }
            snackbarHost.showSnackbar(message)
        }
    }

    MeowScaffold(
        title = stringResource(R.string.subs_title),
        bottomBar = bottomBar,
        actions = {
            IconButton(onClick = viewModel::refreshAll) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
            }
            Box {
                IconButton(onClick = { addMenuOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.subs_add))
                }
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subs_add_from_url)) },
                        onClick = {
                            addMenuOpen = false
                            dialogFor = null
                            dialogOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subs_import_from_file)) },
                        onClick = {
                            addMenuOpen = false
                            importLauncher.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
        },
    ) { padding ->
        SubscribeScreen(
            state = state,
            contentPadding = padding,
            onSelect = viewModel::select,
            onEdit = { dialogFor = it; dialogOpen = true },
            onEditYaml = onEditYaml,
            onExport = { profile ->
                pendingExport = profile
                exportLauncher.launch("${profile.name}.yaml")
            },
            onRefresh = viewModel::refresh,
            onDelete = viewModel::delete,
            onAddRequested = { dialogFor = null; dialogOpen = true },
        )
    }

    if (dialogOpen) {
        val editing = dialogFor
        SubscriptionDialog(
            initial = editing,
            onDismiss = { dialogOpen = false },
            onConfirm = { name, url ->
                dialogOpen = false
                if (editing == null) viewModel.add(name, url) else viewModel.update(editing.id, name, url)
            },
            clipboardText = clipboard,
            onClipboardEmpty = { scope.launch { snackbarHost.showSnackbar(clipboardEmpty) } },
        )
    }
    SnackbarHost(snackbarHost)
}

@Composable
private fun TrafficRoute(bottomBar: @Composable () -> Unit) {
    val viewModel: io.github.madeye.meow.ui.screens.traffic.TrafficViewModel =
        viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MeowScaffold(title = stringResource(R.string.traffic_title), bottomBar = bottomBar) { padding ->
        io.github.madeye.meow.ui.screens.traffic.TrafficScreen(
            state = state,
            contentPadding = padding,
            onSelectDay = viewModel::onSelectDay,
        )
    }
}

@Composable
private fun SettingsRoute(
    onPerAppProxy: () -> Unit,
    onConnections: () -> Unit,
    onRules: () -> Unit,
    onLogs: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MeowScaffold(title = stringResource(R.string.settings_title), bottomBar = bottomBar) { padding ->
        SettingsScreen(
            state = state,
            contentPadding = padding,
            onPerAppProxy = onPerAppProxy,
            onConnections = onConnections,
            onRules = onRules,
            onLogs = onLogs,
        )
    }
}

@Composable
private fun PerAppProxyRoute(onBack: () -> Unit) {
    val viewModel: PerAppProxyViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    MeowScaffold(
        title = stringResource(R.string.perapp_title),
        navigationIcon = { BackButton(onBack) },
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Both act on the filtered list, so "select all" during a
                    // search means "all of these", not "all installed apps".
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.perapp_select_all)) },
                        onClick = {
                            menuOpen = false
                            viewModel.onSelectAllVisible(state.visibleApps)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.perapp_deselect_all)) },
                        onClick = {
                            menuOpen = false
                            viewModel.onDeselectAllVisible(state.visibleApps)
                        },
                    )
                }
            }
            IconButton(onClick = { viewModel.save(onBack) }) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_save))
            }
        },
    ) { padding ->
        PerAppProxyScreen(
            state = state,
            contentPadding = padding,
            onQueryChange = viewModel::onQueryChange,
            onToggleSystemApps = viewModel::onToggleSystemApps,
            onModeChange = viewModel::onModeChange,
            onToggleApp = viewModel::onToggleApp,
            iconLoader = viewModel::icon,
        )
    }
}

@Composable
private fun YamlEditorRoute(snackbarHost: SnackbarHostState, onBack: () -> Unit) {
    val viewModel: YamlEditorViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val initialText by viewModel.initialText.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val canRevert by viewModel.canRevert.collectAsStateWithLifecycle()
    val name by viewModel.profileName.collectAsStateWithLifecycle()
    val handle = rememberSoraEditorHandle()
    val scope = rememberCoroutineScope()
    val revertedMessage = stringResource(R.string.yaml_reverted)
    val savedMessage = stringResource(R.string.yaml_saved)

    MeowScaffold(
        title = name,
        navigationIcon = { BackButton(onBack) },
        actions = {
            YamlEditorActions(
                dirty = dirty,
                valid = error == null,
                canRevert = canRevert,
                onRevert = {
                    viewModel.revert { reverted ->
                        handle.setText(reverted)
                        scope.launch { snackbarHost.showSnackbar(revertedMessage) }
                    }
                },
                onSave = {
                    viewModel.save(handle.text()) {
                        scope.launch { snackbarHost.showSnackbar(savedMessage) }
                    }
                },
            )
        },
    ) { padding ->
        YamlEditorScreen(
            initialText = initialText,
            error = error,
            dirty = dirty,
            canRevert = canRevert,
            contentPadding = padding,
            onEdit = viewModel::onEdit,
            onRequestSave = { viewModel.save(it) {} },
            onRequestRevert = { viewModel.revert { handle.setText(it) } },
            onNavigateBack = onBack,
            handle = handle,
        )
    }
    SnackbarHost(snackbarHost)
}

@Composable
private fun ConnectionsRoute(onBack: () -> Unit) {
    val viewModel: ConnectionsViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MeowScaffold(
        title = stringResource(R.string.connections_title),
        navigationIcon = { BackButton(onBack) },
        actions = {
            ConnectionsActions(
                hasConnections = state.connections.isNotEmpty(),
                onCloseAll = viewModel::closeAll,
            )
        },
    ) { padding ->
        ConnectionsScreen(
            state = state,
            contentPadding = padding,
            onQueryChange = viewModel::onQueryChange,
            onClose = viewModel::close,
        )
    }
}

@Composable
private fun RulesRoute(onBack: () -> Unit) {
    val viewModel: RulesViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MeowScaffold(
        title = if (state.rules.isEmpty()) {
            stringResource(R.string.rules_title)
        } else {
            pluralStringResource(R.plurals.rules_count, state.rules.size, state.rules.size)
        },
        navigationIcon = { BackButton(onBack) },
    ) { padding ->
        RulesScreen(
            state = state,
            contentPadding = padding,
            onQueryChange = viewModel::onQueryChange,
            onRetry = viewModel::load,
        )
    }
}

@Composable
private fun LogsRoute(onBack: () -> Unit) {
    val viewModel: LogsViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var autoScroll by rememberSaveable { mutableStateOf(true) }

    MeowScaffold(
        title = stringResource(R.string.logs_title),
        navigationIcon = { BackButton(onBack) },
        actions = { LogsActions(autoScroll) { autoScroll = !autoScroll } },
    ) { padding ->
        LogsScreen(logs = logs, autoScroll = autoScroll, contentPadding = padding)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.common_back),
        )
    }
}
