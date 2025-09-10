
package se.olle.rostbubbla.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SpeechRepo(private val ctx: Context) {
  private var recognizer: SpeechRecognizer? = null

  private fun ensure() {
    if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
  }

  suspend fun listenOnce(language: String = "sv-SE"): String? {
    ensure()
    return suspendCancellableCoroutine { cont ->
      val r = recognizer!!
      var resumed = false
      var lastPartial: String? = null
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      }
      r.setRecognitionListener(object : RecognitionListener {
        override fun onResults(b: Bundle) {
          val list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          Log.d("SpeechRepo", "onResults: ${list?.joinToString()} lastPartial=$lastPartial")
          if (!resumed) {
            resumed = true
            val finalText = list?.firstOrNull()?.takeIf { !it.isNullOrBlank() } ?: lastPartial
            cont.resume(finalText)
          }
        }
        override fun onError(error: Int) {
          Log.w("SpeechRepo", "onError: $error lastPartial=$lastPartial")
          if (!resumed) {
            resumed = true
            if (error == SpeechRecognizer.ERROR_NO_MATCH && !lastPartial.isNullOrBlank()) {
              cont.resume(lastPartial)
            } else {
              cont.resume(null)
            }
          }
        }
        override fun onPartialResults(p0: Bundle) {
          val list = p0.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          if (!list.isNullOrEmpty()) {
            lastPartial = list.first()
            Log.d("SpeechRepo", "onPartial: $lastPartial")
          }
        }
        override fun onReadyForSpeech(p0: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(p0: Float) {}
        override fun onBufferReceived(p0: ByteArray?) {}
        override fun onEndOfSpeech() { r.stopListening() }
        override fun onEvent(p0: Int, p1: Bundle?) {}
      })
      r.startListening(intent)
      cont.invokeOnCancellation { r.cancel() }
    }
  }
}
