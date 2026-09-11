package com.watchagents.wa.ui.watch

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal interface WatchHomeActions {
    fun onOpenConversation(id: String)
    fun onNewConversation()
    fun onSettings()
}

/**
 * 圆屏无顶栏版首页：会话列表全屏铺开，WA 字样在左上内切圆内，
 * 右上角设置与右下角新建按钮也全部收入内切圆。
 *
 * @param apiKeyConfigured 是否已填 API Key（默认不内置 Key，未填时空态给出引导）
 */
@Composable
internal fun WatchHomeScreen(
    conversations: List<Triple<String, String, Long>>,
    actions: WatchHomeActions,
    apiKeyConfigured: Boolean = true,
) {
    val listState = rememberLazyListState()
    // 旋转表冠 / 方向键滚动会话列表
    WatchRotaryScrollEffect(listState)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val offsets = watchCircleOffsets()

        // 列表 / 空态（文字全屏铺开）
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = offsets.contentTopPad + 10.dp,
                        bottom = offsets.contentBottomPad,
                        start = 10.dp,
                        end = 10.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💬", fontSize = 36.sp)
                    Text(
                        "点右下角 ＋ 新建会话，用键盘输入问题",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (!apiKeyConfigured) {
                        Text(
                            "未配置 API Key：右上角 ⚙ → DeepSeek Key 填 sk-…",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                contentPadding = PaddingValues(
                    top = offsets.contentTopPad,
                    bottom = offsets.contentBottomPad,
                ),
            ) {
                items(conversations, key = { it.first }) { (id, title, _) ->
                    ConversationRow(
                        title = title.ifBlank { "新对话" },
                        onClick = { actions.onOpenConversation(id) },
                    )
                }
            }
        }

        // 左上角：WA 品牌字（内切圆内）
        Text(
            text = "WA",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = offsets.topStart.first, y = offsets.topStart.second + 8.dp),
        )
        // 右上角：设置（圆屏收入内切圆；方屏贴角）
        WatchRoundButton(
            onClick = actions::onSettings,
            glyph = "⚙",
            glyphSize = 20,
            size = offsets.buttonSize,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = offsets.topEndShift.first, y = offsets.topEndShift.second),
        )
        // 右下角：新建会话（内切圆内；主色圆钮 + 顶部高光 + 按压回弹）
        val fabInteraction = remember { MutableInteractionSource() }
        val fabPressed by fabInteraction.collectIsPressedAsState()
        val fabScale by animateFloatAsState(
            targetValue = if (fabPressed) 0.86f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "fabPressScale",
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = offsets.bottomEndShift.first, y = offsets.bottomEndShift.second)
                .graphicsLayer {
                    scaleX = fabScale
                    scaleY = fabScale
                }
                .size(offsets.fabSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                .clickable(
                    interactionSource = fabInteraction,
                    indication = LocalIndication.current,
                ) { actions.onNewConversation() },
            contentAlignment = Alignment.Center,
        ) {
            // 顶部液态高光
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.30f),
                            0.45f to Color.White.copy(alpha = 0f),
                            1f to Color.White.copy(alpha = 0f),
                        ),
                    ),
            )
            Text(
                "＋",
                fontSize = 26.sp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun ConversationRow(
    title: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, watchGlassBorderColor()),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
