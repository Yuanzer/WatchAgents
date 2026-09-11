package com.watchagents.wa.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import com.watchagents.wa.agent.tool.WatchLocalFileTools
import com.watchagents.wa.data.model.AppearanceSettings
import com.watchagents.wa.data.model.AppearanceThemeMode
import com.watchagents.wa.data.model.WatchRotaryDirection
import com.watchagents.wa.data.repository.AppearanceSettingsRepository
import com.watchagents.wa.ui.watch.LocalWatchRotaryReversed
import com.watchagents.wa.ui.watch.LocalWatchScreenShape
import com.watchagents.wa.ui.watch.WatchAgentState
import com.watchagents.wa.ui.watch.WatchChatActions
import com.watchagents.wa.ui.watch.WatchChatScreen
import com.watchagents.wa.ui.watch.WatchHomeActions
import com.watchagents.wa.ui.watch.WatchHomeScreen
import com.watchagents.wa.ui.watch.WatchRotaryInput
import com.watchagents.wa.ui.watch.WatchSettingsActions
import com.watchagents.wa.ui.watch.WatchSettingsScreen
import com.watchagents.wa.ui.watch.WatchTheme
import com.watchagents.wa.ui.watch.WatchThinkingDialog
import com.watchagents.wa.ui.watch.hasWatchMediaPermission
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchAppTheme()
        }
    }

    /**
     * 旋转表冠（华为/小米/Wear OS）→ 列表滚动。
     *
     * 走 Activity 兜底而不是给每个列表挂 View 监听：Compose 自身不消费通用运动事件，
     * 事件会一路落到这里；再通过 [WatchRotaryInput] 的刻度流分发给当前可见列表。
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        WatchRotaryInput.handleMotionEvent(event) || super.onGenericMotionEvent(event)

    /** 表冠被系统映射成方向键/翻页键的机型走这条。 */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        // 必须继续调 super：ComponentActivity 在 API 33 以下靠它把返回键转给 OnBackPressedDispatcher
        WatchRotaryInput.handleKeyEvent(event) || super.dispatchKeyEvent(event)
}

@Composable
private fun WatchAppTheme() {
    var appearance by remember {
        mutableStateOf(AppearanceSettings())
    }
    DisposableEffect(Unit) {
        val job = kotlinx.coroutines.MainScope().launch {
            AppearanceSettingsRepository.settingsFlow().collectLatest {
                appearance = it
            }
        }
        onDispose { job.cancel() }
    }
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    WatchTheme(darkTheme = isDark) {
        WatchAppRoot(appearance = appearance)
    }
}

private enum class WatchRoute {
    Home,
    Chat,
    Settings,
}

