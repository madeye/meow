package io.github.madeye.meow.ui.screens.connections

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.R
import io.github.madeye.meow.api.Connection
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectionsUiState(
    val connections: List<Connection> = emptyList(),
    val query: String = "",
) {
    val visible: List<Connection>
        get() = if (query.isBlank()) {
            connections
        } else {
            connections.filter { it.metadata.host.contains(query, ignoreCase = true) }
        }
}

class ConnectionsViewModel(private val api: MeowApi) : ViewModel() {

    private val connections = MutableStateFlow<List<Connection>>(emptyList())
    private val query = MutableStateFlow("")

    val uiState: StateFlow<ConnectionsUiState> =
        combine(connections, query) { list, search -> ConnectionsUiState(list, search) }
            .stateIn(
                viewModelScope,
                // Polling stops when the screen leaves the composition, rather
                // than running forever as the Flutter version's timer did.
                SharingStarted.WhileSubscribed(5_000),
                ConnectionsUiState(),
            )

    init {
        viewModelScope.launch {
            while (true) {
                try {
                    connections.value = api.connections().connections
                } catch (e: MeowApiException) {
                    connections.value = emptyList()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun onQueryChange(value: String) { query.value = value }

    fun close(id: String) {
        viewModelScope.launch {
            // Drop it locally straight away; the next poll confirms.
            connections.value = connections.value.filterNot { it.id == id }
            runCatching { api.closeConnection(id) }
        }
    }

    fun closeAll() {
        viewModelScope.launch {
            connections.value = emptyList()
            runCatching { api.closeAllConnections() }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
    }
}

@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    contentPadding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onClose: (String) -> Unit,
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
            placeholder = { Text(stringResource(R.string.connections_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))

        if (state.visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.connections_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.meow.mutedText,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.visible, key = { it.id }) { connection ->
                    ConnectionCard(connection = connection, onClose = { onClose(connection.id) })
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(connection: Connection, onClose: () -> Unit) {
    val colors = MaterialTheme.meow
    val host = connection.metadata.host.ifEmpty { connection.metadata.destinationIP }

    GlassCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$host:${connection.metadata.destinationPort}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (connection.chains.isNotEmpty()) {
                    Text(
                        text = connection.chains.reversed().joinToString(" → "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = buildString {
                        append("↑ ${Formatters.bytes(connection.upload)}")
                        append("  ↓ ${Formatters.bytes(connection.download)}")
                        val elapsed = Formatters.elapsedSince(connection.start)
                        if (elapsed.isNotEmpty()) append("  $elapsed")
                    },
                    style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                    color = colors.mutedText,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = colors.danger)
            }
        }
    }
}

@Composable
fun ConnectionsActions(hasConnections: Boolean, onCloseAll: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    if (hasConnections) {
        IconButton(onClick = { confirm = true }) {
            Icon(
                Icons.Filled.DeleteSweep,
                contentDescription = stringResource(R.string.connections_close_all),
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.connections_close_all_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onCloseAll() }) {
                    Text(stringResource(R.string.connections_close_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
