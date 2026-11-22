package cn.todaymcp.app

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 MCP Server。
 * MCP 协议（Streamable HTTP，简化实现）：接受 JSON-RPC POST 请求。
 * 监听 127.0.0.1:8765/mcp —— Today 的「通过链接添加」填
 *   http://127.0.0.1:8765/mcp
 */
class McpServer(port: Int) : NanoHTTPD("127.0.0.1", port) {

    private val serverInfo = JSONObject()
        .put("name", "phone-mcp")
        .put("version", "0.1.0")

    private val tools: List<JSONObject> = listOf(
        toolDef("screen_screenshot", "截取当前手机屏幕，返回 PNG 的 base64 图像"),
        toolDef("ui_dump",
            "获取当前屏幕的可交互元素列表（文本、控件ID、坐标），用于了解界面结构。建议先调用这个再点击。",
            arg("elements_max", "number", "可选，限制返回元素数量，默认不限制")),
        toolDef("screen_info", "获取屏幕尺寸与密度"),
        toolDef("click", "点击屏幕指定坐标",
            arg("x", "number", "X 坐标（像素）"),
            arg("y", "number", "Y 坐标（像素）")),
        toolDef("long_press", "长按屏幕指定坐标",
            arg("x", "number", "X 坐标"),
            arg("y", "number", "Y 坐标")),
        toolDef("swipe", "从 (x1,y1) 滑动到 (x2,y2)",
            arg("x1", "number", "起点X"), arg("y1", "number", "起点Y"),
            arg("x2", "number", "终点X"), arg("y2", "number", "终点Y"),
            arg("duration_ms", "number", "可选，滑动时长毫秒，默认300")),
        toolDef("input_text", "向当前聚焦的输入框输入文字",
            arg("text", "string", "要输入的内容")),
        toolDef("press_key", "按系统按键",
            arg("key", "string", "back / home / recents / enter / delete")),
        toolDef("open_notifications", "下拉打开通知栏"),
        toolDef("open_quick_settings", "打开快捷设置面板"),
        toolDef("list_apps", "列出手机上已安装的应用（包名和名称）"),
        toolDef("start_app", "启动指定应用",
            arg("package_name", "string", "应用包名，如 com.tencent.mm")),
        toolDef("current_app", "获取当前前台应用的包名")
    )

    // ---------- MCP 工具描述构造 ----------

    private fun toolDef(name: String, desc: String, vararg props: JSONObject): JSONObject {
        val schemaProps = JSONObject()
        val required = JSONArray()
        props.forEach {
            schemaProps.put(it.getString("name"), JSONObject()
                .put("type", it.getString("type"))
                .put("description", it.optString("description", "")))
            if (it.optBoolean("required", true)) required.put(it.getString("name"))
        }
        val schema = JSONObject().put("type", "object")
        if (props.isNotEmpty()) {
            schema.put("properties", schemaProps)
            schema.put("required", required)
        }
        return JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("inputSchema", schema)
    }

    private fun arg(name: String, type: String, desc: String): JSONObject =
        JSONObject().put("name", name).put("type", type).put("description", desc)

    // ---------- HTTP / JSON-RPC ----------

