
package se.olle.rostbubbla.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Prompt::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
  abstract fun promptDao(): PromptDao

  companion object {
    @Volatile private var INSTANCE: AppDb? = null
    fun get(ctx: Context): AppDb = INSTANCE ?: synchronized(this) {
      INSTANCE ?: Room.databaseBuilder(ctx, AppDb::class.java, "prompts.db").build().also { INSTANCE = it }
    }
  }
}
