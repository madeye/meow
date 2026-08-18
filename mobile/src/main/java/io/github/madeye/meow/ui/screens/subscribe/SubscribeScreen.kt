package io.github.madeye.meow.ui.screens.subscribe

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters

@Composable
fun SubscribeScreen(
    state: SubscribeUiState,
    contentPadding: PaddingValues,
    onSelect: (Long) -> Unit,
    onEdit: (ProfileUi) -> Unit,
    onEditYaml: (Long) -> Unit,
    onExport: (ProfileUi) -> Unit,
    onRefresh: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onAddRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (state.profiles.isEmpty()) {
            EmptyState(contentPadding = contentPadding, onAdd = onAddRequested)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        onSelect = { onSelect(profile.id) },
                        onEdit = { onEdit(profile) },
                        onEditYaml = { onEditYaml(profile.id) },
                        onExport = { onExport(profile) },
                        onRefresh = { onRefresh(profile.id) },
                        onDelete = { onDelete(profile.id) },
                    )
                }
            }
        }

        if (state.busy) {
            // Long operations (fetch, refresh-all, import) block interaction
            // rather than letting a second one start mid-flight.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                GlassCard(modifier = Modifier.size(72.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(contentPadding: PaddingValues, onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.meow.mutedText.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.subs_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.meow.mutedText,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onAdd) { Text(stringResource(R.string.subs_add)) }
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileUi,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onEditYaml: () -> Unit,
    onExport: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val colors = MaterialTheme.meow

    GlassCard(onClick = onSelect) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (profile.selected) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (profile.selected) colors.accent else colors.mutedText.copy(alpha = 0.5f),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (profile.url.isNotEmpty()) {
                    Text(
                        text = profile.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (profile.lastUpdated > 0) {
                    Text(
                        text = stringResource(
                            R.string.subs_last_updated,
                            Formatters.timestamp(profile.lastUpdated),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = colors.mutedText)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_select)) },
                        onClick = { menuOpen = false; onSelect() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    if (profile.hasYaml) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.subs_edit_yaml)) },
                            onClick = { menuOpen = false; onEditYaml() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.subs_export)) },
                            onClick = { menuOpen = false; onExport() },
                        )
                    }
                    if (profile.url.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_refresh)) },
                            onClick = { menuOpen = false; onRefresh() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete)) },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.subs_delete_confirm, profile.name)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Add/edit dialog. [initial] non-null means edit. */
@Composable
fun SubscriptionDialog(
    initial: ProfileUi?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
    clipboardText: () -> String?,
    onClipboardEmpty: () -> Unit,
) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var url by rememberSaveable(initial?.id) { mutableStateOf(initial?.url.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (initial == null) R.string.subs_add else R.string.subs_edit),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.subs_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.subs_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val text = clipboardText()
                                if (text.isNullOrBlank()) onClipboardEmpty() else url = text
                            },
                        ) {
                            Icon(
                                Icons.Filled.ContentPaste,
                                contentDescription = stringResource(R.string.subs_paste),
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(if (initial == null) R.string.common_add else R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
