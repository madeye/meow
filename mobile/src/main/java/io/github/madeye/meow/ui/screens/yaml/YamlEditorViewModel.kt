package io.github.madeye.meow.ui.screens.yaml

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.repo.ConfigValidator
import io.github.madeye.meow.repo.ProfileRepository
import io.github.madeye.meow.ui.nav.Dest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.navigation.toRoute

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class YamlEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val validator: ConfigValidator,
    private val analytics: Analytics,
) : ViewModel() {

    private val profileId: Long = savedStateHandle.toRoute<Dest.YamlEditor>().profileId

    private val _profileName = MutableStateFlow("")
    val profileName: StateFlow<String> = _profileName.asStateFlow()

    private val _initialText = MutableStateFlow<String?>(null)

    /** Null until the profile has been read; the editor waits for it. */
    val initialText: StateFlow<String?> = _initialText.asStateFlow()

    private val _canRevert = MutableStateFlow(false)
    val canRevert: StateFlow<Boolean> = _canRevert.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val edits = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Null means valid. `mapLatest` cancels an in-flight validation when the
     * user keeps typing, so a slow result can never overwrite a newer one.
     */
    val error: StateFlow<String?> = edits
        .debounce(VALIDATION_DEBOUNCE_MS)
        .mapLatest { text -> validator.validate(text) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val profile = profiles.getById(profileId)
            _profileName.value = profile?.name.orEmpty()
            _initialText.value = profile?.yamlContent.orEmpty()
            _canRevert.value = !profile?.yamlBackup.isNullOrEmpty() &&
                profile?.yamlBackup != profile?.yamlContent
        }
    }

    fun onEdit(text: String) {
        _dirty.value = text != _initialText.value
        edits.tryEmit(text)
    }

    fun save(text: String, onDone: () -> Unit) {
        viewModelScope.launch {
            profiles.updateYaml(profileId, text)
            analytics.profileYamlEdit()
            _initialText.value = text
            _dirty.value = false
            onDone()
        }
    }

    fun revert(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val reverted = profiles.revertYaml(profileId)
            analytics.profileYamlRevert()
            _initialText.value = reverted
            _dirty.value = false
            _canRevert.value = false
            onDone(reverted)
        }
    }

    private companion object {
        const val VALIDATION_DEBOUNCE_MS = 300L
    }
}
