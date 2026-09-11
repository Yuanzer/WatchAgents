package com.watchagents.wa.ui.watch

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchagents.wa.ui.model.AgentChatMessageUi
import com.watchagents.wa.ui.model.AgentMessageUi
import com.watchagents.wa.ui.model.SystemNoticeMessageUi
import com.watchagents.wa.ui.model.ThinkingMessageUi
import com.watchagents.wa.ui.model.ToolActivityMessageUi
import com.watchagents.wa.ui.model.ToolActivityStatusUi
import com.watchagents.wa.ui.model.UserMessageUi

/** 输入条事件回调（键盘输入版）。 */
internal interface WatchChatActions {
    fun onInputChange(value: String)
    fun onSubmit()
    fun onStop()
    fun onBack()
    fun onToggleThinking()
}

/** 底部毛玻璃带高度：容纳悬浮输入胶囊 + 上下余量，也决定列表底部预留（最后一条能滚出来看全）。 */
private val WatchInputBandHeight: Dp = 64.dp

/** 毛玻璃模糊半径。 */
private val WatchInputBlurRadius: Dp = 18.dp

/**
 * 真·高斯模糊走 RenderEffect，只有 API 31+ 有；更低版本 `Modifier.blur` 会被忽略，
 * 因此直接改走渐变遮罩回落——老手表不背这份 GPU 开销，观感也是"往下淡出"而不是硬切。
 */
private val WatchBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 圆屏无顶栏版会话页：文字/回答全屏铺开，顶栏收敛为贴圆边的两个圆形按钮。 */
@Composable
internal fun WatchChatScreen(
    state: WatchAgentState,
    actions: WatchChatActions,
) {
    val listState = rememberLazyListState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val offsets = watchCircleOffsets()
        // 只读"是否在跑"这种低频状态；消息列表与输入框各自读自己的状态，
        // 这样打字不会重排消息列表、流式增量也不会重组合输入条（手表 CPU 省一半）。
        val isStreaming = state.isStreaming

        // 键盘弹起时可用区域会变矮：列表、毛玻璃带、输入框都在这个区域内对齐
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val areaHeight = maxHeight
                val bandHeight = WatchInputBandHeight
                // 真高斯模糊需要 API 31+ 的 RenderEffect；老机型退回渐变遮罩，不背这份 GPU 开销
                val backdrop = if (WatchBlurSupported) rememberGraphicsLayer() else null

                Box(modifier = Modifier.fillMaxSize()) {
                    // ① 消息列表铺满整屏：内容会一直滚到输入框下方去
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                backdrop?.record(
                                    Density(density, fontScale),
                                    layoutDirection,
                                    IntSize(size.width.toInt(), size.height.toInt()),
                                ) {
                                    this@drawWithContent.drawContent()
                                }
                                drawContent()
                            },
                    ) {
                        WatchMessageList(
                            state = state,
                            listState = listState,
                            contentTopPad = offsets.contentTopPad,
                            contentBottomPad = bandHeight + 6.dp,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // ② 底部毛玻璃带：把①的内容模糊后只画底部一条，
                    //    于是对话是"从输入框下方糊着穿过去"，而不是被一块实心背景硬切
                    if (backdrop != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(bandHeight)
                                .clipToBounds()
                                .blur(WatchInputBlurRadius)
                                .drawWithContent {
                                    drawContent()
                                    // 顶部渐隐：避免"清晰/模糊"交界处出现一条硬边
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.5f to Color.Black,
                                            1f to Color.Black,
                                        ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                },
                        ) {
                            // 与 ① 完全对齐地重绘记录下来的图层，再被上面的 clip + blur 处理
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(areaHeight)
                                    .offset(y = -(areaHeight - bandHeight))
                                    .drawBehind { drawLayer(backdrop) },
                            )
                        }
                    } else {
                        // ③ 低版本（API 26~30）回落：渐变遮罩，文字往下自然淡出
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(bandHeight)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                                        ),
                                    ),
                                ),
                        )
                    }

                    // ④ 悬浮输入胶囊（半透明玻璃，浮在毛玻璃带上）
                    WatchInputBar(
                        state = state,
                        actions = actions,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    if (state.showApiKeyHint) {
                        WatchApiKeyHint(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = bandHeight),
                        )
                    }
                }
            }
        }

        // —— 角部悬浮按钮(液态玻璃)：尺寸随屏宽缩放 ——
        WatchRoundButton(
            onClick = actions::onBack,
            glyph = "‹",
            glyphSize = 28,
            size = offsets.buttonSize,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = offsets.topStart.first, y = offsets.topStart.second),
        )
        // 思考中隐藏 🧠：避免与顶部呼吸胶囊重叠，右上角让给运行状态
        if (!isStreaming) {
            WatchRoundButton(
                onClick = actions::onToggleThinking,
                glyph = "🧠",
                glyphSize = 20,
                size = offsets.buttonSize,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = offsets.topEndShift.first, y = offsets.topEndShift.second),
            )
        }
        // 顶部中央状态胶囊（仅在运行时显示）
        WatchStatusCapsule(
            visible = isStreaming,
            text = "● 思考/搜索中…",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(
                    x = 0.dp,
                    y = offsets.topStart.second + offsets.buttonSize / 2 - 11.dp,
                ),
        )
    }
}

/**
 * 消息列表（独立重组作用域）。
 *
 * 自动跟随策略：只在"用户就停在底部"时跟着流式内容走；用户往上翻就停住不再抢滚动，
 * 翻回底部自动恢复。滚动用瞬时定位而不是逐 token 重启动画——手表上差别很明显。
 *
 * [contentBottomPad] 预留出悬浮输入框的高度，最后一条消息才能滚到输入框上方看全。
 */
