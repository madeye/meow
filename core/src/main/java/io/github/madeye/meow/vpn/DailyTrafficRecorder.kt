package io.github.madeye.meow.vpn

import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.database.DailyTraffic
import io.github.madeye.meow.database.PrivateDatabase
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Accumulates per-day upload/download totals from the 1 Hz traffic callback.
 *
 * Lifted out of `MainActivity.trafficUpdated`, where it ran a Room write on the
 * main thread once a second. The accounting itself is deliberately unchanged:
 * counters are cumulative per session, so the first sample after a connect
 * contributes nothing and only subsequent deltas are recorded.
 *
 * The `yyyy-MM-dd` key format is shared with the iOS app's `DailyTraffic`.
 */
class DailyTrafficRecorder {

    private var lastTx = 0L
    private var lastRx = 0L

    /** Called when a new session starts; the next sample re-baselines. */
    fun reset() {
        lastTx = 0L
        lastRx = 0L
    }

    fun record(stats: TrafficStats, today: String = todayKey()) {
        val deltaTx = if (lastTx > 0) stats.txTotal - lastTx else 0L
        val deltaRx = if (lastRx > 0) stats.rxTotal - lastRx else 0L
        lastTx = stats.txTotal
        lastRx = stats.rxTotal

        if (deltaTx <= 0 && deltaRx <= 0) return

        val dao = PrivateDatabase.dailyTrafficDao
        val entry = dao.getByDate(today) ?: DailyTraffic(date = today)
        if (deltaTx > 0) entry.tx += deltaTx
        if (deltaRx > 0) entry.rx += deltaRx
        dao.upsert(entry)
    }

    companion object {
        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun todayKey(): String = LocalDate.now().format(FORMATTER)
    }
}
