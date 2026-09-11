# Watchagents（WA）— Android / HarmonyOS 手表智能体

面向 **华为 Watch 4 / Watch 4 Pro（HarmonyOS 4.x，非 NEXT）与 Wear OS 2.x~5 手表**的轻量智能体。
**无 root / 无 Xposed / 无 adb 依赖**：键盘输入 + AI 文字回复 + DeepSeek 直连
（思考等级）+ 联网搜索（多引擎）+ 长期记忆 + runbook/文件/媒体技能 + 深浅色主题。

- 应用名 **WA**（全名 **Watchagents**），包名 `com.watchagents.wa`
- 当前版本 **1.0.0**（首个公开版本，功能等价于内部迭代版 3.3.4）

## 功能清单

| 能力 | 说明 |
|---|---|
| 键盘输入 | 纯键盘（无麦克风/录音依赖，避免鸿蒙兼容层崩溃源） |
| AI 文字回复 | DeepSeek 官方兼容端点直连（`api.deepseek.com`），SSE 流式上屏；**默认不内置 Key**，首次使用需在设置页自行粘贴 |
| 思考等级 | 聊天页右上 🧠（独立圆形按钮）或设置页「思考程度」→ 不思考/低/高/最高 |
| 会话历史 | 多条会话、Room 持久化、自动标题 |
| 深浅色主题 | 设置页「外观」：跟随系统 / 浅色 / 深色，配色对齐上游手机版（蓝基调）；深色默认对 OLED 更省电 |
| 长期记忆 | 本地 `MEMORY.md` + 中文语义检索，Agent 可读/搜/写 |
| 内置技能 | runbook / semantic-memory / daily-log / memory-curator / self-improving-agent / file-workspace / media-reader |
| 本地文件 | `files_*`：App 私有沙盒工作区（笔记/清单/文档/草稿）读写删，无需任何权限 |
| 相册与文档 | `media_*`：MediaStore 检索照片/文档，文本类文档可读全文；照片返回元数据（纯文本模型看不到像素）；需在设置页点一次「授权」 |
| 联网搜索 | `web_search`：Bing RSS → DuckDuckGo 多引擎自动回退/合并 + `fetch_url` 读原文；提示词注入检索纪律与子问题拆分策略 |

## 跨机型兼容（3.3）

目标：**同一份 APK 尽量覆盖各家安卓手表**——华为 HarmonyOS 手表、OPPO / 小米等国产安卓手表、
Wear OS 2.x~5、Galaxy Watch、Pixel Watch，圆屏 / 方屏 / 长条屏都可用。

### 支持范围

| 项 | 取值 | 说明 |
|---|---|---|
| minSdk | **26（Android 8.0）** | 代码使用 `java.nio.file`（API 26 新增），这是硬下限 |
| targetSdk | 33 | 刻意不升 34/35：升上去只会引入前台服务类型、强制 edge-to-edge 等新约束，手表无收益 |
| ABI | arm64-v8a / armeabi-v7a / x86 / x86_64 | 依赖自带的小 `.so` 四个 ABI 都打了，32 位手表也能装 |
| 屏幕 | 圆屏 / 方屏 / 长条屏 | 按形状自动分派几何，见第 2、3 条 |
| 屏幕尺寸 | ≈150dp~260dp | 控件尺寸按屏宽等比缩放 |

**为什么不做 API 24/25（Android 7.x，Wear OS 2.0 那一代）**：技能子系统（`SkillRuntime` /
`SkillPackageInstaller` / `SkillRecoveryJournal` 等）大量使用 `java.nio.file`，该包在 Android 上
API 26 才出现，脱糖（core library desugaring）默认也**不覆盖**它。要降到 24/25 必须换成
`desugar_jdk_libs_nio` 变体并改造这些调用，属于大改动，见文末"可选下一步"。

### 这一版修掉的兼容问题

1. **一启动就崩（最严重，值得单独说）**：`Application.getProcessName()` 是 **API 28** 才有的方法，
   原代码直接调在 `Application.onCreate` 里 → 在 Android 8.0/8.1（API 26/27，OPPO Watch、
   小米手表等一大批国产安卓手表）上**装得上、打开即闪退**。现按 API 分支，26/27 退回读
   `/proc/self/cmdline`（`app/.../AppProcessPolicy.kt`）。