@Composable
private fun WatchMessageList(
    state: WatchAgentState,
    listState: LazyListState,
    contentTopPad: Dp,
    contentBottomPad: Dp,
    modifier: Modifier = Modifier,
) {
    val messages = state.messages
    val lastIndex = messages.lastIndex
    val lastLength = messages.lastOrNull()?.streamingLength() ?: 0

    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                // 用户主动滑动时按"是否滑到底"重设跟随；程序化滚动到底同样会恢复跟随
                if (scrolling) autoFollow = !canScrollForward
            }
    }

    // 旋转表冠 / 方向键滚动
    WatchRotaryScrollEffect(listState)

    LaunchedEffect(lastIndex, lastLength) {
        if (autoFollow && lastIndex >= 0) {
            // 偏移给足 = 视口停在内容底部（会被自动夹到最大滚动位置）
            listState.scrollToItem(lastIndex, Int.MAX_VALUE / 2)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            top = contentTopPad,
            start = 8.dp,
            end = 8.dp,
            bottom = contentBottomPad,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            WatchMessageBubble(message = message)
        }
    }
}

/** 未配置 API Key 时的引导条（默认不内置 Key，首启必然遇到）。 */
@Composable
private fun WatchApiKeyHint(modifier: Modifier = Modifier) {
    Text(
        text = "未配置 API Key：设置 → DeepSeek Key 粘贴 sk-… 后重发",
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** 流式长度：只用来触发"跟随底部"，避免把整条消息当 key 反复比较。 */
private fun AgentChatMessageUi.streamingLength(): Int = when (this) {
    is AgentMessageUi -> content.length
    is ThinkingMessageUi -> content.length
    is UserMessageUi -> content.length
    is ToolActivityMessageUi -> toolName.length + (resultSummary?.length ?: 0)
    is SystemNoticeMessageUi -> detail?.length ?: 0
    else -> 0
}

@Composable
internal fun WatchMessageBubble(message: AgentChatMessageUi) {
    when (message) {
        is UserMessageUi -> AlignedBubble(
            alignEnd = true,
            bubbleColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Text(message.content, fontSize = 15.sp, lineHeight = 20.sp)
        }

        is AgentMessageUi -> AlignedBubble(
            alignEnd = false,
            bubbleColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Text(
                message.content.ifBlank { "…" },
                fontSize = 15.sp,
                lineHeight = 20.sp,
            )
        }

        is ThinkingMessageUi -> ThinkingMessageRow(message)

        is ToolActivityMessageUi -> AlignedBubble(
            alignEnd = false,
            bubbleColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            val marker = when (message.status) {
                ToolActivityStatusUi.Running -> "⚙"
                ToolActivityStatusUi.Success -> "✓"
                ToolActivityStatusUi.Failed -> "✗"
            }
            Text("$marker ${message.toolName}", fontSize = 12.sp)
        }

        is SystemNoticeMessageUi -> AlignedBubble(
            alignEnd = false,
            bubbleColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.error,
        ) {
            Text(message.detail ?: "运行失败", fontSize = 12.sp)
        }

        else -> Unit
    }
}

@Composable
private fun ThinkingMessageRow(message: ThinkingMessageUi) {
    val content = message.content
    val count = content.trim().length
    if (message.isStreaming) {
        // 思考中：呼吸闪烁的轻量状态行（不占大块版面）
        val pulse = rememberInfiniteTransition(label = "thinkingPulse")
        val alpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "thinkingAlpha",
        )
        Text(
            text = "思考中…" + if (count > 0) "（${count}字）" else "",
            fontSize = 12.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { this.alpha = alpha },
        )
        return
    }
    // 已完成：默认收起为"已思考 N字"，点一下展开详情、再点收起
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Text(
                text = "已思考 ${count}字" + if (expanded) " ▴" else " ▾",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(140)),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(
                    text = content.ifBlank { "…" },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun AlignedBubble(
    alignEnd: Boolean,
    bubbleColor: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (alignEnd) 14.dp else 4.dp,
                bottomEnd = if (alignEnd) 4.dp else 14.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Box(Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                content()
            }
        }
    }
}

/**
 * 悬浮输入条：半透明玻璃胶囊浮在底部毛玻璃带上，发送/停止为条内小圆钮。
 *
 * 自己读 `state.input` / `state.isStreaming`：打字时只重组合这一条，不动消息列表。
 */
@Composable
private fun WatchInputBar(
    state: WatchAgentState,
    actions: WatchChatActions,
    modifier: Modifier = Modifier,
) {
    val input = state.input
    val streaming = state.isStreaming
    val capsuleShape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = capsuleShape,
            modifier = Modifier
                .fillMaxWidth(0.68f)
                // 半透明底色 + 玻璃渐变/描边：毛玻璃带透上来，形成"液态玻璃"胶囊
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                    capsuleShape,
                )
                .watchGlass(capsuleShape),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 5.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = actions::onInputChange,
                    textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = false,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (input.isBlank()) {
                            Text(
                                "输入…",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
                Surface(
                    onClick = if (streaming) actions::onStop else actions::onSubmit,
                    shape = CircleShape,
                    color = if (streaming) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (streaming) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (streaming) "■" else "➤",
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.offset(x = (-0.5).dp, y = 0.dp),
                        )
                    }
                }
            }
        }
    }
}
