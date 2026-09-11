# Watchagents（WA）
## 在你的手表上直接跑一个本地优先的 AI 智能体——无需 root，无需手机中转，DeepSeek 直连，支持长期记忆

运行在 Android / HarmonyOS 手表上的轻量 AI 智能体。无需 root、无需 Xposed。

- 包名 `com.watchagents.wa` ｜ 版本 `1.0.0`
- 下载 **➜ [Releases](https://github.com/Yuanzer/WatchAgents/releases/latest)**（`WA-1.0.0-release.apk`，已签名）
- 需要自备 DeepSeek API Key（默认不内置）

## 功能

| 能力 | 说明 |
|---|---|
| AI 对话 | DeepSeek 官方兼容端点直连，SSE 流式上屏；思考等级 不思考 / 低 / 高 / 最高 |
| 会话 | 多会话、本地持久化、自动标题 |
| 联网 | `web_search`：Bing RSS → DuckDuckGo 自动回退与合并；`fetch_url` 读原文 |
| 长期记忆 | 本地 `MEMORY.md` + 中文语义检索，可读 / 搜 / 写 |
| 文件 | `files_*` 读写 App 私有沙盒工作区，无需任何权限 |
| 相册与文档 | `media_*` 检索 MediaStore；文本文档可读全文，照片仅返回元数据（纯文本模型看不到像素） |
| 内置技能 | runbook / semantic-memory / daily-log / memory-curator / self-improving-agent / file-workspace / media-reader |
| 表冠 | 首页、聊天、设置列表均可转表冠滚动 |
| 外观 | 深 / 浅色主题；圆屏、方屏、长条屏自适应 |

## 安装

1. 手表开启「设置 → 系统 → 开发者选项 → ADB 调试 / 无线调试」，记下 `IP:端口`
2. 电脑执行：

   ```powershell
   adb connect <手表IP>:<端口>
   adb install -r WA-1.0.0-release.apk
   ```

3. 手表应用列表启动 **WA**

也可以把 APK 拷进手表，用文件管理器直接安装（首次需允许未知来源）。

## 首次使用

打开 App → 右上 ⚙ → 「DeepSeek Key（必填）」粘贴 `sk-...` 并保存。
**默认不内置任何 Key**；未填时发送不会真的发起请求，界面会提示去哪填。

其余设置：模型（默认 `deepseek-v4-flash`）、思考程度、外观（主题 / 屏幕形状 / 表冠方向 /
音量键滚列表）、本地文件与照片授权（首次读相册时点一次）。

可以直接对 WA 说：

- “记一下：每周二 19 点羽毛球” → 写入长期记忆
- “把刚才的答案存成 notes/跑步计划.md” → 工作区文件
- “我下载里有没有叫会议纪要的文档？读一下” → 相册 / 文档工具（先授权）
- “查一下华为 Watch 5 什么时候发布的” → 联网搜索

## 兼容性

| 项 | 值 |
|---|---|
| 系统 | minSdk 26（Android 8.0）/ targetSdk 33 |
| ABI | arm64-v8a、armeabi-v7a、x86、x86_64 |
| 屏幕 | 圆屏 / 方屏 / 长条屏，≈150dp~260dp，控件按屏宽等比缩放 |
| 不要求 | root、Xposed、Wi-Fi 硬件、触摸屏 |
| 真机验证过 | 华为 Watch 4 / 4 Pro（HarmonyOS 4.x） |

其他机型（OPPO / 小米 / Galaxy Watch / Pixel Watch 等）按同一套兼容策略适配，但没有逐台实机验证过。

**Android 7.x（API 24/25）不在支持范围内**：技能子系统依赖 `java.nio.file`（API 26 新增），
core library desugaring 默认不覆盖它。改造路径见 [实现说明](docs/design/implementation.md)。

## 已知限制

- **表冠**：四条事件透传路径都已接好，但哪台手表走哪条只能真机验证。转了没反应时，到
  设置 → 外观 试「音量键滚列表」，或切换「表冠方向」；手指滑动始终可用。
- **输入**：语音 / 麦克风已移除，只走系统键盘；极少数没有可用输入法的手表会出现"点输入框没反应"。
- **必须自备 API Key**。
- **无 root 边界**：可读写沙盒文件、读相册与文本文档、联网搜索、记忆、技能；
  **不能**操作其他 App、拨号、读微信 / QQ / 短信、改系统设置、读 `/data`。
  智能体会明确告知并给替代方案，不假装能做。
- **PDF / Office 正文与照片像素**：纯文本链路无法解析，工具会如实说明。
- **部分国产 ROM「文档」类文件可能列为空**：Android 13+ 没有"读取任意文档"的运行时权限；
  沙盒工作区文件始终可用。

## 构建

需要 JDK 25 与 Android SDK（compileSdk 37）。

```powershell
$env:ANDROID_HOME='<Android SDK 路径>'
$env:WA_RELEASE_STORE_FILE='<keystore 路径>'
$env:WA_RELEASE_STORE_PASSWORD='<keystore 密码>'
$env:WA_RELEASE_KEY_ALIAS='<别名>'
$env:WA_RELEASE_KEY_PASSWORD='<key 密码>'
.\gradlew.bat :app:assembleRelease
```

产物 `app/build/outputs/apk/release/app-release.apk`。不设这四个变量时 release 产物不带签名。

> 四个 `WA_RELEASE_*` 必须与 gradlew 写在同一条命令里（每次 pwsh 是新进程）。
> keystore 与密码不入库（`.gitignore` 已排除 `*.keystore` / `*.jks`）。

```powershell
.\gradlew.bat :app:testDebugUnitTest   # 232 个用例
.\gradlew.bat :app:lintDebug           # 0 问题
```

## 工程结构

- `app/src/main/kotlin/com/watchagents/wa/`
  - `agent/model/` 模型客户端、Agent 循环、提示词与工具 schema
  - `agent/tool/` 本地工具执行（记忆 / 技能 / 搜索 / 抓取 / 文件 / 媒体）
  - `agent/memory/`、`agent/skill/` 记忆与技能运行时
  - `ui/watch/` 手表 UI：主题、屏幕形状与几何、首页 / 聊天 / 设置 / 推理弹窗
  - `ui/model/`、`ui/app/` 会话状态与持久化
- `app/src/main/assets/builtin_skills/` 内置技能（升级 APK 时 SKILL.md 内容自动同步）
- `docs/design/` 设计与实现说明

## 许可

[PolyForm Noncommercial License 1.0.0](LICENSE) —— 允许非商业使用与修改，**禁止商业用途**。