    override fun serve(session: IHTTPSession): Response {
        // CORS + 预检，方便某些客户端探测
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
                addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id")
            }
        }
        if (session.method == Method.GET) {
            // 信息页
            val info = JSONObject()
                .put("service", "phone-mcp")
                .put("endpoint", "/mcp")
                .put("status", CtlAccessibilityService.instance != null)
            return newFixedLengthResponse(Response.Status.OK, "application/json", info.toString())
        }
        if (session.uri != "/mcp") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                JSONObject().put("error", "use /mcp").toString())
        }

        // 读取 body
        val map = HashMap<String, String>()
        session.parseBody(map)
        val body = map["postData"] ?: "{}"

        return try {
            val req = JSONObject(body)
            val id = req.opt("id")
            val method = req.optString("method", "")
            val result = handleRpc(method, req.optJSONObject("params"))
            val resp = JSONObject().put("jsonrpc", "2.0").put("result", result)
            if (id != null && id != JSONObject.NULL) resp.put("id", id)
            newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
        } catch (e: Exception) {
            val err = newFixedLengthResponse(Response.Status.OK, "application/json",
                JSONObject().put("jsonrpc", "2.0")
                    .putOpt("id", JSONObject.NULL)
                    .put("error", JSONObject()
                        .put("code", -32603)
                        .put("message", e.message ?: "internal error"))
                    .toString())
            err
        }
    }

    private fun handleRpc(method: String, params: JSONObject?): JSONObject {
        return when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("serverInfo", serverInfo)

            "notifications/initialized", "ping" -> JSONObject()

            "tools/list" -> JSONObject()
                .put("tools", JSONArray(tools))

            "tools/call" -> callTool(
                params?.optString("name") ?: "",
                params?.optJSONObject("arguments") ?: JSONObject())

            "resources/list", "prompts/list" ->
                JSONObject().put("resources", JSONArray()).put("prompts", JSONArray())

            else -> throw Exception("unknown method: $method")
        }
    }

    // ---------- 工具实现 ----------

    private fun callTool(name: String, args: JSONObject): JSONObject {
        val svc = CtlAccessibilityService.instance
            ?: return textResult("错误：无障碍服务未开启。请在系统设置 → 无障碍 中开启「手机MCP 无障碍服务」后重试。")

        return try {
            when (name) {
                "screen_screenshot" -> imageResult(svc.takeScreenshotBase64())
                "ui_dump" -> textResult(svc.dumpUi())
                "screen_info" -> textResult(svc.screenInfo())
                "click" -> boolResult(svc.tap(args.getDouble("x").toFloat(), args.getDouble("y").toFloat()))
                "long_press" -> boolResult(svc.longPress(args.getDouble("x").toFloat(), args.getDouble("y").toFloat()))
                "swipe" -> boolResult(svc.swipe(
                    args.getDouble("x1").toFloat(), args.getDouble("y1").toFloat(),
                    args.getDouble("x2").toFloat(), args.getDouble("y2").toFloat(),
                    args.optLong("duration_ms", 300)))
                "input_text" -> boolResult(svc.inputText(args.getString("text")))
                "press_key" -> boolResult(svc.pressKey(args.getString("key")))
                "open_notifications" -> boolResult(svc.openNotifications())
                "open_quick_settings" -> boolResult(svc.openQuickSettings())
                "list_apps" -> listApps(svc)
                "start_app" -> startApp(svc, args.getString("package_name"))
                "current_app" -> currentApp(svc)
                else -> textResult("未知工具：$name")
            }
        } catch (e: Exception) {
            textResult("执行出错：${e.message}")
        }
    }

    private fun textResult(s: String) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", s)))
        .put("isError", false)

    private fun boolResult(ok: Boolean) =
        if (ok) textResult("成功") else textResult("失败（手势可能被取消或权限不足）")

    private fun imageResult(base64: String): JSONObject {
        if (base64.isEmpty()) return textResult("截屏失败")
        return JSONObject().put("content", JSONArray().put(JSONObject()
            .put("type", "image")
            .put("data", base64)
            .put("mimeType", "image/png")))
    }

    private fun listApps(svc: CtlAccessibilityService): JSONObject {
        val pm = svc.packageManager
        val arr = JSONArray()
        pm.getInstalledPackages(0).take(500).forEach {
            arr.put(JSONObject()
                .put("package_name", it.packageName)
                .put("app_name", it.applicationInfo!!.loadLabel(pm).toString()))
        }
        return textResult(arr.toString())
    }

    private fun startApp(svc: CtlAccessibilityService, pkg: String): JSONObject {
        return try {
            val intent = svc.packageManager.getLaunchIntentForPackage(pkg)
                ?: return textResult("未找到可启动的界面（包名可能不对）")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            svc.startActivity(intent)
            textResult("已启动 $pkg")
        } catch (e: Exception) {
            textResult("启动失败：${e.message}")
        }
    }

    private fun currentApp(svc: CtlAccessibilityService): JSONObject {
        val root = svc.rootInActiveWindow ?: return textResult("无法获取当前窗口")
        return textResult(JSONObject().put("package", root.packageName ?: "").toString())
    }
}
