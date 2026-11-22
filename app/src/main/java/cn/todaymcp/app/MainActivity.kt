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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val btnStart = Button(this).apply { text = "2. 启动 MCP 服务" }
        btnStart.setOnClickListener { startMcp() }

        val btnTest = Button(this).apply { text = "3. 自检（查看服务状态）" }
        btnTest.setOnClickListener { refreshStatus(status) }

        val howto = TextView(this).apply {
            textSize = 13f
            text = """
                使用说明：
                ① 点上方按钮开启「手机MCP 无障碍服务」
                ② 回到本 App 点「启动 MCP 服务」
                ③ 打开 Today → 添加自定义 MCP → 链接填：
                   http://127.0.0.1:8765/mcp

                提示：部分客户端可能要求 https 地址或
                不允许本地回环，若连接失败属客户端限制。
            """.trimIndent()
            setPadding(0, pad, 0, 0)
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(btnA11y)
        layout.addView(btnStart)
        layout.addView(btnTest)
        layout.addView(howto)
        setContentView(layout)

        refreshStatus(status)
    }

    private fun startMcp() {
        val intent = Intent(this, McpForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
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