2. **长条屏白留一半高度**：3.2 所有几何都按"圆屏内切圆"硬编码，OPPO Watch（402×476）、
   小米手表（368×448）这类长方形屏上下各要白留 30%~40%，列表只剩一条缝。
   现在按形状分派：长方形屏用贴边几何，同一块 238dp 高的屏内容区从约 91dp 提到约 144dp。
3. **圆/方屏自动判定**：`AUTO` 看系统圆屏标记 + **实测窗口长宽比**（不用
   `Configuration.screenWidthDp/HeightDp`——它会扣掉系统栏，圆屏带系统栏就会被误判成长方形）；
   长宽比接近 1:1 时保守按圆屏处理（圆屏几何用在方屏上只是上下留白，反过来会把角部按钮切到圆外）。
   设置页「外观 → 屏幕形状」可手动锁定 圆形 / 方形（`WatchRoundLayout.kt`、`AppearanceSettings.kt`）。
4. **小屏手表控件占满圆屏**：角部按钮 / FAB 尺寸改为按屏宽等比缩放，基准取 Watch4 的
   228dp（466px ÷ 2.04）。Watch4 上为 34dp 按钮 / 44dp FAB，Samsung、Pixel 那类 ~198dp 屏
   自动缩小（34→29.6dp、44→38dp）。
5. **系统返回手势直接退出应用**：新增 `BackHandler`——先关推理弹窗，再回首页（`MainActivity.kt`）。
6. **旋转 / 字体大小 / 深色模式切换会重建 Activity**：现声明 `configChanges`，交给 Compose 按新
   Configuration 重组，不再重建（否则正在流式输出的回答会随重建丢掉）。
7. **Android 14「仅选中的照片」**：一并申请 `READ_MEDIA_VISUAL_USER_SELECTED`，任一授予即视为可用
   （`WatchLocalFileTools.readPermissions()`，设置页与工具侧共用同一份权限定义）。
8. **无 Wi-Fi 手表被过滤**：`ACCESS_WIFI_STATE` 会被系统隐含推导成 `android.hardware.wifi` **必需硬件**，
   而代码里没有任何 Wi-Fi 调用，已删除该权限；同时声明触摸屏 `required=false`，
   并**不**声明 `android.hardware.type.watch`（若声明为 required=true，HarmonyOS 这类未上报该 feature
   的手表会直接装不上）。
9. **冷启动白闪**：主题补 `windowBackground`（深浅两套色），OLED 手表启动不再闪一下白屏。
10. **Wear OS 体验**：加 `com.google.android.wearable.standalone=true`、`taskAffinity=""`、
    `supportsRtl`，让"独立应用 / 最近任务"行为正常。

### 验证方式

```powershell
.\gradlew.bat :app:testDebugUnitTest   # 232 个用例（含几何/表冠方向/转速模型回归）
.\gradlew.bat :app:lintDebug           # NewApi / InlinedApi / SelectedPhotoAccess / RestrictedApi 均为 0
```

### 仍然存在的限制（如实告知）

- **Android 7.x（API 24/25）手表装不上**，原因见上，属于工程取舍而非疏漏。
- **表冠能不能转要按机型实测**：四条透传路径都已接好（见「旋转表冠滚列表」），
  但哪台手表走哪条只能真机验证。若转了没反应：先试 设置 → 外观 →「音量键滚列表」，
  再试「表冠方向」切反向；还不行就把机型告诉我。手指滑动始终可用。
- **必须有系统输入法**：输入只走键盘（语音已按 3.2 的原因整体移除）；极少数没有可用输入法的
  手表会出现"点输入框没反应"。
- **没有 API Key 时不能对话**：默认不内置 Key，这是刻意的（详见「默认不内置 API Key」）。
- **部分国产 ROM 下"文档"类文件列为空**：Android 13+ 没有"读取任意文档"的运行时权限，
  这是系统限制（不崩、会给明确提示）；App 沙盒工作区文件（`files_*`）始终可用。

## 3.3 交互与性能优化

### 悬浮输入框 + 毛玻璃（3.3.1）

