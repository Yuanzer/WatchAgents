package com.watchagents.wa.ui.watch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.watchagents.wa.data.model.ReasoningEffort
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 推理等级选择弹窗（圆屏优化版）：不依赖整块大面板，改为 2×2 四个圆形等级按钮
 * 悬浮在屏幕中央，每个圆代表一个等级；选中圆即高亮并轻微回弹，随后自动收起，
 * 圆与圆都在内切圆内，不存在底部选项被屏幕圆弧遮住的问题。
 */
@Composable
internal fun WatchThinkingDialog(
    current: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<ReasoningEffort?>(null) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            // 点空白处 = 取消
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { if (picking == null) onDismiss() },
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val side = watchCircleSide
                val circleSize = circleSizeFor(side)
                val options = listOf(
                    Triple(ReasoningEffort.OFF, "不思考", "○○○"),
                    Triple(ReasoningEffort.LOW, "低", "●○○"),
                    Triple(ReasoningEffort.HIGH, "高", "●●○"),
                    Triple(ReasoningEffort.MAX, "最高", "●●●"),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            val s = appear.value
                            scaleX = 0.86f + 0.14f * s
                            scaleY = 0.86f + 0.14f * s
                            alpha = s
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "推理等级",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "当前：${current.label()}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    options.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { (effort, label, dots) ->
                                WatchEffortCircle(
                                    label = label,
                                    dots = dots,
                                    active = effort == current || picking == effort,
                                    size = circleSize,
                                    enabled = picking == null,
                                    onClick = {
                                        picking = effort
                                        scope.launch {
                                            delay(170)
                                            onSelect(effort)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        "点空白处关闭",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 圆圈直径：随屏宽缩放，保证 2×2 整体落在内切圆内。 */
private fun BoxWithConstraintsScope.circleSizeFor(side: Dp): Dp {
    val target = (side * 0.60f - 12.dp) / 2f
    return if (target > 64.dp) 64.dp else if (target < 44.dp) 44.dp else target
}

/** 单个圆形等级按钮：未选中为液态玻璃质感，选中为高亮主色 + 亮描边 + 回弹。 */
@Composable
private fun WatchEffortCircle(
    label: String,
    dots: String,
    active: Boolean,
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "effortPressScale",
    )
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val shape = CircleShape
    val base = Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .size(size)
        .clip(shape)
    val styled = if (active) {
        base
            .background(MaterialTheme.colorScheme.primary, shape)
            .border(2.dp, Color.White.copy(alpha = 0.85f), shape)
    } else {
        base.watchGlass(shape)
    }
    Box(
        modifier = styled.clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
        ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                textAlign = TextAlign.Center,
            )
            Text(
                text = dots,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = if (active) {
                    contentColor.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal fun ReasoningEffort.label(): String = when (this) {
    ReasoningEffort.OFF -> "不思考"
    ReasoningEffort.MINIMAL -> "极简"
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.DEFAULT -> "默认"
    ReasoningEffort.MEDIUM -> "中"
    ReasoningEffort.HIGH -> "高"
    ReasoningEffort.XHIGH -> "很高"
    ReasoningEffort.MAX -> "最高"
}
