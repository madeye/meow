package io.github.madeye.meow.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters
import io.github.madeye.meow.vpn.SpeedSample

/**
 * Live transfer-rate chart: two filled line series over the last N samples.
 *
 * Hand-drawn rather than pulled from a chart library — the Dart original was a
 * `CustomPainter` for the same reason, and the geometry, byte-formatted axis and
 * brand palette would all have to be bent back into shape anyway.
 */
@Composable
fun SpeedChart(
    samples: List<SpeedSample>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.meow
    val measurer = rememberTextMeasurer()
    val labelStyle = MeowTextStyles.chartLabel.copy(color = colors.mutedText)

    val maxRate = remember(samples) {
        maxOf(
            samples.maxOfOrNull { maxOf(it.txRate, it.rxRate) } ?: 0L,
            MIN_SCALE,
        )
    }

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val left = 44.dp.toPx()
            val bottom = size.height - 4.dp.toPx()
            val plotWidth = size.width - left
            val plotHeight = bottom

            drawGrid(
                measurer = measurer,
                left = left,
                bottom = bottom,
                width = plotWidth,
                maxValue = maxRate,
                gridColor = colors.border.copy(alpha = 0.55f),
                labelStyle = labelStyle,
                format = { Formatters.bytesShort(it) + "/s" },
            )

            if (samples.size < 2) return@Canvas

            // Download beneath upload: the orange upload line stays legible
            // against the blue fill rather than the other way round.
            drawSeries(samples, left, plotWidth, plotHeight, maxRate, colors.download) { it.rxRate }
            drawSeries(samples, left, plotWidth, plotHeight, maxRate, colors.upload) { it.txRate }
        }

        Spacer(Modifier.height(8.dp))
        ChartLegend(
            uploadColor = colors.upload,
            downloadColor = colors.download,
        )
    }
}

private fun DrawScope.drawSeries(
    samples: List<SpeedSample>,
    left: Float,
    width: Float,
    height: Float,
    maxValue: Long,
    color: Color,
    selector: (SpeedSample) -> Long,
) {
    val step = width / (samples.size - 1).coerceAtLeast(1)
    fun pointAt(index: Int): Offset {
        val value = selector(samples[index]).coerceAtLeast(0L)
        val y = height - (value.toFloat() / maxValue.toFloat()) * height
        return Offset(left + step * index, y)
    }

    val line = Path().apply {
        moveTo(pointAt(0).x, pointAt(0).y)
        for (index in 1 until samples.size) {
            val point = pointAt(index)
            lineTo(point.x, point.y)
        }
    }
    val fill = Path().apply {
        addPath(line)
        lineTo(left + width, height)
        lineTo(left, height)
        close()
    }

    drawPath(
        path = fill,
        brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)),
    )
    drawPath(
        path = line,
        color = color,
        style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round),
    )
}

internal fun DrawScope.drawGrid(
    measurer: TextMeasurer,
    left: Float,
    bottom: Float,
    width: Float,
    maxValue: Long,
    gridColor: Color,
    labelStyle: androidx.compose.ui.text.TextStyle,
    format: (Long) -> String,
    lines: Int = 4,
) {
    for (index in 0..lines) {
        val fraction = index.toFloat() / lines
        val y = bottom - bottom * fraction
        drawLine(
            color = gridColor,
            start = Offset(left, y),
            end = Offset(left + width, y),
            strokeWidth = 1f,
        )
        val label = format((maxValue * fraction).toLong())
        val measured = measurer.measure(label, labelStyle)
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                x = left - measured.size.width - 4.dp.toPx(),
                y = y - measured.size.height / 2f,
            ),
        )
    }
}

@Composable
internal fun ChartLegend(
    uploadColor: Color,
    downloadColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        LegendEntry(uploadColor, androidx.compose.ui.res.stringResource(io.github.madeye.meow.R.string.traffic_upload))
        Spacer(Modifier.size(16.dp))
        LegendEntry(downloadColor, androidx.compose.ui.res.stringResource(io.github.madeye.meow.R.string.traffic_download))
    }
}

@Composable
private fun LegendEntry(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.meow.mutedText,
        )
    }
}

private const val MIN_SCALE = 1024L
