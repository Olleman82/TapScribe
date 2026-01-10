
package se.olle.rostbubbla.access

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

import se.olle.rostbubbla.debug.DebugLogger
import android.util.Log

class PasteAccessibilityService : AccessibilityService() {

  override fun onServiceConnected() {
    instance = this
    DebugLogger.log(this, "Accessibility", "Service Connected")
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    if (instance === this) instance = null
    DebugLogger.log(this, "Accessibility", "Service Unbound")
    return super.onUnbind(intent)
  }

  override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
      val eventType = event?.eventType
      if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED ||
          eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
          eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
          
          val tag = when(eventType) {
              android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED -> "View Focused"
              android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "Window Changed"
              android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "Content Changed"
              else -> "Event"
          }
          // Only log content changed if it's an important class to avoid spam
          if (eventType != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
              event?.className?.toString()?.contains("EditText") == true) {
              DebugLogger.log(this, "Accessibility", "$tag: ${event?.className}")
          }
      }
  }
  override fun onInterrupt() {}

  fun pasteText(text: String): Boolean {
    val root = rootInActiveWindow
    if (root == null) {
        DebugLogger.log(this, "Accessibility", "pasteText: rootInActiveWindow is null")
        return false
    }
    
    var focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    
    // Fallback: Manually search the tree for isFocused if findFocus failed
    if (focused == null) {
        DebugLogger.log(this, "Accessibility", "pasteText: findFocus(INPUT) failed, searching manually...")
        focused = findFocusedNode(root)
    }

    if (focused == null) {
        DebugLogger.log(this, "Accessibility", "pasteText: No input focus found even after manual search")
        return false
    }
    
    DebugLogger.log(this, "Accessibility", "pasteText: Target node class=${focused.className}, isEditable=${focused.isEditable}")
    
    // 1) Try ACTION_SET_TEXT
    val args = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    if (focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
      DebugLogger.log(this, "Accessibility", "pasteText: Attempting ACTION_SET_TEXT")
      if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
          DebugLogger.log(this, "Accessibility", "pasteText: ACTION_SET_TEXT success")
          return true
      }
    }
    
    // 2) Try ACTION_PASTE
    if (focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }) {
      DebugLogger.log(this, "Accessibility", "pasteText: Attempting ACTION_PASTE")
      val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
      cm.setPrimaryClip(android.content.ClipData.newPlainText("AI", text))
      if (focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
          DebugLogger.log(this, "Accessibility", "pasteText: ACTION_PASTE success")
          return true
      }
    }
    
    DebugLogger.log(this, "Accessibility", "pasteText: All methods failed")
    return false
  }

  private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.isFocused) return node
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val result = findFocusedNode(child)
      if (result != null) return result
    }
    return null
  }

  companion object {
    @Volatile var instance: PasteAccessibilityService? = null
  }
}
