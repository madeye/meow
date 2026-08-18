package io.github.madeye.meow.ui.screens.subscribe

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.repo.ConfigValidator
import io.github.madeye.meow.repo.ProfileRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Compose skips recomposition only for stable types. The Room entity has `var`
 * fields throughout, so it is mapped to this immutable view before reaching any
 * composable.
 */
@Immutable
data class ProfileUi(
    val id: Long,
    val name: String,
    val url: String,
    val selected: Boolean,
    val lastUpdated: Long,
    val hasYaml: Boolean,
    val hasBackup: Boolean,
)

@Immutable
data class SubscribeUiState(
    val profiles: List<ProfileUi> = emptyList(),
    val busy: Boolean = false,
)

/**
 * One-shot user feedback; not state, so it is not replayed on rotation.
 *
 * The events carry data, not sentences — the route turns them into localized
 * text, so no string formatting leaks into the ViewModel.
 */
sealed interface SubscribeEvent {
    data class Imported(val name: String) : SubscribeEvent
    data class Updated(val name: String) : SubscribeEvent
    data class ImportFailed(val reason: String) : SubscribeEvent
    data class RefreshFailed(val reason: String) : SubscribeEvent
    data class Failure(val reason: String) : SubscribeEvent
}

class SubscribeViewModel(
    private val profiles: ProfileRepository,
    private val validator: ConfigValidator,
    private val analytics: Analytics,
) : ViewModel() {

    private val busy = MutableStateFlow(false)

    private val _events = MutableSharedFlow<SubscribeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SubscribeEvent> = _events.asSharedFlow()

    val uiState: StateFlow<SubscribeUiState> =
        combine(profiles.observeAll(), busy) { list, isBusy ->
            SubscribeUiState(
                profiles = list.map {
                    ProfileUi(
                        id = it.id,
                        name = it.name,
                        url = it.url,
                        selected = it.selected,
                        lastUpdated = it.lastUpdated,
                        hasYaml = it.yamlContent.isNotEmpty(),
                        hasBackup = it.yamlBackup.isNotEmpty(),
                    )
                },
                busy = isBusy,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubscribeUiState())

    fun add(name: String, url: String) = withBusy {
        profiles.add(name.ifBlank { url }, url)
        analytics.subscriptionAdd()
    }

    fun update(id: Long, name: String, url: String) = withBusy {
        profiles.update(id, name, url)
        analytics.subscriptionEdit()
    }

    fun delete(id: Long) = withBusy {
        profiles.delete(id)
        analytics.subscriptionDelete()
    }

    fun select(id: Long) = withBusy {
        profiles.select(id)
        analytics.profileSelect()
    }

    fun refresh(id: Long) {
        viewModelScope.launch {
            busy.value = true
            try {
                profiles.refresh(id)
                analytics.subscriptionRefresh()
                _events.tryEmit(SubscribeEvent.Updated(profiles.getById(id)?.name.orEmpty()))
            } catch (e: Exception) {
                _events.tryEmit(SubscribeEvent.RefreshFailed(e.reason()))
            } finally {
                busy.value = false
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            busy.value = true
            try {
                profiles.refreshAll()
                analytics.subscriptionRefreshAll()
            } catch (e: Exception) {
                _events.tryEmit(SubscribeEvent.RefreshFailed(e.reason()))
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Imports a YAML file the user picked. The engine validates it — the UI
     * never parses config itself, so an import can't be accepted here and then
     * rejected at connect time.
     */
    fun import(name: String, yaml: String) {
        viewModelScope.launch {
            busy.value = true
            try {
                val error = validator.validate(yaml)
                if (error != null) {
                    _events.tryEmit(SubscribeEvent.ImportFailed(error))
                    return@launch
                }
                profiles.addLocal(name, yaml)
                analytics.configImport()
                _events.tryEmit(SubscribeEvent.Imported(name))
            } catch (e: Exception) {
                _events.tryEmit(SubscribeEvent.ImportFailed(e.reason()))
            } finally {
                busy.value = false
            }
        }
    }

    fun onExported() = analytics.configExport()

    suspend fun yamlOf(id: Long): String = profiles.getById(id)?.yamlContent.orEmpty()

    private fun withBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: Exception) {
                _events.tryEmit(SubscribeEvent.Failure(e.reason()))
            } finally {
                busy.value = false
            }
        }
    }

    /** Network stacks like to throw with a null message; fall back to the type. */
    private fun Exception.reason(): String = message ?: this::class.java.simpleName
}
