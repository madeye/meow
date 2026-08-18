package io.github.madeye.meow.repo

import io.github.madeye.meow.database.DailyTraffic
import io.github.madeye.meow.database.PrivateDatabase
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class TrafficTotals(val tx: Long, val rx: Long) {
    val total: Long get() = tx + rx

    companion object {
        val Zero = TrafficTotals(0, 0)
    }
}

/**
 * Daily upload/download history behind the Traffic screen.
 *
 * Days with no traffic have no row, so the chart series is gap-filled here
 * rather than in the painter — the chart should receive exactly [WINDOW_DAYS]
 * points and not have to search for them.
 */
class TrafficHistoryRepository {

    private val dao get() = PrivateDatabase.dailyTrafficDao

    /** Gap-filled series, oldest first, ending today. */
    fun observeWindow(days: Int = WINDOW_DAYS): Flow<List<DailyTraffic>> {
        val start = LocalDate.now().minusDays((days - 1).toLong())
        return dao.observeSince(start.format(FORMATTER)).map { rows -> fill(rows, start, days) }
    }

    suspend fun today(): TrafficTotals = withContext(Dispatchers.IO) {
        dao.getByDate(LocalDate.now().format(FORMATTER)).toTotals()
    }

    suspend fun thisMonth(): TrafficTotals = withContext(Dispatchers.IO) {
        val firstOfMonth = LocalDate.now().withDayOfMonth(1).format(FORMATTER)
        dao.getSince(firstOfMonth).fold(TrafficTotals.Zero) { acc, row ->
            TrafficTotals(acc.tx + row.tx, acc.rx + row.rx)
        }
    }

    /** Drops rows older than the retention window; previously done ad hoc on read. */
    suspend fun prune(keepDays: Long = 31) = withContext(Dispatchers.IO) {
        dao.deleteBefore(LocalDate.now().minusDays(keepDays).format(FORMATTER))
    }

    private fun fill(rows: List<DailyTraffic>, start: LocalDate, days: Int): List<DailyTraffic> {
        val byDate = rows.associateBy { it.date }
        return (0 until days).map { offset ->
            val key = start.plusDays(offset.toLong()).format(FORMATTER)
            byDate[key] ?: DailyTraffic(date = key)
        }
    }

    private fun DailyTraffic?.toTotals() =
        if (this == null) TrafficTotals.Zero else TrafficTotals(tx, rx)

    companion object {
        const val WINDOW_DAYS = 30
        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
