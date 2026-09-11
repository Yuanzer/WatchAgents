package com.watchagents.wa.ui.watch

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchagents.wa.data.model.AppearanceThemeMode
import com.watchagents.wa.data.model.Model
import com.watchagents.wa.data.model.OpenAiCompatibleProviderSetting
import com.watchagents.wa.data.model.WatchRotaryDirection
import com.watchagents.wa.data.model.WatchScreenShape
import com.watchagents.wa.data.repository.AgentMemoryRepository
import com.watchagents.wa.data.repository.ProviderRepository
import com.watchagents.wa.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface WatchSettingsActions {
    fun onBack()

    /** 打开推理等级弹窗（与聊天页共用）。 */
    fun onToggleThinking()
}

/**
 * 相册/文档是否已授权。
 *
 * 权限集合的唯一事实来源在 [com.watchagents.wa.agent.tool.WatchLocalFileTools.readPermissions]：
 * Android 13 用 READ_MEDIA_IMAGES，Android 14+ 还要兼容"仅选中的照片"
 * （READ_MEDIA_VISUAL_USER_SELECTED）。这里只做委派，避免两处判断不一致。
 */
internal fun hasWatchMediaPermission(context: Context): Boolean =
    com.watchagents.wa.agent.tool.WatchLocalFileTools.hasReadPermission(context)

/**
 * 圆屏无顶栏版设置页：返回按钮收入左上内切圆；
 * 含外观(深/浅/跟随系统 + 屏幕形状)、DeepSeek Key、模型、思考程度、记忆、本地文件与媒体授权。
 *
 * 行不做动态收窄（真机列表坐标系与窗口圆有系统级偏移，动态方案已移除），
 * 改为静态安全策略：列表内容上避让顶角按钮、下留出安全距
 * （圆屏底部固定 60dp 保证最末行的文字完整位于圆内；方屏/长条屏按布局几何给出）。
 */
