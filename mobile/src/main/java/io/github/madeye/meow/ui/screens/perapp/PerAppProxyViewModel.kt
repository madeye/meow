package io.github.madeye.meow.ui.screens.perapp

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.repo.InstalledApp
import io.github.madeye.meow.repo.InstalledAppsRepository
import io.github.madeye.meow.repo.PerAppConfig
import io.github.madeye.meow.repo.PerAppMode
import io.github.madeye.meow.repo.PerAppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class PerAppUiState(
    val loading: Boolean = true,
    val mode: PerAppMode = PerAppMode.Proxy,
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val showSystemApps: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
) {
    /** Filtering happens here so the list and "select all" always agree. */
    val visibleApps: List<InstalledApp>
        get() = apps.filter { app ->
            (showSystemApps || !app.isSystem || app.packageName in selected) &&
                (
                    query.isBlank() ||
                        app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                    )
        }
}

class PerAppProxyViewModel(
    private val perApp: PerAppRepository,
    private val installedApps: InstalledAppsRepository,
    private val analytics: Analytics,
) : ViewModel() {

    private val config = MutableStateFlow(PerAppConfig())
    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val query = MutableStateFlow("")
    private val showSystem = MutableStateFlow(false)

    private val iconCache = mutableMapOf<String, Drawable?>()

    val uiState: StateFlow<PerAppUiState> = combine(
        config,
        apps,
        loading,
        query,
        showSystem,
    ) { current, appList, isLoading, search, system ->
        PerAppUiState(
            loading = isLoading,
            mode = current.mode,
            selected = current.packages,
            query = search,
            showSystemApps = system,
            apps = appList,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PerAppUiState())

    init {
        viewModelScope.launch {
            config.value = perApp.load()
            apps.value = installedApps.load()
            loading.value = false
        }
    }

    fun onQueryChange(value: String) { query.value = value }

    fun onToggleSystemApps() { showSystem.value = !showSystem.value }

    fun onModeChange(mode: PerAppMode) {
        config.value = config.value.copy(mode = mode)
    }

    fun onToggleApp(packageName: String) {
        val current = config.value.packages
        config.value = config.value.copy(
            packages = if (packageName in current) current - packageName else current + packageName,
        )
    }

    /** Applies to the currently filtered list only, matching the old behaviour. */
    fun onSelectAllVisible(visible: List<InstalledApp>) {
        config.value = config.value.copy(
            packages = config.value.packages + visible.map { it.packageName },
        )
    }

    fun onDeselectAllVisible(visible: List<InstalledApp>) {
        config.value = config.value.copy(
            packages = config.value.packages - visible.map { it.packageName }.toSet(),
        )
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            perApp.save(config.value)
            analytics.perAppProxySave(config.value.mode.key)
            onSaved()
        }
    }

    /** Icons are fetched per row and memoised; loading all of them up front is visibly slow. */
    suspend fun icon(packageName: String): Drawable? =
        iconCache.getOrPut(packageName) { installedApps.icon(packageName) }
}
