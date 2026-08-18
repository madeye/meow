package io.github.madeye.meow.ui.screens.traffic

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.repo.TrafficTotals
import io.github.madeye.meow.ui.charts.DailyTrafficChart
import io.github.madeye.meow.ui.charts.SpeedChart
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.components.SectionHeader
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters

@Composable
fun TrafficScreen(
    state: TrafficUiState,
    contentPadding: PaddingValues,
    onSelectDay: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            )
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(stringResource(R.string.traffic_data_usage))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UsageCard(
                label = stringResource(R.string.traffic_today),
                totals = state.today,
                modifier = Modifier.weight(1f),
            )
            UsageCard(
                label = stringResource(R.string.traffic_this_month),
                totals = state.thisMonth,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(4.dp))
        SectionHeader(stringResource(R.string.traffic_daily_history))
        GlassCard {
            if (state.history.all { it.tx == 0L && it.rx == 0L }) {
                EmptyChart(stringResource(R.string.traffic_no_history))
            } else {
                DailyTrafficChart(
                    days = state.history,
                    selectedIndex = state.selectedDay,
                    onSelect = onSelectDay,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionHeader(stringResource(R.string.traffic_current_session))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SessionCard(
                label = stringResource(R.string.traffic_upload),
                icon = Icons.Filled.ArrowUpward,
                total = state.traffic.txTotal,
                rate = state.traffic.txRate,
                tint = MaterialTheme.meow.upload,
                modifier = Modifier.weight(1f),
            )
            SessionCard(
                label = stringResource(R.string.traffic_download),
                icon = Icons.Filled.ArrowDownward,
                total = state.traffic.rxTotal,
                rate = state.traffic.rxRate,
                tint = MaterialTheme.meow.download,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(4.dp))
        SectionHeader(stringResource(R.string.traffic_speed))
        GlassCard {
            when {
                !state.connected -> EmptyChart(stringResource(R.string.traffic_connect_to_see))
                state.samples.size < 2 -> EmptyChart(stringResource(R.string.traffic_collecting))
                else -> SpeedChart(samples = state.samples)
            }
        }
    }
}

@Composable
private fun UsageCard(label: String, totals: TrafficTotals, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.meow.mutedText,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = Formatters.bytes(totals.total),
            style = MaterialTheme.typography.titleMedium.merge(MeowTextStyles.monoDigits),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint = MaterialTheme.meow.upload,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = Formatters.bytes(totals.tx),
                style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                color = MaterialTheme.meow.mutedText,
            )
            Spacer(Modifier.size(8.dp))
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.meow.download,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = Formatters.bytes(totals.rx),
                style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                color = MaterialTheme.meow.mutedText,
            )
        }
    }
}

@Composable
private fun SessionCard(
    label: String,
    icon: ImageVector,
    total: Long,
    rate: Long,
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
            text = Formatters.bytes(total),
            style = MaterialTheme.typography.titleMedium.merge(MeowTextStyles.monoDigits),
        )
        Text(
            text = Formatters.rate(rate),
            style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
            color = MaterialTheme.meow.mutedText,
        )
    }
}

@Composable
private fun EmptyChart(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(140.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.meow.mutedText,
            textAlign = TextAlign.Center,
        )
    }
}
