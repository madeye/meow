package io.github.madeye.meow.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.database.DailyTraffic
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters

/**
 * Stacked daily upload/download bars with tap-to-select.
 *
 * [days] arrives gap-filled from the repository, so this only draws — it never
 * has to search for a missing date.
 */
@Composable
fun DailyTrafficChart(
    days: List<DailyTraffic>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.meow
    val measurer = rememberTextMeasurer()
    val labelStyle = MeowTextStyles.chartLabel.copy(color = colors.mutedText)
    val maxValue = remember(days) {
        maxOf(days.maxOfOrNull { it.tx + it.rx } ?: 0L, 1L)
    }
    val selected = selectedIndex?.let(days::getOrNull)

    Column(modifier = modifier) {
        // Fixed-height readout so selecting a bar doesn't shift the chart.
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected == null) {
                Text(
                    text = stringResource(R.string.traffic_tap_bar_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                )
            } else {
                Text(
                    text = selected.date,
                    style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                    color = colors.mutedText,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "↑ ${Formatters.bytes(selected.tx)}",
                    style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                    color = colors.upload,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "↓ ${Formatters.bytes(selected.rx)}",
                    style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                    color = colors.download,
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .pointerInput(days.size) {
                    detectTapGestures { position ->
                        val left = 44.dp.toPx()
                        if (position.x < left || days.isEmpty()) return@detectTapGestures
                        val index = (((position.x - left) / (size.width - left)) * days.size)
                            .toInt()
                            .coerceIn(0, days.lastIndex)
                        onSelect(if (index == selectedIndex) null else index)
                    }
                },
        ) {
            val left = 44.dp.toPx()
            val bottom = size.height - 14.dp.toPx()
            val plotWidth = size.width - left

            drawGrid(
                measurer = measurer,
                left = left,
                bottom = bottom,
                width = plotWidth,
                maxValue = maxValue,
                gridColor = colors.border.copy(alpha = 0.55f),
                labelStyle = labelStyle,
                format = { Formatters.bytesShort(it) },
            )

            if (days.isEmpty()) return@Canvas

            val slot = plotWidth / days.size
            val barWidth = slot * 0.7f
            val radius = CornerRadius(1.5.dp.toPx())

            days.forEachIndexed { index, day ->
                val slotLeft = left + slot * index
                val isSelected = index == selectedIndex
                if (isSelected) {
                    drawRect(
                        color = colors.accent.copy(alpha = 0.08f),
                        topLeft = Offset(slotLeft, 0f),
                        size = Size(slot, bottom),
                    )
                }

                val alpha = if (selectedIndex == null || isSelected) 1f else 0.55f
                val barLeft = slotLeft + (slot - barWidth) / 2f
                val rxHeight = (day.rx.toFloat() / maxValue) * bottom
                val txHeight = (day.tx.toFloat() / maxValue) * bottom

                // Download at the base, upload stacked on top — same order as iOS.
                if (rxHeight > 0f) {
                    drawRoundRect(
                        color = colors.download.copy(alpha = alpha),
                        topLeft = Offset(barLeft, bottom - rxHeight),
                        size = Size(barWidth, rxHeight),
                        cornerRadius = radius,
                    )
                }
                if (txHeight > 0f) {
                    drawRoundRect(
                        color = colors.upload.copy(alpha = alpha),
                        topLeft = Offset(barLeft, bottom - rxHeight - txHeight),
                        size = Size(barWidth, txHeight),
                        cornerRadius = radius,
                    )
                }

                val labelEvery = 5
                if (index % labelEvery == 0 || index == days.lastIndex || isSelected) {
                    val measured = measurer.measure(Formatters.dayLabel(day.date), labelStyle)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            x = slotLeft + (slot - measured.size.width) / 2f,
                            y = bottom + 2.dp.toPx(),
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        ChartLegend(uploadColor = colors.upload, downloadColor = colors.download)
    }
}
