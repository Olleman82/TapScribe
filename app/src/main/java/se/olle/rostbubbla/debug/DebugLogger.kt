package se.olle.rostbubbla.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val TAG = "TapScribeDebug"
    private const val LOG_DIR = "debug_logs"
    private const val LOG_FILE = "app.log"
    private const val MAX_SIZE_BYTES = 5 * 1024 * 1024 // 5 MB

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Writes a log entry to file
    fun log(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        // Always log to Logcat for ADB debugging
        if (throwable != null) {
            Log.e(TAG, "[$tag] $message", throwable)
        } else {
            Log.d(TAG, "[$tag] $message")
        }

        // Only write to file if debug mode is enabled in settings
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("debug_mode", false)) {
            return
        }

        try {
            val dir = File(context.filesDir, LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, LOG_FILE)

            // Start with a heartbeat if file is new or empty
            if (!file.exists() || file.length() == 0L) {
                val vName = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "unknown" }
                val vCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (e: Exception) { -1 }
                file.appendText("--- LOG SESSION STARTED (Version: $vName, Code: $vCode) ---\n")
            }

            // Rotate if too big (simple delete and restart for now)
            if (file.exists() && file.length() > MAX_SIZE_BYTES) {
                file.delete()
                file.appendText("--- LOG ROTATED (MAX SIZE REACHED) ---\n")
            }

            val timestamp = dateFormat.format(Date())
            val errorStr = throwable?.let { "\nStacktrace: ${it.stackTraceToString()}" } ?: ""
            val entry = "$timestamp [$tag] $message$errorStr\n"
            
            file.appendText(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    fun getLogFile(context: Context): File? {
        val file = File(File(context.filesDir, LOG_DIR), LOG_FILE)
        return if (file.exists()) file else null
    }

    fun clearLogs(context: Context) {
        try {
            val file = File(File(context.filesDir, LOG_DIR), LOG_FILE)
            if (file.exists()) file.delete()
            log(context, "Debug", "LOGS CLEARED BY USER")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
}
