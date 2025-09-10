
package se.olle.rostbubbla.access

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

class PasteAccessibilityService : AccessibilityService() {

  override fun onServiceConnected() {
    instance = this
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    if (instance === this) instance = null
    return super.onUnbind(intent)
  }

  override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
  override fun onInterrupt() {}

  fun pasteText(text: String): Boolean {
    val root = rootInActiveWindow ?: return false
    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
    // 1) Försök ACTION_SET_TEXT
    val args = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    if (focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
      if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
    }
    // 2) Försök ACTION_PASTE
    if (focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }) {
      // Lägg i urklipp och kör paste
      val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
      cm.setPrimaryClip(android.content.ClipData.newPlainText("AI", text))
      if (focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
    }
    return false
  }

  companion object {
    @Volatile var instance: PasteAccessibilityService? = null
  }
}
