package com.cloudyte.app.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExclusionDao {
    @Query("SELECT * FROM exclusions ORDER BY id ASC")
    fun observeAll(): Flow<List<LocalExclusion>>

    @Query("SELECT * FROM exclusions WHERE enabled = 1")
    suspend fun getEnabledExclusions(): List<LocalExclusion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exclusion: LocalExclusion): Long

    @Update
    suspend fun update(exclusion: LocalExclusion)

    @Delete
    suspend fun delete(exclusion: LocalExclusion)

    @Query("DELETE FROM exclusions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