- 输入框从"占据底部一条"改成**悬浮胶囊**：消息列表铺满整屏，对话内容直接滚到输入框下方去，
  不再被一块不透明底色硬切。
- 输入框下面垫一条**毛玻璃带**：把列表内容模糊后只画底部 64dp，再用 DstIn 渐变把顶部渐隐，
  避免"清晰/模糊"交界处出现硬边；胶囊本身是半透明玻璃（半透明底色 + 渐变高光 + 亮描边）。
- **真高斯模糊走 RenderEffect，只有 API 31+ 有**（Watch4 的兼容层是 API 31 量级，能吃上）；
  API 26~30 的老手表自动退回"渐变遮罩"——文字往下淡出，同样不是硬切，且不背模糊的 GPU 开销。
  判断在 `WatchChatScreen.kt` 的 `WatchBlurSupported`。
- 列表底部预留 70dp，最后一条消息能滚到输入框上方看全；键盘弹起时
  （列表 + 毛玻璃带 + 胶囊）整块一起上移。

### 转场动画（3.3.1）

| 动作 | 动画 |
|---|---|
| 新建会话（右下 ＋） | 聊天页**从右下角 ＋ 的位置弹开**（`scaleIn(0.32, origin=右下)` + 弹簧），返回时缩回那个角 |
| 打开已有会话 | 纵深推进：新页从 0.88 推近，旧页放大到 1.08 淡出 |
| 返回首页 | 反向：旧页缩到 0.88，首页从 1.08 推近 |
| 进入设置 | 设置页**从顶部滑入**（设置页左上角就是返回键，方向感一致），首页轻微缩小淡出 |
| 退出设置 | 设置页向上滑出，首页从 0.94 推近 |

时长 150~220ms，只动 Transform 与 alpha（`graphicsLayer` 层面，不触发重新布局）。

### 表冠：平滑模型 + 速度跟随转速 + 方向修正（3.3.2 ~ 3.3.4）

**平滑模型（3.3.3 引入，3.3.4 调参）**

表冠刻度不再直接推列表，而是往一个**待滚像素队列**里加数，真正的滚动在一个逐帧循环里
按时间常数指数逼近（`rotarySmoothingStep`，τ = 20ms）：

| 维度 | 行为 |
|---|---|
| 一个刻度 | 分摊到约 5 帧走完（60fps 下每帧推进积压的 56.5%，**前 4 帧就走完 96%**），不再一帧瞬跳 34dp |
| 一次来一批刻度（国产 ROM 常见） | 合成一次平滑推进，而不是一次大跳 |
| 稳态速度 | = 刻度速率 × 34dp，**与旋转速度严格成正比**；队列积压 ≈ 每帧输入量的 0.77 倍（典型转速下不到 0.2 格） |
| 松手 | 剩下的队列自然排空 → 与转速成正比的短惯性尾（约 100ms），不会飞过头 |
| 掉帧保护 | 单帧最多推进积压的 **65%**，且 dt 夹在 50ms 内 → 卡顿也不会一帧跳掉一大段 |
| 空闲 | 完全不请求帧（`wakeUps` 通道挂起），不白耗电 |
| 被手势/系统抢占 | 丢掉积压 ≠ 补跳（>350ms 的空档直接清零） |

**手感调参**（都只改一个常量，位于 `WatchRotaryScroll.kt`）：

| 想要 | 改什么 |
|---|---|
| 更跟手 / 更绵 | `ROTARY_SMOOTHING_SECONDS`：当前 `0.02f`（越小越跟手，试过 0.035 偏黏） |
| 更快 / 更慢 | `WatchRotaryScrollEffect(dpPerTick = ...)`：当前 `34`（24 → 26 → 34，按真机反馈两次上调） |

**为什么不用前两版的做法**：

- 3.3.1 的"每格固定跑 120ms 动画"：动画串行，快转时调用频率超过动画时长 → 排成队列、越转越落后；
- 3.3.2 的"位置立即 1:1 + `splineBasedDecay` 惯性滑行"：方向对了、速度跟手了，
  但**位置是瞬跳的**（离散刻度 = 一帧一格），观感就是"一点一点突然滑动"。
