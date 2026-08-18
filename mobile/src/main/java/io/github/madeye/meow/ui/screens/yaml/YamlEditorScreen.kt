package io.github.madeye.meow.ui.screens.yaml

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.ui.theme.meow

@Composable
fun YamlEditorScreen(
    initialText: String?,
    error: String?,
    dirty: Boolean,
    canRevert: Boolean,
    contentPadding: PaddingValues,
    onEdit: (String) -> Unit,
    onRequestSave: (String) -> Unit,
    onRequestRevert: () -> Unit,
    onNavigateBack: () -> Unit,
    handle: SoraEditorHandle,
    modifier: Modifier = Modifier,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmRevert by remember { mutableStateOf(false) }

    // Composes cleanly with predictive back: while clean, the handler is
    // disabled and the system animation runs as usual.
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        Box(modifier = Modifier.weight(1f).imePadding()) {
            if (initialText == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                SoraYamlEditor(
                    initialText = initialText,
                    handle = handle,
                    onContentChanged = { onEdit(handle.text()) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        StatusBar(error = error)
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.yaml_discard_changes)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onNavigateBack() }) {
                    Text(stringResource(R.string.common_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (confirmRevert) {
        AlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text(stringResource(R.string.yaml_revert_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmRevert = false; onRequestRevert() }) {
                    Text(stringResource(R.string.yaml_revert))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevert = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Toolbar actions, hoisted so the screen's scaffold can host them. */
@Composable
fun YamlEditorActions(
    dirty: Boolean,
    valid: Boolean,
    canRevert: Boolean,
    onRevert: () -> Unit,
    onSave: () -> Unit,
) {
    IconButton(onClick = onRevert, enabled = canRevert) {
        Icon(
            Icons.Filled.Restore,
            contentDescription = stringResource(R.string.yaml_revert),
        )
    }
    IconButton(onClick = onSave, enabled = dirty && valid) {
        Icon(
            Icons.Filled.Save,
            contentDescription = stringResource(R.string.common_save),
        )
    }
}

@Composable
private fun StatusBar(error: String?) {
    val colors = MaterialTheme.meow
    val valid = error == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (valid) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (valid) colors.connected else colors.danger,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = if (valid) {
                stringResource(R.string.yaml_valid)
            } else {
                stringResource(R.string.yaml_config_invalid, error.orEmpty())
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (valid) colors.connected else colors.danger,
        )
    }
}
