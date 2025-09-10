package se.olle.rostbubbla.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import se.olle.rostbubbla.ACTIONS

class SpeechUiActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val lang = intent.getStringExtra("lang") ?: "sv-SE"
    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }
    try {
      startActivityForResult(i, 1001)
    } catch (_: Throwable) {
      finish()
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == 1001) {
      val text = if (resultCode == Activity.RESULT_OK) {
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
      } else null
      val b = Intent(ACTIONS.ACTION_STT_RESULT).apply {
        putExtra(ACTIONS.EXTRA_STT_TEXT, text)
        `package` = packageName
      }
      sendBroadcast(b)
      finish()
    }
  }
}


