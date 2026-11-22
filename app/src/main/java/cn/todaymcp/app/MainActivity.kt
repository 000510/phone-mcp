package cn.todaymcp.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 主界面：显示服务状态，引导开启无障碍权限并启动 MCP 服务。
 */
class MainActivity : android.app.Activity() {

    companion object {
        const val LOG_FILE = "crash_log.txt"
        var lastCrash: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全局崩溃捕获：把崩溃原因记录下来，方便在手机上直接查看
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val log = buildString {
                append("=== 崩溃时间 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())} ===\n")
                append(e.javaClass.name).append(": ").append(e.message).append("\n")
                e.stackTrace.take(25).forEach { append(it.toString()).append("\n") }
            }
            lastCrash = log
            try {
                openFileOutput(LOG_FILE, MODE_APPEND).use { it.write(log.toByteArray()) }
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(t, e)
        }

        // 请求通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "手机MCP（本地无障碍服务）"
            textSize = 20f
        }
        val status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, pad)
        }

        val btnA11y = Button(this).apply { text = "1. 开启无障碍服务" }
        btnA11y.setOnClickListener {
            safe({ startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, "打开设置失败")
        }

        val btnStart = Button(this).apply { text = "2. 启动 MCP 服务" }
        btnStart.setOnClickListener { startMcp() }

        val btnTest = Button(this).apply { text = "3. 自检（查看服务状态）" }
        btnTest.setOnClickListener { refreshStatus(status) }

        val btnLog = Button(this).apply { text = "4. 查看崩溃日志（闪退后看这个）" }
        btnLog.setOnClickListener { showLog() }

        val howto = TextView(this).apply {
            textSize = 13f
            text = """
                使用说明：
                ① 点上方按钮开启「手机MCP 无障碍服务」
                ② 回到本 App 点「启动 MCP 服务」
                ③ 打开 Today → 添加自定义 MCP → 链接填：
                   http://127.0.0.1:8765/mcp

                如果闪退，重新打开 App 点「查看崩溃日志」，
                把日志内容发给 AI。
            """.trimIndent()
            setPadding(0, pad, 0, 0)
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(btnA11y)
        layout.addView(btnStart)
        layout.addView(btnTest)
        layout.addView(btnLog)
        layout.addView(howto)
        setContentView(layout)

        refreshStatus(status)
    }

    private fun safe(block: () -> Unit, fallback: String) {
        try {
            block()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "$fallback: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun startMcp() {
        try {
            val intent = Intent(this, McpForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            android.widget.Toast.makeText(this, "服务已启动，点自检确认", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "启动失败: ${e.javaClass.simpleName}: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showLog() {
        val text = lastCrash ?: try {
            openFileInput(LOG_FILE).bufferedReader().use { it.readText() }.takeLast(3000)
        } catch (_: Exception) {
            "暂无崩溃记录"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("崩溃日志")
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun refreshStatus(status: TextView) {
        val a11y = CtlAccessibilityService.instance != null
        Thread {
            var http = false
            try {
                val conn = java.net.URL("http://127.0.0.1:8765/mcp").openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1000
                http = conn.responseCode == 200
                conn.disconnect()
            } catch (_: Exception) {}
            runOnUiThread {
                status.text = buildString {
                    append(if (a11y) "✅ 无障碍服务：已开启" else "❌ 无障碍服务：未开启")
                    append("\n")
                    append(if (http) "✅ MCP 服务：http://127.0.0.1:8765/mcp 运行中" else "❌ MCP 服务：未运行（点下面按钮启动）")
                }
            }
        }.start()
    }
}
