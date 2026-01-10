
package se.olle.rostbubbla.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Prompt::class, MemoryItem::class], version = 9, exportSchema = false)
abstract class AppDb : RoomDatabase() {
  abstract fun promptDao(): PromptDao
  abstract fun memoryDao(): MemoryDao

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
        }, object : Migration(4, 5) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE prompts ADD COLUMN isMailPrompt INTEGER NOT NULL DEFAULT 0")
          }
        }, object : Migration(5, 6) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE prompts ADD COLUMN useMemoryList INTEGER NOT NULL DEFAULT 0")
            db.execSQL("""
              CREATE TABLE memory_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
              )
            """.trimIndent())
          }
        }, object : Migration(6, 7) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE prompts ADD COLUMN sendWebhook INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE prompts ADD COLUMN webhookToken TEXT")
          }
        }, object : Migration(7, 8) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE prompts ADD COLUMN webhookRawOnly INTEGER NOT NULL DEFAULT 0")
          }
        }, object : Migration(8, 9) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE prompts ADD COLUMN webhookUrl TEXT")
          }
        })
        .build().also { INSTANCE = it }
    }
  }
}
