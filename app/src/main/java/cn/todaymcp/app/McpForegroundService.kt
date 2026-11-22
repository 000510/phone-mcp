package cn.todaymcp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** 前台服务：维持 MCP Server 进程存活 */
class McpForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channelId = "mcp_server"
        val nm = getSystemService(NotificationManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "MCP 服务", NotificationManager.IMPORTANCE_LOW)
                )
                startForeground(
                    1,
                    Notification.Builder(this, channelId)
                        .setContentTitle("手机MCP 运行中")
                        .setContentText("本机 127.0.0.1:8765/mcp 服务已开启")
                        .setSmallIcon(android.R.drawable.ic_menu_manage)
                        .build()
                )
            } else {
                startForeground(1, Notification.Builder(this).build())
            }
        } catch (e: Exception) {
            // 通知/前台化失败不阻止服务器启动
            android.util.Log.w("McpFGS", "startForeground failed", e)
        }
        McpServerHolder.start(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

object McpServerHolder {
    @Volatile
    private var server: McpServer? = null

    /** 最近一次启动失败的错误信息，供界面展示 */
    @Volatile
    var lastError: String? = null
        private set

    fun start(context: android.content.Context) {
        if (server != null) return
        synchronized(this) {
            if (server != null) return
            try {
                server = McpServer(8765).also { it.start(SOCKET_READ_TIMEOUT, false) }
                lastError = null
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                android.util.Log.e("McpServerHolder", "start failed", e)
            }
        }
    }
}
