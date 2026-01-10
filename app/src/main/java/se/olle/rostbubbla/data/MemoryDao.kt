package se.olle.rostbubbla.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_items ORDER BY createdAt DESC")
    fun getAllMemoryItems(): Flow<List<MemoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoryItem(memoryItem: MemoryItem): Long

    @Delete
    suspend fun deleteMemoryItem(memoryItem: MemoryItem)

    @Query("SELECT * FROM memory_items WHERE id = :id")
    suspend fun getMemoryItemById(id: Long): MemoryItem?
}



