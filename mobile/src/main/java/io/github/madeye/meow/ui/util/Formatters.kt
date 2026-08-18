package io.github.madeye.meow.ui.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Byte/rate/date formatting.
 *
 * Deliberately not string resources: the unit ladder is a formatting rule, not
 * a translation, and duplicating it per locale is how the two drift apart.
 */
object Formatters {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    /** e.g. `1.2 GB`, `340 KB`, `88 B`. */
    fun bytes(value: Long): String {
        var amount = abs(value).toDouble()
        var unit = 0
        while (amount >= 1024 && unit < UNITS.lastIndex) {
            amount /= 1024
            unit++
        }
        val digits = if (unit == 0 || amount >= 100) 0 else 1
        return String.format(Locale.US, "%.${digits}f %s", amount, UNITS[unit])
    }

    /** e.g. `1.2 MB/s`. */
    fun rate(bytesPerSecond: Long): String = "${bytes(bytesPerSecond)}/s"

    /**
     * Compact form for chart axes, where horizontal space is scarce:
     * `1.2G`, `340M`, `12K`, `88`.
     */
    fun bytesShort(value: Long): String {
        var amount = abs(value).toDouble()
        var unit = 0
        while (amount >= 1024 && unit < UNITS.lastIndex) {
            amount /= 1024
            unit++
        }
        val suffix = when (unit) {
            0 -> ""
            1 -> "K"
            2 -> "M"
            3 -> "G"
            else -> "T"
        }
        val digits = if (unit == 0 || amount >= 10) 0 else 1
        return String.format(Locale.US, "%.${digits}f%s", amount, suffix)
    }

    private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    /** `2026-08-17` -> `08-17`, for the daily chart's x axis. */
    fun dayLabel(isoDate: String): String =
        runCatching { LocalDate.parse(isoDate).format(DAY_LABEL) }.getOrDefault(isoDate)

    /** Absolute local timestamp for "Updated …" rows. */
    fun timestamp(epochMillis: Long): String =
        if (epochMillis <= 0) "" else TIMESTAMP.format(Instant.ofEpochMilli(epochMillis))

    /** Elapsed wall-clock since an ISO-8601 instant, as `1h 04m` / `12s`. */
    fun elapsedSince(iso: String, now: Instant = Instant.now()): String {
        val start = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
        val seconds = (now.epochSecond - start.epochSecond).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${String.format(Locale.US, "%02d", seconds % 60)}s"
            else -> "${seconds / 3600}h ${String.format(Locale.US, "%02d", (seconds % 3600) / 60)}m"
        }
    }
}