@Composable
internal fun WatchSettingsScreen(
    appContext: Context,
    state: WatchAgentState,
    actions: WatchSettingsActions,
    themeMode: AppearanceThemeMode,
    onThemeModeChange: (AppearanceThemeMode) -> Unit,
    screenShape: WatchScreenShape,
    onScreenShapeChange: (WatchScreenShape) -> Unit,
    rotaryDirection: WatchRotaryDirection,
    onRotaryDirectionChange: (WatchRotaryDirection) -> Unit,
    volumeKeyRotary: Boolean,
    onVolumeKeyRotaryChange: (Boolean) -> Unit,
    mediaPermissionGranted: Boolean,
    onRequestMediaPermission: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var memoryEnabled by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<Model>>(emptyList()) }
    var providerName by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // 旋转表冠 / 方向键滚动设置列表
    WatchRotaryScrollEffect(listState)

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ProviderRepository.ensureBuiltInsMerged()
            val all = ProviderRepository.allProviders()
            val settings = ProviderRepository.settings()
            // Watch 精简版：设置页固定对应内置 DeepSeek（默认选中/回填 Key）
            val deepseek = all.firstOrNull {
                it.id == com.watchagents.wa.data.provider.BuiltinProviders.DEEPSEEK_ID
            }
            val selProvider = deepseek
                ?: all.firstOrNull { it.id == settings.selectedProviderId && it.isEnabled }
                ?: all.firstOrNull { it.isEnabled }
            providerName = selProvider?.name ?: ""
            models = selProvider?.models.orEmpty()
            apiKey = selProvider?.apiKey ?: ""
            selectedModel = settings.selectedModelId?.takeIf { id ->
                models.any { it.id == id }
            } ?: models.firstOrNull()?.id
            memoryEnabled = AgentMemoryRepository.isEnabled()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val offsets = watchCircleOffsets()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            contentPadding = PaddingValues(
                top = offsets.contentTopPad + 2.dp,
                // 圆屏：静态 60dp 安全距（真机验收值，保证最末行文字完整在圆内）
                // 方屏/长条屏：没有圆弧，按布局几何给出即可，避免底部白留一片
                bottom = if (offsets.kind == WatchScreenKind.ROUND) {
                    60.dp
                } else {
                    offsets.contentBottomPad
                },
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SettingCard {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("外观", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AppearanceThemeMode.entries.forEach { mode ->
                                val selected = mode == themeMode
                                Surface(
                                    onClick = { onThemeModeChange(mode) },
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    },
                                    contentColor = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(1.dp, watchGlassBorderColor()),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        mode.watchLabel(),
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 7.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            "深色模式对 OLED 圆形屏更省电",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        // 屏幕形状：手表有圆屏/方屏/长条屏，自动判定不准时手动锁定
                        Text(
                            "屏幕形状",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            WatchScreenShape.entries.forEach { shape ->
                                ChoiceChip(
                                    label = shape.watchLabel(),
                                    selected = shape == screenShape,
                                    onClick = { onScreenShapeChange(shape) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Text(
                            "列表上下留白过多选「方形」；圆屏上按钮被切边选「圆形」。",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        // 表冠方向：各家手表"顺时针=往下滚"的定义不统一，方向反了切这里
                        Text(
                            "表冠方向",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            WatchRotaryDirection.entries.forEach { direction ->
                                ChoiceChip(
                                    label = direction.watchLabel(),
                                    selected = direction == rotaryDirection,
                                    onClick = { onRotaryDirectionChange(direction) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Text(
                            "转表冠时列表滚动方向反了就选「反向」。",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        // 音量键兜底：少数品牌把表冠映射成音量键，默认关（开了会占用音量键）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "音量键滚列表",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "表冠转了没反应的手表才需要打开（会占用 App 内音量键）",
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = volumeKeyRotary,
                                onCheckedChange = onVolumeKeyRotaryChange,
                            )
                        }
                    }
                }
            }
            item {
                Column {
                    SectionLabel("DeepSeek Key（必填）")
                    TextFieldCard(
                        value = apiKey,
                        placeholder = "sk-…",
                        onChange = { apiKey = it },
                        hint = if (apiKey.isBlank()) {
                            "当前未配置：不填 Key 无法对话（默认不内置 Key）"
                        } else {
                            "已填写，点下方保存生效；换 Key 直接覆盖即可"
                        },
                    )
                    SaveButton(
                        label = "保存 Key",
                        onClick = {
                            val key = apiKey.trim()
                            scope.launch(Dispatchers.IO) {
                                ProviderRepository.ensureBuiltInsMerged()
                                val provider = ProviderRepository.providerById(com.watchagents.wa.data.provider.BuiltinProviders.DEEPSEEK_ID)
                                    ?: return@launch
                                val updated = when (provider) {
                                    is OpenAiCompatibleProviderSetting -> provider.copy(apiKey = key)
                                    else -> provider
                                }
                                ProviderRepository.updateProvider(updated)
                                // 保存后立刻刷新"是否已配置"状态，首页/聊天页的引导条同步消失
                                withContext(Dispatchers.Main) { state.refreshApiKeyState() }
                            }
                        },
                    )
                }
            }
            item { SectionLabel("模型（$providerName）") }
            if (models.isNotEmpty()) {
                items(models, key = { it.id }) { model ->
                    val display = if (model.modelId == model.displayName) {
                        model.modelId
                    } else {
                        model.displayName
                    }
                    val selected = selectedModel == model.id
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, watchGlassBorderColor()),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedModel = model.id
                                scope.launch(Dispatchers.IO) {
                                    RuntimeConfigRepository.setSelectedProviderId(
                                        com.watchagents.wa.data.provider.BuiltinProviders.DEEPSEEK_ID,
                                    )
                                    RuntimeConfigRepository.setSelectedModelId(model.id)
                                }
                            },
                    ) {
                        Text(
                            text = display + if (selected) " ✓" else "",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            } else {
                item {
                    Text(
                        "（暂无可用模型）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { SectionLabel("思考程度") }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, watchGlassBorderColor()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onToggleThinking() },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("推理等级", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "当前：${state.reasoningEffort.label()}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "›",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item {
                Text(
                    "选「不思考」最省电；复杂问题可临时调高。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item { SectionLabel("记忆") }
            item {
                SettingCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("长期记忆", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "值得记住的事沉淀为记忆",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = memoryEnabled,
                            onCheckedChange = { checked ->
                                memoryEnabled = checked
                                scope.launch(Dispatchers.IO) {
                                    AgentMemoryRepository.setEnabled(checked)
                                }
                            },
                        )
                    }
                }
            }
            item { SectionLabel("本地文件与照片") }
            item {
                SettingCard {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "读取照片 / 文档",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    if (mediaPermissionGranted) "已授权" else "未授权（授权后可读取相册/文档中的文字内容）",
                                    fontSize = 11.sp,
                                    color = if (mediaPermissionGranted) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (!mediaPermissionGranted) {
                                Surface(
                                    onClick = onRequestMediaPermission,
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ) {
                                    Text(
                                        "授权",
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            "工作区文件（对话中说\"存成文件/读文件/改文件\"即写入 App 私有沙盒，始终可用）；" +
                                "图片以元数据 + 文件名可读，照片内文字需模型支持视觉才能直接看懂。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // 左上角：返回（圆屏收入内切圆；方屏贴角）
        WatchRoundButton(
            onClick = actions::onBack,
            glyph = "‹",
            glyphSize = 28,
            size = offsets.buttonSize,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = offsets.topStart.first, y = offsets.topStart.second),
        )
    }
}

private fun AppearanceThemeMode.watchLabel(): String = when (this) {
    AppearanceThemeMode.SYSTEM -> "跟随系统"
    AppearanceThemeMode.LIGHT -> "浅色"
    AppearanceThemeMode.DARK -> "深色"
}

private fun WatchScreenShape.watchLabel(): String = when (this) {
    WatchScreenShape.AUTO -> "自动"
    WatchScreenShape.ROUND -> "圆形"
    WatchScreenShape.SQUARE -> "方形"
}

private fun WatchRotaryDirection.watchLabel(): String = when (this) {
    WatchRotaryDirection.STANDARD -> "标准"
    WatchRotaryDirection.REVERSED -> "反向"
}

/** 设置页的等宽选项按钮（外观相关三选一都用它，保证样式一致）。 */
@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, watchGlassBorderColor()),
        modifier = modifier,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 7.dp),
        )
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, watchGlassBorderColor()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
private fun TextFieldCard(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    hint: String = "用系统键盘粘贴；也可稍后由 App 内置默认 Key",
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, watchGlassBorderColor()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isBlank()) {
                            Text(
                                placeholder,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Text(
                hint,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SaveButton(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}
