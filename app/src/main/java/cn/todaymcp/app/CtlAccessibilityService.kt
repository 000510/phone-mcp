package cn.todaymcp.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 无障碍服务：所有「看屏幕 / 摸屏幕」能力的唯一来源。
 * McpServer 通过这里暴露的静态方法调用它们。
 */
class CtlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CtlAccessibilityService? = null
            private set

        val mainHandler = Handler(Looper.getMainLooper())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // 不需要处理事件，只需保持连接以获取能力
    }

    override fun onInterrupt() {}

    // ---------- 工具方法 ----------

    /** 截屏，返回 PNG 的 base64 */
    fun takeScreenshotBase64(): String {
        val latch = CountDownLatch(1)
        var result: String? = null
        mainHandler.post {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val buffer = screenshot.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(buffer, null)
                            if (bmp == null) {
                                result = ""
                            } else {
                                val soft = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                bmp.recycle()
                                val bos = ByteArrayOutputStream()
                                soft.compress(Bitmap.CompressFormat.PNG, 80, bos)
                                soft.recycle()
                                result = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                            }
                            buffer.close()
                            screenshot.recycle()
                        } catch (e: Exception) {
                            result = ""
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        result = ""
                        latch.countDown()
                    }
                })
        }
        latch.await(10, TimeUnit.SECONDS)
        return result ?: ""
    }

    /** 点击坐标，返回是否成功 */
    fun tap(x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var ok = false
        mainHandler.post {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    ok = true; latch.countDown()
                }

                override fun onCancelled(g: GestureDescription?) {
                    ok = false; latch.countDown()
                }
            }, null)
        }
        latch.await(5, TimeUnit.SECONDS)
        return ok
    }

    /** 滑动 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var ok = false
        mainHandler.post {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    ok = true; latch.countDown()
                }

                override fun onCancelled(g: GestureDescription?) {
                    ok = false; latch.countDown()
                }
            }, null)
        }
        latch.await(durationMs / 1000 + 5, TimeUnit.SECONDS)
        return ok
    }

    /** 长按 */
    fun longPress(x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(x, y); lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 800)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var ok = false
        mainHandler.post {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { ok = false; latch.countDown() }
            }, null)
        }
        latch.await(6, TimeUnit.SECONDS)
        return ok
    }

    /** 向当前聚焦的输入框输入文字（优先 SET_TEXT，失败则尝试粘贴板） */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findFocusNode(root) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 按系统按键：back / home / recents / enter / delete */
    fun pressKey(key: String): Boolean {
        val keyCode = when (key.lowercase()) {
            "back" -> KeyEvent.KEYCODE_BACK
            "home" -> KeyEvent.KEYCODE_HOME
            "recents", "multitask" -> KeyEvent.KEYCODE_APP_SWITCH
            "enter" -> KeyEvent.KEYCODE_ENTER
            "delete", "del", "backspace" -> KeyEvent.KEYCODE_DEL
            "volume_up" -> KeyEvent.KEYCODE_VOLUME_UP
            "volume_down" -> KeyEvent.KEYCODE_VOLUME_DOWN
            "power" -> KeyEvent.KEYCODE_POWER
            else -> return false
        }
        return performGlobalAction(keyCodeToGlobal(keyCode))
    }

    private fun keyCodeToGlobal(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME -> GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> GLOBAL_ACTION_RECENTS
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER -> GLOBAL_ACTION_BACK // 音量/电源不能全局模拟，回退
            else -> GLOBAL_ACTION_BACK
        }
    }

    /** 打开通知栏 / 快捷设置 */
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings() = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    /** 输出当前界面的可交互元素树（JSON 字符串），供 AI 了解屏幕结构 */
    fun dumpUi(maxDepth: Int = 18): String {
        val root = rootInActiveWindow ?: return "[]"
        val sb = StringBuilder("[")
        dumpNode(root, 0, maxDepth, sb)
        if (sb.length > 1) sb.setLength(sb.length - 1)
        sb.append("]")
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int, sb: StringBuilder) {
        if (depth > maxDepth || sb.length > 200_000) return
        val text = node.text?.toString()?.take(60)
        val desc = node.contentDescription?.toString()?.take(60)
        val viewId = node.viewIdResourceName
        val clickable = node.isClickable
        val r = Rect()
        node.getBoundsInScreen(r)
        if (text != null || desc != null || viewId != null || clickable) {
            sb.append("{")
            if (text != null) sb.append("\"text\":\"").append(escape(text)).append("\",")
            if (desc != null) sb.append("\"desc\":\"").append(escape(desc)).append("\",")
            if (viewId != null) sb.append("\"id\":\"").append(escape(viewId)).append("\",")
            sb.append("\"clickable\":$clickable,")
            sb.append("\"bounds\":[${r.left},${r.top},${r.right},${r.bottom}]},")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, depth + 1, maxDepth, sb) }
        }
    }

    private fun escape(s: String) =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", "")

    private fun findFocusNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && focus.isEditable) return focus
        // 兜底：找第一个可编辑节点
        return findEditable(root)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findEditable(it)?.let { return it } }
        }
        return null
    }

    /** 屏幕尺寸 */
    fun screenInfo(): String {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return """{"width":${m.widthPixels},"height":${m.heightPixels},"density":${(m.densityDpi / 160.0).roundToInt()}}"""
    }
}