@Composable
private fun WatchAppRoot(appearance: AppearanceSettings) {
    val context = LocalContext.current.applicationContext
    val state = remember { WatchAgentState(context) }
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf(WatchRoute.Home) }
    // 是否"新建会话"进入聊天页：决定用"从右下 ＋ 展开"的专用动画
    var freshConversationEntry by remember { mutableStateOf(false) }
    var thinkingPopup by remember { mutableStateOf(false) }
    var mediaGranted by remember {
        mutableStateOf(hasWatchMediaPermission(context))
    }

    // 系统返回手势/返回键：先关弹窗，其次回首页，而不是直接退出应用
    BackHandler(enabled = thinkingPopup) { thinkingPopup = false }
    BackHandler(enabled = !thinkingPopup && route != WatchRoute.Home) {
        route = WatchRoute.Home
    }

    // 相册/文档运行时授权
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        mediaGranted = hasWatchMediaPermission(context)
    }

    fun requestMediaPermission() {
        // 权限集合与工具侧共用一份定义：Android 13 起是 READ_MEDIA_IMAGES，
        // Android 14+ 还要一并申请 READ_MEDIA_VISUAL_USER_SELECTED（仅选中的照片）
        mediaPermissionLauncher.launch(WatchLocalFileTools.readPermissions().toTypedArray())
    }

    // 屏幕形状 + 表冠方向：只下发用户设置；AUTO 的实际判定在布局层
    // （watchCircleOffsets 用"实测窗口尺寸 + 系统圆屏标记"解析，避免 Configuration 扣掉系统栏导致误判）
    // 音量键兜底开关要在组合后同步给按键分发层（Activity.dispatchKeyEvent 拿不到 Compose 状态）
    SideEffect {
        WatchRotaryInput.volumeKeysAsRotary = appearance.volumeKeyRotary
    }
    CompositionLocalProvider(
        LocalWatchScreenShape provides appearance.screenShape,
        LocalWatchRotaryReversed provides (appearance.rotaryDirection == WatchRotaryDirection.REVERSED),
    ) {
        // 整页铺满主题背景：深浅色都从这里透出（此前各页未画背景，窗口白底直接露出来）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 组合期取值给 transitionSpec 捕获，避免在转场规格里读 Compose 状态
            val freshEntry = freshConversationEntry
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    val toSettings = targetState == WatchRoute.Settings
                    val fromSettings = initialState == WatchRoute.Settings
                    val toChat = targetState == WatchRoute.Chat
                    val fromChat = initialState == WatchRoute.Chat
                    when {
                        // 新建会话：整页从右下角 ＋ 的位置弹开（缩回也是回那个角）
                        toChat && freshEntry -> {
                            (
                                fadeIn(tween(160)) + scaleIn(
                                    initialScale = 0.32f,
                                    transformOrigin = TransformOrigin(1f, 1f),
                                    animationSpec = spring(
                                        dampingRatio = 0.72f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                                ).togetherWith(
                                fadeOut(tween(110)) + scaleOut(
                                    targetScale = 1.04f,
                                    transformOrigin = TransformOrigin(1f, 1f),
                                ),
                            )
                        }

                        fromChat && freshEntry -> {
                            (fadeIn(tween(150)) + scaleIn(initialScale = 1.04f))
                                .togetherWith(
                                    fadeOut(tween(170)) + scaleOut(
                                        targetScale = 0.32f,
                                        transformOrigin = TransformOrigin(1f, 1f),
                                        animationSpec = tween(190),
                                    ),
                                )
                        }

                        // 进入设置：从顶部滑入 + 淡入（设置页左上角就是返回键，方向感一致）
                        toSettings -> {
                            (fadeIn(tween(170)) + slideInVertically(tween(220)) { -it / 4 })
                                .togetherWith(
                                    fadeOut(tween(130)) +
                                        scaleOut(targetScale = 0.94f, animationSpec = tween(200)),
                                )
                        }

                        fromSettings -> {
                            (fadeIn(tween(150)) + scaleIn(initialScale = 0.94f))
                                .togetherWith(
                                    slideOutVertically(tween(200)) { -it / 4 } + fadeOut(tween(130)),
                                )
                        }

                        // 打开已有会话（深入下一层）：新页从略小推近，旧页略放大淡出
                        targetState.ordinal > initialState.ordinal -> {
                            (fadeIn(tween(170)) + scaleIn(initialScale = 0.88f, animationSpec = tween(220)))
                                .togetherWith(
                                    fadeOut(tween(130)) +
                                        scaleOut(targetScale = 1.08f, animationSpec = tween(200)),
                                )
                        }

                        // 返回上一层：旧页往里缩，新页从略大推近
                        else -> {
                            (fadeIn(tween(170)) + scaleIn(initialScale = 1.08f, animationSpec = tween(200)))
                                .togetherWith(
                                    fadeOut(tween(130)) +
                                        scaleOut(targetScale = 0.88f, animationSpec = tween(220)),
                                )
                        }
                    }
                },
                label = "watchRoute",
            ) { target ->
                when (target) {
                    WatchRoute.Home -> WatchHomeScreen(
                        conversations = state.conversationList,
                        apiKeyConfigured = state.apiKeyConfigured,
                        actions = object : WatchHomeActions {
                            override fun onOpenConversation(id: String) {
                                freshConversationEntry = false
                                state.selectConversation(id)
                                route = WatchRoute.Chat
                            }

                            override fun onNewConversation() {
                                freshConversationEntry = true
                                state.createConversation()
                                route = WatchRoute.Chat
                            }

                            override fun onSettings() {
                                route = WatchRoute.Settings
                            }
                        },
                    )

                    WatchRoute.Chat -> {
                        WatchChatScreen(
                            state = state,
                            actions = object : WatchChatActions {
                                override fun onInputChange(value: String) {
                                    state.onInputChange(value)
                                }

                                override fun onSubmit() {
                                    state.submit()
                                }

                                override fun onStop() {
                                    state.stopStreaming()
                                }

                                override fun onBack() {
                                    route = WatchRoute.Home
                                }

                                override fun onToggleThinking() {
                                    thinkingPopup = true
                                }
                            },
                        )
                    }

                    WatchRoute.Settings -> WatchSettingsScreen(
                        appContext = context,
                        state = state,
                        actions = object : WatchSettingsActions {
                            override fun onBack() {
                                route = WatchRoute.Home
                            }

                            override fun onToggleThinking() {
                                thinkingPopup = true
                            }
                        },
                        themeMode = appearance.themeMode,
                        onThemeModeChange = { mode ->
                            scope.launch {
                                AppearanceSettingsRepository.update { it.copy(themeMode = mode) }
                            }
                        },
                        screenShape = appearance.screenShape,
                        onScreenShapeChange = { shape ->
                            scope.launch {
                                AppearanceSettingsRepository.update { it.copy(screenShape = shape) }
                            }
                        },
                        rotaryDirection = appearance.rotaryDirection,
                        onRotaryDirectionChange = { direction ->
                            scope.launch {
                                AppearanceSettingsRepository.update { it.copy(rotaryDirection = direction) }
                            }
                        },
                        volumeKeyRotary = appearance.volumeKeyRotary,
                        onVolumeKeyRotaryChange = { enabled ->
                            scope.launch {
                                AppearanceSettingsRepository.update { it.copy(volumeKeyRotary = enabled) }
                            }
                        },
                        mediaPermissionGranted = mediaGranted,
                        onRequestMediaPermission = ::requestMediaPermission,
                    )
                }
            }
        }

        if (thinkingPopup) {
            WatchThinkingDialog(
                current = state.reasoningEffort,
                onSelect = { effort ->
                    thinkingPopup = false
                    state.onReasoningEffortChange(effort)
                },
                onDismiss = { thinkingPopup = false },
            )
        }
    }
}
