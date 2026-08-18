package io.github.madeye.meow.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnKeyOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.ui.components.DelayBadge
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.components.SectionHeader
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters

@Composable
fun HomeScreen(
    state: HomeUiState,
    contentPadding: PaddingValues,
    onToggle: (Boolean) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onSelectNode: (String, String) -> Unit,
    onTestGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            // The e2e harness waits on this tag rather than on localized text.
            .testTag("home_root"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(state = state, onToggle = onToggle) }

        if (state.isConnected) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TrafficTile(
                        label = stringResource(R.string.home_upload),
                        icon = Icons.Filled.ArrowUpward,
                        rate = state.traffic.txRate,
                        total = state.traffic.txTotal,
                        tint = MaterialTheme.meow.upload,
                        modifier = Modifier.weight(1f),
                    )
                    TrafficTile(
                        label = stringResource(R.string.home_download),
                        icon = Icons.Filled.ArrowDownward,
                        rate = state.traffic.rxRate,
                        total = state.traffic.rxTotal,
                        tint = MaterialTheme.meow.download,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            SectionHeader(stringResource(R.string.proxy_groups_title))
        }

        if (state.groups.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        text = stringResource(
                            if (state.hasProfile) {
                                R.string.proxy_no_groups
                            } else {
                                R.string.home_no_subscription_hint
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.meow.mutedText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                }
            }
        } else {
            items(state.groups, key = { it.name }) { group ->
                ProxyGroupCard(
                    group = group,
                    expanded = state.expandedGroup == group.name,
                    testing = state.testingGroup == group.name,
                    onToggleExpanded = { onToggleExpanded(group.name) },
                    onSelectNode = { node -> onSelectNode(group.name, node) },
                    onTest = { onTestGroup(group.name) },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: HomeUiState, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.meow
    val label = stringResource(
        when (state.state) {
            BaseService.State.Connected -> R.string.home_connected
            BaseService.State.Connecting -> R.string.home_connecting
            BaseService.State.Stopping -> R.string.home_disconnecting
            BaseService.State.Stopped -> R.string.home_disconnected
            BaseService.State.Idle -> R.string.home_not_connected
        },
    )
    val tint = when (state.state) {
        BaseService.State.Connected -> colors.connected
        BaseService.State.Connecting, BaseService.State.Stopping -> colors.warning
        else -> colors.mutedText
    }

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(tint.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.isConnected) Icons.Filled.VpnKey else Icons.Filled.VpnKeyOff,
                    contentDescription = null,
                    tint = tint,
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = tint,
                )
                Text(
                    text = state.profileName.ifEmpty { stringResource(R.string.subs_none) },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                )
            }
            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                val toggleLabel = stringResource(R.string.home_vpn_toggle)
                Switch(
                    checked = state.isConnected,
                    onCheckedChange = onToggle,
                    enabled = state.hasProfile || state.isConnected,
                    modifier = Modifier
                        .testTag("vpn_switch")
                        .semantics { contentDescription = toggleLabel },
                )
            }
        }
    }
}

@Composable
private fun TrafficTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    rate: Long,
    total: Long,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.meow.mutedText,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = Formatters.rate(rate),
            style = MaterialTheme.typography.titleMedium.merge(MeowTextStyles.monoDigits),
        )
        Text(
            text = stringResource(R.string.traffic_total, Formatters.bytes(total)),
            style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
            color = MaterialTheme.meow.mutedText,
        )
    }
}

@Composable
private fun ProxyGroupCard(
    group: ProxyGroupUi,
    expanded: Boolean,
    testing: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectNode: (String) -> Unit,
    onTest: () -> Unit,
) {
    val colors = MaterialTheme.meow
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )

    GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = group.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${group.type} · ${group.now}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                )
            }
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onTest) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = stringResource(R.string.proxy_url_test_all),
                        tint = colors.accent,
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = colors.mutedText,
                modifier = Modifier.rotate(chevronRotation),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(color = colors.border)
                group.nodes.forEach { node ->
                    ProxyNodeRow(node = node, onClick = { onSelectNode(node.name) })
                }
            }
        }
    }
}

@Composable
private fun ProxyNodeRow(node: ProxyNodeUi, onClick: () -> Unit) {
    val colors = MaterialTheme.meow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (node.selected) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Filled.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = if (node.selected) colors.accent else colors.mutedText.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = node.name, style = MaterialTheme.typography.bodyMedium)
            if (node.type.isNotEmpty()) {
                Text(
                    text = node.type,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                )
            }
        }
        DelayBadge(delayMs = node.delayMs)
    }
}