- 3.3.3/3.3.4 用"队列 + 逐帧指数跟随"同时解决：连续平滑、速度跟手、延迟只有约 1 帧。

**方向修正（3.3.2 真机反馈）**

- `AXIS_SCROLL` 正轴值应当让列表**向上回滚**，而 Compose 的正值是"向列表尾部滚动"，
  所以轴值统一取负（`ROTARY_AXIS_DIRECTION`），并用单元测试钉住这条约定。
- 各家手表定义不统一，新增 设置 → 外观 → **表冠方向**（标准 / 反向），不用改代码就能纠正。

### 旋转表冠滚列表（3.3 首版，华为 / 小米优先）

首页会话列表、聊天消息列表、设置列表都支持转表冠滚动。
实现放在 Activity 兜底分发 + 刻度流（`WatchRotaryScroll.kt`、`MainActivity.kt`），
三条透传路径一次覆盖（另加旧式滚动轴兜底）：

| 表冠透传方式 | 典型机型 | 事件 |
|---|---|---|
| 标准旋转编码器 | Wear OS 全系；多数把表冠透传给 APK 的手表 | `ACTION_SCROLL` + `AXIS_SCROLL` |
| 只给滚动轴、不打编码器 source 标记，或只填旧式垂直轴 | 部分厂商实现 | `AXIS_SCROLL`，取不到则退回 `AXIS_VSCROLL` |
| 表冠被系统映射成按键 | Wear OS 2.x 旋转导航、部分国产手表 | `DPAD_UP/DOWN`、`PAGE_UP/DOWN`、`SYSTEM_NAVIGATION_UP/DOWN` |
| 表冠即音量键 | 少数品牌 / ROM | 设置 → 外观 →「音量键滚列表」打开后才拦 |

- 只认滚动轴与方向/翻页键（音量键默认不拦），**绝不碰返回键**；输入法窗口有焦点时这些键本来也到不了 App。
- 没有列表在监听时**不消费系统事件**（`consumerCount`），避免无谓地吞掉按键。
- 没有界面在听时刻度直接丢弃，不会出现"换页后乱滚"；快转丢旧留新，不会排队卡顿。

### 默认不内置 API Key

`BuiltinProviders.DEFAULT_DEEPSEEK_API_KEY` 默认为空字符串：**不预置任何 Key**。
未填时 `WatchAgentState.submit()` 直接返回，**不发起请求、也不清空已输入内容**，
首页空态 + 聊天页输入条上方 + 设置页 Key 栏（标注「必填」）三处给出"去哪填"的引导。
想恢复"内置开箱可用"只需把那一行改回 `sk-...`（`ProviderRepository.ensureBuiltInsMerged`
会自动把非空默认值回填给空 Key 的 DeepSeek，无需改别的代码）。

### 动画与性能

- **聊天页重组作用域拆分**：消息列表与输入条各自读自己的状态——打字不再重排消息列表、
  流式增量也不再重组合输入条，手表 CPU 上省掉大量无用重组。
- **流式跟随**：只在"用户停在底部"时跟随（往上翻立即停手，翻回底部自动恢复）；
  滚动改成瞬时定位，不再每个 token 重启动一次滚动动画。
  顺带修好一个 3.2 的老问题：长回答以前只在消息条数变化时滚一次，会滚出屏幕看不全。
- **减少全屏重绘**：页面转场 240ms → 180ms 且缩放幅度减小；呼吸/脉冲动画只改
  `graphicsLayer` 的 alpha（draw 阶段），不触发重组。
- 首页会话列表用 `derivedStateOf` 缓存排序，不再每次重组都重新 map+sort。

## 圆屏 UI 说明（3.2 版式，3.3 调整了贴边与滚动）

- 文字/AI 回答仍**全屏铺开**（不做中央小方块内收）。
- 顶栏已取消：聊天页左上角 ‹ 返回、右上角 🧠 思考均为**独立圆形悬浮按钮**；
  首页右上 ⚙、右下 ＋、设置页左上 ‹ 同理。
