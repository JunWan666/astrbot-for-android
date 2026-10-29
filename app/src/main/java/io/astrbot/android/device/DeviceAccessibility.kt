package io.astrbot.android.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Optional backend for structured screen reading and Unicode text; all access stays gated by the broker. */
class DeviceAccessibility : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    suspend fun perform(operation: String, args: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        when (operation) {
            "tree" -> {
                val root = checkNotNull(rootInActiveWindow) { "No active screen" }
                check(root.refresh()) { "Screen changed; retry reading" }
                var count = 0
                var characters = 0
                fun visit(node: AccessibilityNodeInfo, depth: Int): JSONObject {
                    check(++count <= 1200 && depth <= 60) { "Screen tree exceeds size limit" }
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    fun text(value: CharSequence?): String {
                        val result = value?.toString()?.take(2000).orEmpty()
                        characters += result.length
                        check(characters <= 100000) { "Screen tree exceeds size limit" }
                        return result
                    }
                    val result = JSONObject().put("class", text(node.className)).put("package", text(node.packageName))
                        .put("text", if (node.isPassword) "[password]" else text(node.text))
                        .put("description", if (node.isPassword) "" else text(node.contentDescription))
                        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                        .put("editable", node.isEditable).put("focused", node.isFocused).put("clickable", node.isClickable)
                    val children = JSONArray()
                    for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                        try { if (child.isVisibleToUser) children.put(visit(child, depth + 1)) } finally { child.recycle() }
                    }
                    return result.put("children", children)
                }
                try { JSONObject().put("format", "json").put("tree", visit(root, 0)) } finally { root.recycle() }
            }
            "input" -> {
                val text = args.getString("text")
                require(text.length in 1..2000) { "Invalid text length" }
                val root = checkNotNull(rootInActiveWindow) { "No active screen" }
                check(root.refresh()) { "Screen changed; retry input" }
                val focused = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { root.recycle() }
                checkNotNull(focused) { "Focus an editable field first" }
                try {
                    check(focused.isEditable && !focused.isPassword) { "Editable non-password field required" }
                    val original = focused.text?.toString().orEmpty()
                    val start = focused.textSelectionStart.takeIf { it in 0..original.length } ?: original.length
                    val end = focused.textSelectionEnd.takeIf { it in start..original.length } ?: start
                    val inserted = original.substring(0, start) + text + original.substring(end)
                    check(inserted.length <= 10000) { "Field exceeds input limit" }
                    check(focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inserted)
                    })) { "App refused text input" }
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start + text.length)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start + text.length)
                    })
                    JSONObject().put("performed", true)
                } finally { focused.recycle() }
            }
            "tap", "swipe" -> {
                val path = Path().apply {
                    if (operation == "tap") moveTo(args.getInt("x").toFloat(), args.getInt("y").toFloat())
                    else { moveTo(args.getInt("x1").toFloat(), args.getInt("y1").toFloat()); lineTo(args.getInt("x2").toFloat(), args.getInt("y2").toFloat()) }
                }
                val duration = if (operation == "tap") 80L else args.getLong("duration")
                suspendCancellableCoroutine { continuation ->
                    val accepted = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(JSONObject().put("performed", true)) }
                            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Gesture cancelled")) }
                        }, null)
                    if (!accepted && continuation.isActive) continuation.resumeWithException(IllegalStateException("Gesture refused"))
                }
            }
            "key" -> {
                val key = when (args.getString("key")) { "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS; else -> error("This key requires Shizuku") }
                check(performGlobalAction(key)) { "Key was refused" }
                JSONObject().put("performed", true)
            }
            else -> error("This operation requires Shizuku")
        }
    }

    companion object { @Volatile internal var instance: DeviceAccessibility? = null }
}
