package org.fcitx.fcitx5.android.plugin.quicksend.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface QuickSendDao {

    /** 全量加载，按使用次数倒序，其次按更新时间倒序。无分页（上限 500 条）。 */
    @Query("SELECT * FROM quicksend ORDER BY useCount DESC, updatedAt DESC")
    suspend fun allEntries(): List<QuickSendEntry>

    @Query("SELECT * FROM quicksend WHERE id = :id")
    suspend fun get(id: Long): QuickSendEntry?

    @Insert
    suspend fun insert(entry: QuickSendEntry): Long

    @Update
    suspend fun update(entry: QuickSendEntry)

    @Query("UPDATE quicksend SET useCount = useCount + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementUseCount(id: Long, updatedAt: Long)

    @Query("UPDATE quicksend SET useCount = :count, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateUseCount(id: Long, count: Int, updatedAt: Long)

    @Delete
    suspend fun delete(entry: QuickSendEntry)

    @Query("SELECT COUNT(*) FROM quicksend")
    suspend fun count(): Int
}
