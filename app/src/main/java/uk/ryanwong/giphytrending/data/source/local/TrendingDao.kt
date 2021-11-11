package uk.ryanwong.giphytrending.data.source.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.reactivex.Single

@Dao
interface TrendingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertData(data: TrendingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllData(data: List<TrendingEntity>)

    @Query("SELECT * FROM trending ORDER BY import_datetime DESC")
    fun queryData(): Single<List<TrendingEntity>>

    @Query("DELETE FROM trending")
    fun clear()

    // SQLite does not have a boolean data type.
    // Room maps it to an INTEGER column, mapping true to 1 and false to 0.
    @Query("UPDATE trending SET dirty = 1")
    fun markDirty()

    @Query("DELETE FROM trending WHERE dirty = 1")
    fun deleteDirty()
}