package io.github.madeye.meow.ui.screens.logs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.R
import io.github.madeye.meow.api.LogEntry
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.ui.theme.meow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * Live engine logs.
 *
 * Reads the controller's `/logs` websocket rather than the old `nativeGetLogs`
 * JNI call: that drained a per-process Rust ring buffer from the UI process,
 * while the engine runs in `:vpn` — so it could only ever have returned empty.
 */
class LogsViewModel(api: MeowApi) : ViewModel() {

    val logs: StateFlow<List<LogEntry>> = api.logs()
        .catch { /* the stream retries internally; a terminal failure just stops */ }
        .scan(emptyList<LogEntry>()) { acc, entry -> (acc + entry).takeLast(MAX_LINES) }
        // Closes the socket ~5s after the screen goes away, and re-dials on return.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val MAX_LINES = 500
    }
}

@Composable
fun LogsScreen(
    logs: List<LogEntry>,
    autoScroll: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    if (logs.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.logs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.meow.mutedText,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        itemsIndexed(logs) { _, entry ->
            val colors = MaterialTheme.meow
            val tint = when (entry.type.lowercase()) {
                "error" -> colors.danger
                "warning", "warn" -> colors.warning
                "info" -> colors.accent
                else -> colors.mutedText
            }
            Text(
                text = "${entry.type.uppercase().padEnd(5)} ${entry.payload}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = tint,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
fun LogsActions(autoScroll: Boolean, onToggleAutoScroll: () -> Unit) {
    IconButton(onClick = onToggleAutoScroll) {
        Icon(
            imageVector = if (autoScroll) Icons.Filled.Pause else Icons.Filled.VerticalAlignBottom,
            contentDescription = stringResource(R.string.logs_auto_scroll),
        )
    }
}
