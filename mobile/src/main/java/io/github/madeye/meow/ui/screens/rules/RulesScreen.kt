package io.github.madeye.meow.ui.screens.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.R
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.Rule
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RulesUiState(
    val rules: List<Rule> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val failed: Boolean = false,
) {
    val visible: List<Rule>
        get() = if (query.isBlank()) {
            rules
        } else {
            rules.filter {
                it.type.contains(query, true) ||
                    it.payload.contains(query, true) ||
                    it.proxy.contains(query, true)
            }
        }
}

class RulesViewModel(private val api: MeowApi) : ViewModel() {

    private val rules = MutableStateFlow<List<Rule>>(emptyList())
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val failed = MutableStateFlow(false)

    val uiState: StateFlow<RulesUiState> =
        combine(rules, query, loading, failed) { list, search, isLoading, didFail ->
            RulesUiState(list, search, isLoading, didFail)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

    init {
        load()
    }

    fun onQueryChange(value: String) { query.value = value }

    fun load() {
        viewModelScope.launch {
            loading.value = true
            failed.value = false
            try {
                rules.value = api.rules()
            } catch (e: MeowApiException) {
                failed.value = true
            } finally {
                loading.value = false
            }
        }
    }
}

@Composable
fun RulesScreen(
    state: RulesUiState,
    contentPadding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.rules_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))

        when {
            state.failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.rules_load_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.meow.danger,
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                }
            }

            state.visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.rules_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.meow.mutedText,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.visible) { rule -> RuleRow(rule) }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: Rule) {
    val colors = MaterialTheme.meow
    val tint = when (rule.proxy.uppercase()) {
        "DIRECT" -> colors.connected
        "REJECT" -> colors.danger
        else -> colors.accent
    }
    GlassCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rule.type,
                style = MaterialTheme.typography.labelSmall.merge(MeowTextStyles.monoDigits),
                color = tint,
                modifier = Modifier
                    .background(tint.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(0.dp))
            Text(
                text = rule.payload,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                text = rule.proxy,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
            )
        }
    }
}
