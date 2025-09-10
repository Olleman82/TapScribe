
package se.olle.rostbubbla.data

import androidx.room.*

@Dao
interface PromptDao {
  @Query("SELECT * FROM prompts ORDER BY id DESC")
  suspend fun all(): List<Prompt>

  @Query("SELECT * FROM prompts WHERE title = :title LIMIT 1")
  suspend fun byTitle(title: String): Prompt?

  @Insert suspend fun insert(p: Prompt): Long
  @Update suspend fun update(p: Prompt)
  @Delete suspend fun delete(p: Prompt)
}
