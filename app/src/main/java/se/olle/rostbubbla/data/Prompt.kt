
package se.olle.rostbubbla.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prompts")
data class Prompt(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val title: String,
  val systemText: String,
  val vehikel: String? = null,
  val useGoogleSearch: Boolean = false,
  val thinkingBudget: Int? = null,
  val thinkingEnabled: Boolean = false,
  val useOpenAI: Boolean = false
)