- 角部控件按**窗口内切圆几何**摆放：`inset = side/2·(1−1/√2)`（≈ side×0.146）。
  3.3 起不再用固定的"内收 14dp / 抬升 10dp"，而是把按钮**外角精确放在内切圆内的
  安全圆上**（半径 = R − `WatchCircleSafeMargin`），方向由 `WATCH_CORNER_HUG_ANGLE_DEG`
  决定（默认 40°）：既不越出弧线被切掉，也尽量贴左右边缘；内容上边距从"按钮底边 + 4dp"
  起算，把高度全让给对话。Watch4 上相比 3.2：按钮离左右边缘近约 7dp、对话区多约 9dp。
  想再贴边就调大角度，想再给对话让高度就调小角度（都在 `WatchRoundLayout.kt`）。
- **圆内布局采用静态安全距(不做逐行动态变形)**:曾试过"按行实时位置自动收窄",真机上列表行
  坐标与窗口圆存在系统级偏移,导致中部错收窄、设置页空白,已在 3.2 修正版移除。
  现在列表行保持全宽铺开,上下仅用几何边距避让弧区:
  首页/聊天页底距随右下 ＋ 走;设置页圆屏底部固定 60dp 安全距,最末行文字完整位于圆内,
  滚动经过顶/底弧区时由屏幕自身自然裁切(这是圆形屏的物理裁剪,不改变排版)。
- **液态玻璃质感**（轻量实现，无真实高斯模糊、不卡顿）：顶角圆钮/输入胶囊/卡片描边
  用半透明渐变 + 亮描边，深浅色各一套（`watchGlassFill/watchGlassBorderColor`）；
  按钮/右下 ＋/推理等级圆钮带**按压回弹弹簧动画**，页面切换为淡入 + 轻微放大过渡
  （3.3 缩短到 180ms，少一次全屏重绘），思考中胶囊与「思考中…」行呼吸闪烁
  （只改 `graphicsLayer` 的 alpha，不触发重组）；右下 ＋ 顶部带液态高光。
- 整体尺寸缩小；**文字渲染为旧版的 0.55 倍**（=旧 0.5 基础上再放大 10%，
  主题内 `WATCH_TEXT_RENDER_SCALE = 0.55f` 统一缩放 sp，dp 布局不受影响）。
  想微调字号只需改这一个常量。
- **思考详情默认收起**：一轮思考结束后显示「已思考 N字 ▾」小胶囊，点击原地平滑展开
  完整思考过程、再点收起；思考中只显示呼吸闪烁的「思考中…」状态行。
- **推理等级弹窗（圆屏版）**：不整块弹大面板，改为屏幕中央 2×2 四个圆形等级按钮
  （不思考/低/高/最高，选中高亮 + 白描边 + 回弹），四个圆都在内切圆内，
  不存在底部按钮/文字被圆弧遮住的问题；点空白处关闭。

## 工程结构（D:\WatchAgents）

- `app/` Android 模块（Kotlin + Jetpack Compose Material3）
- `app/src/main/kotlin/com/watchagents/wa/`：
  - `agent/model/` DeepSeek 客户端、Agent 循环、提示词（`AgentPromptBuilder.kt`）、工具 schema
  - `agent/tool/WatchToolExecutor.kt` 本地工具执行（记忆/技能/搜索/抓取）
  - `agent/tool/WatchLocalFileTools.kt` 工作区文件 + MediaStore 照片/文档（权限守卫）
  - `agent/memory/` `agent/skill/` 记忆与技能运行时（无 root）
  - `ui/watch/` 圆屏 UI：`WatchTheme`（深浅色+字号）、`WatchRoundLayout`（屏幕形状判定 +
    按屏宽缩放的几何）、Home/Chat/Settings/推理弹窗
  - `ui/model/` `ui/app/` 会话状态模型与持久化
- `app/src/main/assets/builtin_skills/` 内置技能（升级 APK 后 SKILL.md 内容变化会自动同步，保留技能自身数据目录）

## 构建（Windows 电脑）

环境要求：JDK 25、Android SDK（compileSdk 37）。本机验证命令：

```powershell
cd 'D:\WatchAgents'
$env:ANDROID_HOME='D:\dev\android-sdk'
$env:WA_RELEASE_STORE_FILE='D:\keys\wa-release.keystore'
$env:WA_RELEASE_STORE_PASSWORD='<你的 keystore 密码>'
$env:WA_RELEASE_KEY_ALIAS='wa'
$env:WA_RELEASE_KEY_PASSWORD='<你的 key 密码>'
.\gradlew.bat :app:assembleRelease
```

