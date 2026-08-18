package io.github.madeye.meow.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "daily_traffic")
data class DailyTraffic(
    @PrimaryKey var date: String = "", // yyyy-MM-dd
    var tx: Long = 0,
    var rx: Long = 0,
)

@Dao
interface DailyTrafficDao {
    @Query("SELECT * FROM daily_traffic ORDER BY date ASC")
    fun getAll(): List<DailyTraffic>

    @Query("SELECT * FROM daily_traffic WHERE date >= :since ORDER BY date ASC")
    fun getSince(since: String): List<DailyTraffic>

    /** Observable variant driving the Traffic screen's charts. */
    @Query("SELECT * FROM daily_traffic WHERE date >= :since ORDER BY date ASC")
    fun observeSince(since: String): Flow<List<DailyTraffic>>

    @Query("SELECT * FROM daily_traffic WHERE date = :date")
    fun getByDate(date: String): DailyTraffic?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: DailyTraffic)

    @Query("DELETE FROM daily_traffic WHERE date < :before")
    fun deleteBefore(before: String)
}
