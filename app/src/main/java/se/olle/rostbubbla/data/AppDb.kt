
package se.olle.rostbubbla.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Prompt::class], version = 4, exportSchema = false)
abstract class AppDb : RoomDatabase() {
  abstract fun promptDao(): PromptDao

  companion object {
    @Volatile private var INSTANCE: AppDb? = null
    fun get(ctx: Context): AppDb = INSTANCE ?: synchronized(this) {
      INSTANCE ?: Room.databaseBuilder(ctx, AppDb::class.java, "prompts.db")
        .addMigrations(object : Migration(1, 2) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE prompts ADD COLUMN useGoogleSearch INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE prompts ADD COLUMN thinkingBudget INTEGER")
          }
        }, object : Migration(2, 3) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE prompts ADD COLUMN thinkingEnabled INTEGER NOT NULL DEFAULT 0")
          }
        }, object : Migration(3, 4) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE prompts ADD COLUMN useOpenAI INTEGER NOT NULL DEFAULT 0")
          }
        })
        .build().also { INSTANCE = it }
    }
  }
}
