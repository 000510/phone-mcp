# 手机MCP（Today Phone MCP）

一个跑在安卓手机**本地**的 MCP Server，通过**无障碍权限**（不需要电脑、不需要 ADB、不需要 root）向 AI 客户端（如 Today）提供：

- 📸 截屏（让 AI 看到你的屏幕）
- 👆 点击 / 长按 / 滑动（模拟操作）
- ⌨️ 输入文字
- 🌲 界面元素树（文本 + 坐标，比纯截图更精准）
- 📦 列出 / 启动应用、按系统按键

数据仅在本机回环地址 `127.0.0.1` 传输，不经过任何外部服务器。

## 服务地址

```
http://127.0.0.1:8765/mcp
```

## 工具列表

| 工具 | 参数 | 说明 |
|---|---|---|
| screen_screenshot | - | 截屏，返回 PNG base64 |
| ui_dump | - | 当前界面可交互元素 JSON 列表 |
| screen_info | - | 屏幕尺寸与密度 |
| click | x, y | 点击坐标 |
| long_press | x, y | 长按 |
| swipe | x1,y1,x2,y2,duration_ms | 滑动 |
| input_text | text | 向聚焦输入框输入文字 |
| press_key | key | back/home/recents/enter/delete |
| open_notifications | - | 打开通知栏 |
| open_quick_settings | - | 打开快捷设置 |
| list_apps | - | 已安装应用列表 |
| start_app | package_name | 启动应用 |
| current_app | - | 当前前台应用包名 |

## 手机端构建步骤（全程不需要电脑）

### ① 注册并上传项目到 GitHub

1. 手机浏览器打开 https://github.com 注册/登录
2. 右上角「+」→ New repository，名字随意（如 phone-mcp），**Public**，创建
3. 在仓库页面「Add file → Upload files」，把本 zip 解压后的**全部文件**拖进去
   - 注意 `.github` 文件夹也要上传（手机浏览器若看不到，先用文件管理器确认它在解压目录里）
4. Commit changes

### ② 云端编译

1. 打开仓库的 **Actions** 标签页 → 选 **Build APK** → **Run workflow** → 点绿色按钮
2. 等待 3~10 分钟（手机上别关页面，进入构建详情可看进度）
3. 构建完成后，该次运行页面下方 **Artifacts** 区域出现 `phone-mcp-apk`，点击下载得到 APK

### ③ 安装并启用

1. 打开下载的 APK 安装（需允许「安装未知来源应用」）
2. 打开「手机MCP」App：
   - 点「1. 开启无障碍服务」→ 在系统设置里找到「手机MCP 无障碍服务」开启
   - 返回 App 点「2. 启动 MCP 服务」
   - 点「3. 自检」确认两项都是 ✅

### ④ 接入 Today

Today → 设置 → 添加自定义 MCP → 通过链接添加 → 填入：

```
http://127.0.0.1:8765/mcp
```

之后在对话里就可以让 Today 操作手机了，比如：
「截个图看看我现在屏幕上是什么」「点一下坐标(540, 1200)」「打开微信」

## 已知限制（诚实声明）

- **Today 是否允许 http 本地地址**未经验证——若它强制要求 https 或公网地址，此路不通（这是客户端限制，不是本项目问题）。可以先试，失败时 Today 会报连接错误
- MCP 实现是简化版 JSON-RPC（Streamable HTTP 简化），若 Today 采用严格 SSE 流式协议，可能需要适配
- 无障碍服务可能被系统省电策略杀掉，如失效请回 App 重新启动服务
- 首次使用建议先只做「截屏 + ui_dump」观察，确认行为符合预期再让它点击

## 反馈与迭代

第一版一定有毛边。报错时把 Today 显示的错误信息发出来，逐轮修复即可。