产物：`app\build\outputs\apk\release\app-release.apk`（签名，WA 1.0.0 / versionCode 1，约 1.8 MB）。

> 发布用的 keystore **不入库**（`.gitignore` 已排除 `*.keystore` / `*.jks`），密码也不写进仓库。

> 四个 `WA_RELEASE_*` 环境变量必须在**同一条命令**里设置并执行 gradlew（每次 pwsh 是新进程）。
> 想用自己的签名：替换 store 文件与密码即可。

### 可选下一步（要覆盖 Android 7.x 手表时才做）

1. `app/build.gradle.kts`：`minSdk = 24`，开启 `isCoreLibraryDesugaringEnabled = true`，
   依赖换成 `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.x")`（nio 变体才含
   `java.nio.file`）。
2. 逐个复核 `Files.*` 行为差异（脱糖实现不支持符号链接，`Files.isSymbolicLink` 恒为 false ——
   本工程把它当安全校验用，需确认该降级可接受）。
3. 重新跑测试并在 API 24 模拟器上验证启动、技能安装、媒体读取三条路径。

## 安装到手表（侧载 APK）

1. **开开发者模式**：手表 设置 → 关于手表 → 连续点击版本号（约 7 次）直至提示进入开发者模式。
2. **开无线调试**：设置 → 系统 → 开发者选项 → 打开「ADB 调试/无线调试」，记下 `IP:端口`。
3. **电脑连接**（同一 Wi-Fi）：
   ```powershell
   adb connect <手表IP>:<端口>
   adb devices
   ```
4. **安装**（首次在手表端允许未知来源）：
   ```powershell
   adb install -r app-release.apk
   ```
5. 应用列表启动 **WA**（或 `adb shell monkey -p com.watchagents.wa 1`）。

## 使用

1. 打开 App → 首页右上 ⚙ 进设置。
2. **先填 DeepSeek Key**：设置页「DeepSeek Key（必填）」粘贴 `sk-...` 后点「保存 Key」
   （默认不内置任何 Key；未填时首页空态与聊天页会给出提示，发送不会真的发出请求）。
3. 模型默认 `deepseek-v4-flash`（快/省电）；可切换更强或支持视觉的型号。
4. 「外观」可切换 跟随系统/浅色/深色；「思考程度 → 推理等级」设默认档位
   （默认「不思考」最省电，新会话自动继承；聊天中右上 🧠 只调当前会话）。
5. 「本地文件与照片」：首次让 Agent 读相册/文档时点一次「授权」；
   若返回 `PERMISSION_DENIED` 回到此处授权后重试即可。
6. 返回首页 ＋ 新建会话 → 点输入框打字 → 发送。

用法示例（可直接对 WA 说）：
- “记一下：每周二 19 点羽毛球” → 写入长期记忆
- “把刚才的答案存成文件，叫 notes/跑步计划.md” → files_write（工作区沙盒）
- “我下载里有没有叫会议纪要的文档？读一下” → media_list/media_read（先授权）
- “查一下华为 Watch 5 什么时候发布的，要最新的消息” → web_search（多引擎）
- “今天星期几 / 现在几点” → 直接用注入的设备本地时间回答

## 输入方式说明

语音/麦克风已整体移除（鸿蒙兼容层对第三方录音/识别不稳定，曾致启动崩溃），
只保留系统键盘输入；点输入框拉起 Watch 自带输入法。

## 无 root 边界（如实告知）

- 读/写工作区文件、读相册与文本文档、联网搜索、记忆、技能：**可用**。
- 操作其他 App、拨打电话、读微信/QQ/短信、改系统设置、读 `/data` 等：**不可行**；
  智能体已按提示词纪律明确告知并给替代方案，不假装能做。
- PDF/Office 正文与照片像素内容：纯文本链路无法解析，工具与提示词均会如实说明。

## 单元测试

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

覆盖 provider 建模/选择、思考能力解析、请求拼装、记忆/技能运行时、设置等
（Robolectric 在 JDK25 下的 native-access 输出为环境噪音）。
