package io.github.madeye.meow.ui.screens.perapp

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.madeye.meow.R
import io.github.madeye.meow.repo.InstalledApp
import io.github.madeye.meow.repo.PerAppMode
import io.github.madeye.meow.ui.theme.meow

@Composable
fun PerAppProxyScreen(
    state: PerAppUiState,
    contentPadding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onToggleSystemApps: () -> Unit,
    onModeChange: (PerAppMode) -> Unit,
    onToggleApp: (String) -> Unit,
    iconLoader: suspend (String) -> android.graphics.drawable.Drawable?,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val visible = state.visibleApps

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PerAppMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { onModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, PerAppMode.entries.size),
                    ) {
                        Text(
                            stringResource(
                                when (mode) {
                                    PerAppMode.Proxy -> R.string.perapp_mode_proxy
                                    PerAppMode.Bypass -> R.string.perapp_mode_bypass
                                },
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.selected.isEmpty()) {
                    stringResource(R.string.perapp_disabled_hint)
                } else {
                    pluralStringResource(
                        R.plurals.perapp_selected,
                        state.selected.size,
                        state.selected.size,
                    ) + " · " + stringResource(R.string.perapp_restart_required)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.meow.mutedText,
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.perapp_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            FilterChip(
                selected = state.showSystemApps,
                onClick = onToggleSystemApps,
                label = { Text(stringResource(R.string.perapp_show_system)) },
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            items(visible, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    checked = app.packageName in state.selected,
                    onToggle = { onToggleApp(app.packageName) },
                    iconLoader = iconLoader,
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onToggle: () -> Unit,
    iconLoader: suspend (String) -> android.graphics.drawable.Drawable?,
) {
    // Loaded per row: resolving every installed app's icon up front costs
    // hundreds of PackageManager round trips.
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName) {
        value = iconLoader(app.packageName)?.let { drawable ->
            runCatching { drawable.toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(36.dp))
        } else {
            Icon(
                Icons.Filled.Android,
                contentDescription = null,
                tint = MaterialTheme.meow.mutedText.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.meow.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

private const val ICON_PX = 96
