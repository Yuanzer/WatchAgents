package com.watchagents.wa.ui.watch

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.watchagents.wa.agent.memory.AgentMemoryContext
import com.watchagents.wa.agent.memory.AgentMemoryContextBuilder
import com.watchagents.wa.agent.model.AgentModelClient
import com.watchagents.wa.agent.runtime.AgentEvent
import com.watchagents.wa.agent.skill.SkillContext
import com.watchagents.wa.agent.skill.SkillRuntime
import com.watchagents.wa.agent.tool.WatchToolExecutor
import com.watchagents.wa.config.Prefs
import com.watchagents.wa.data.model.ReasoningEffort
import com.watchagents.wa.data.repository.AgentMemoryRepository
import com.watchagents.wa.data.repository.RuntimeConfigRepository
import com.watchagents.wa.ui.app.AgentConversationStore
import com.watchagents.wa.ui.model.AgentChatHomeUiState
import com.watchagents.wa.ui.model.AgentChatMessageUi
import com.watchagents.wa.ui.model.AgentMessageUi
import com.watchagents.wa.ui.model.SystemNoticeCode
import com.watchagents.wa.ui.model.SystemNoticeMessageUi
import com.watchagents.wa.ui.model.ThinkingMessageUi
import com.watchagents.wa.ui.model.UserMessageUi
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watch4 精简版会话状态容器：进程内直调 AgentModelClient.complete。
 *
 * 不依赖 Xposed / Messenger / overlay / 无障碍：发消息 → 当前 Provider 配置 →
 * DeepSeek 直连流式 → AgentEvent 投影到消息列表 → Room 持久化。
 */
internal class WatchAgentState(
    private val appContext: Context,
) {
    private val runMessageProjector = com.watchagents.wa.ui.app.AgentRunMessageProjector()
    private val conversationStore = AgentConversationStore
    private var toolExecutor: WatchToolExecutor? = null

    private val initial = AgentConversationStore.load(appContext)

    var selectedConversationId: String? by mutableStateOf(initial.selectedConversationId)
        private set
    var conversationsById: Map<String, AgentChatHomeUiState> by mutableStateOf(initial.conversationsById)
        private set
    var titles: Map<String, String> by mutableStateOf(initial.titles)
        private set
    var updatedAt: Map<String, Long> by mutableStateOf(initial.updatedAt)
        private set

    var input: String by mutableStateOf("")
    var isStreaming: Boolean by mutableStateOf(false)
        private set
    var messages: List<AgentChatMessageUi> by mutableStateOf(
        initial.selectedConversationId?.let { initial.conversationsById[it]?.messages } ?: emptyList(),
    )
        private set
    var history: List<AgentModelClient.ConversationMessage> by mutableStateOf(
        initial.selectedConversationId?.let { initial.conversationsById[it]?.history } ?: emptyList(),
    )
        private set
    var reasoningEffort: ReasoningEffort by mutableStateOf(
        initial.selectedConversationId
            ?.let { initial.conversationsById[it]?.reasoningEffort }
            ?.takeUnless { it == ReasoningEffort.DEFAULT }
            ?: globalDefaultEffort(),
    )
        private set

    /**
     * 当前选中的 Provider 是否已填 API Key。
     *
     * 3.3 起默认不内置 Key，所以首启就是"未配置"状态：首页空态与聊天页会给出明确引导，
     * 避免用户对着一句话"请求失败"猜原因。初值取 true（乐观），异步探测完再纠正，
     * 这样已配置的用户不会看到一闪而过的误报。
     */
    var apiKeyConfigured: Boolean by mutableStateOf(true)
        private set

    /** 用户点了发送但没配 Key：在输入条上方显示一次明确提示（不清空已输入内容）。 */
    var showApiKeyHint: Boolean by mutableStateOf(false)
        private set

    private var currentRunJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        refreshApiKeyState()
    }

    /** 重新探测当前 Provider 是否已有可用 Key（设置页保存后、发送失败后调用）。 */
    fun refreshApiKeyState() {
        scope.launch {
            val configured = runCatching {
                RuntimeConfigRepository.currentRuntimeConfig()?.apiKey?.isNotBlank() == true
            }.getOrDefault(true)
            apiKeyConfigured = configured
            if (configured) showApiKeyHint = false
        }
    }

    /**
     * 首页会话列表（按最近更新排序）。
     *
     * 用 [derivedStateOf] 缓存：只有会话集合/标题/时间真正变化时才重排，
     * 避免首页每次重组（转场动画、主题变化等）都重新 map+sorted。
     */
    val conversationList: List<Triple<String, String, Long>> by derivedStateOf {
        conversationsById.keys
            .map { id -> Triple(id, titles[id].orEmpty(), updatedAt[id] ?: 0L) }
            .sortedByDescending { it.third }
    }

    fun onInputChange(value: String) {
        input = value
    }

    fun onReasoningEffortChange(effort: ReasoningEffort) {
        reasoningEffort = effort
        // 手动选择等级即显式控制思考开关与全局默认档位，避免与全局默认冲突
        Prefs.setEnabled(Prefs.Keys.AGENT_THINKING_ENABLED, effort != ReasoningEffort.OFF)
        Prefs.putString(Prefs.Keys.AGENT_THINKING_EFFORT, effort.wireValue)
        selectedConversationId?.let { id ->
            conversationsById = conversationsById + (id to conversationsById.getValue(id).copy(
                thinkingEnabled = effort != ReasoningEffort.OFF,
                reasoningEffort = effort,
            ))
        }
    }

    /** 全局默认思考档位：设置页/聊天弹窗选择的档位持久化，新会话继承。 */
    private fun globalDefaultEffort(): ReasoningEffort {
        val stored = Prefs.getString(Prefs.Keys.AGENT_THINKING_EFFORT)
        ReasoningEffort.fromWireValue(stored)?.let { return it }
        return if (Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)) {
            ReasoningEffort.DEFAULT
        } else {
            ReasoningEffort.OFF
        }
    }

    fun selectConversation(id: String) {
        if (selectedConversationId == id) return
        persistCurrentLocked()
        selectedConversationId = id
        val state = conversationsById[id]
        messages = state?.messages ?: emptyList()
        history = state?.history ?: emptyList()
        // 会话未显式设过档位(DEFAULT)时跟随全局默认，避免设置页档位不生效
        reasoningEffort = state?.reasoningEffort
            ?.takeUnless { it == ReasoningEffort.DEFAULT }
            ?: globalDefaultEffort()
        input = state?.input ?: ""
    }

    fun createConversation() {
        persistCurrentLocked()
        val id = "conversation-${UUID.randomUUID()}"
        val defaultEffort = globalDefaultEffort()
        selectedConversationId = id
        conversationsById = conversationsById + (id to emptyChatState().copy(
            thinkingEnabled = defaultEffort != ReasoningEffort.OFF,
            reasoningEffort = defaultEffort,
        ))
        titles = titles + (id to "")
        updatedAt = updatedAt + (id to System.currentTimeMillis())
        messages = emptyList()
        history = emptyList()
        reasoningEffort = defaultEffort
        input = ""
        persistAll()
    }

    fun deleteConversation(id: String) {
        conversationsById = conversationsById - id
        titles = titles - id
        updatedAt = updatedAt - id
        if (selectedConversationId == id) {
            selectedConversationId = conversationsById.keys.firstOrNull()
            val state = selectedConversationId?.let { conversationsById[it] }
            messages = state?.messages ?: emptyList()
            history = state?.history ?: emptyList()
        }
        persistAll()
    }

    fun stopStreaming() {
        currentRunJob?.cancel()
        currentRunJob = null
        isStreaming = false
        markAssistantComplete()
        persistAll()
    }

    /** 发送消息：进程内直调 DeepSeek（含思考等级/记忆/技能上下文）。 */
    fun submit() {
        val prompt = input.trim()
        if (prompt.isBlank() || isStreaming) return
        // 未配置 Key：不发请求、不清空输入，直接给出去哪儿填的引导
        if (!apiKeyConfigured) {
            showApiKeyHint = true
            refreshApiKeyState()
            return
        }
        val conversationId = selectedConversationId ?: createConversationAndReturnId()
        val state = conversationsById.getValue(conversationId)
        val isNew = state.messages.isEmpty()
        val effort = reasoningEffort
        val runId = "run-${UUID.randomUUID()}"

        val userMessage = UserMessageUi(
            id = "user-$runId",
            content = prompt,
        )
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(prompt, emptyList())
        val nextMessages = state.messages + userMessage
        updateConversationState(
            conversationId,
            state.copy(
                messages = nextMessages,
                history = state.history + userHistoryMessage,
                isStreaming = true,
                input = "",
            ),
        )
        messages = nextMessages
        history = state.history + userHistoryMessage
        input = ""
        isStreaming = true
        persistAll()

        currentRunJob?.cancel()
        currentRunJob = scope.launch {
            try {
                val config = RuntimeConfigRepository.currentRuntimeConfig()
                if (config == null) {
                    failRun(runId, "请先在设置中配置 API Key 并选择模型")
                    return@launch
                }
                val thinkingAllowed = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
                val permittedEffort = if (thinkingAllowed) effort else ReasoningEffort.OFF
                val finalConfig = config.copy(
                    thinkingEnabled = permittedEffort.enablesReasoning,
                    reasoningEffort = permittedEffort,
                    terminalTools = false,
                    // 联网检索（web_search/fetch_url）在 Watch 上可用
                    browserTools = true,
                    deviceDirectTools = false,
                    deviceSensitiveReadTools = false,
                    deviceSensitiveActionTools = false,
                )
                val memoryEnabled = AgentMemoryRepository.isEnabled()
                val memoryContext: AgentMemoryContext = if (memoryEnabled) {
                    AgentMemoryContextBuilder.build(
                        snapshot = AgentMemoryRepository.snapshot(),
                        contextWindow = finalConfig.contextWindow,
                        query = prompt,
                    )
                } else {
                    AgentMemoryContext.DISABLED
                }
                val skills = SkillRuntime.createIndexService(appContext).listInstalledSkills()
                val skillContext = SkillContext(installedSkills = skills)
                val executor = toolExecutor
                    ?: WatchToolExecutor(appContext).also { toolExecutor = it }

                val result = AgentModelClient.complete(
                    config = finalConfig,
                    prompt = prompt,
                    toolExecutor = executor,
                    images = emptyList(),
                    history = state.history + userHistoryMessage,
                    skillContext = skillContext,
                    memoryContext = memoryContext,
                    localFileTools = true,
                    onEvent = { event -> onRunEvent(runId, conversationId, event) },
                )
                finalizeRun(runId, conversationId, result.content)
                if (isNew) {
                    generateTitleInBackground(conversationId, prompt)
                }
            } catch (cancelled: com.watchagents.wa.agent.runtime.AgentRunCancelledException) {
                // 用户停止：保留已生成部分
            } catch (throwable: Throwable) {
                failRun(runId, throwable.message ?: throwable.javaClass.simpleName)
            }
        }
    }

    private fun createConversationAndReturnId(): String {
        createConversation()
        return selectedConversationId ?: error("会话创建失败")
    }

    private fun onRunEvent(runId: String, conversationId: String, event: AgentEvent) {
        val state = conversationsById[conversationId] ?: return
        var next = state.messages
        when (event) {
            is AgentEvent.AssistantBlockDelta -> when (event.kind) {
                AgentEvent.AssistantBlockKind.TEXT -> next = runMessageProjector.appendTextDelta(
                    runId, event.round, event.delta, next,
                )
                AgentEvent.AssistantBlockKind.THINKING -> next = runMessageProjector.appendReasoningDelta(
                    runId, event.round, event.delta, next,
                )
                AgentEvent.AssistantBlockKind.TOOL_CALL -> Unit
            }
            is AgentEvent.ToolStarted -> next = runMessageProjector.startTool(runId, event, next)
            is AgentEvent.ToolFinished -> next = runMessageProjector.finishTool(runId, event, next)
            is AgentEvent.RunStarted -> Unit
            is AgentEvent.RunFinished -> Unit
            else -> Unit
        }
        if (next !== state.messages) {
            updateConversationState(conversationId, state.copy(messages = next))
            if (conversationId == selectedConversationId) messages = next
        }
    }

    private fun finalizeRun(runId: String, conversationId: String, content: String) {
        val state = conversationsById[conversationId] ?: return
        val next = runMessageProjector
            .finalizeText(runId, state.messages)
        val now = System.currentTimeMillis()
        updateConversationState(
            conversationId,
            state.copy(
                messages = next,
                isStreaming = false,
                history = state.history + AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = content,
                ),
            ),
        )
        updatedAt = updatedAt + (conversationId to now)
        if (conversationId == selectedConversationId) {
            messages = next
            isStreaming = false
        }
        currentRunJob = null
        persistAll()
    }

    private fun failRun(runId: String, reason: String) {
        // 兜底：任何路径上出现"缺 Key"都翻转成引导态（例如异步探测还没跑完就发送）
        if (reason.contains("API Key", ignoreCase = true)) {
            apiKeyConfigured = false
            showApiKeyHint = true
        }
        val id = selectedConversationId ?: return
        val state = conversationsById[id] ?: return
        val failed = runMessageProjector.failRunningTools(reason, state.messages)
        val next = failed + SystemNoticeMessageUi(
            id = "notice-$runId",
            code = SystemNoticeCode.RuntimeFailed,
            detail = reason,
        )
        updateConversationState(id, state.copy(messages = next, isStreaming = false))
        messages = next
        isStreaming = false
        currentRunJob = null
        persistAll()
    }

    private fun markAssistantComplete() {
        val id = selectedConversationId ?: return
        val state = conversationsById[id] ?: return
        val next = state.messages.map { message ->
            if (message is AgentMessageUi && message.isStreaming) {
                message.copy(isStreaming = false)
            } else if (message is ThinkingMessageUi && message.isStreaming) {
                message.copy(isStreaming = false, collapsed = true)
            } else {
                message
            }
        }
        updateConversationState(id, state.copy(messages = next))
        messages = next
    }

    private fun generateTitleInBackground(conversationId: String, prompt: String) {
        scope.launch {
            val config = RuntimeConfigRepository.currentRuntimeConfig()
            val generated = config?.let {
                AgentModelClient.generateConversationTitle(it, prompt)
            }
            if (generated != null) {
                titles = titles + (conversationId to generated)
                persistAll()
            }
        }
    }

    private fun updateConversationState(id: String, state: AgentChatHomeUiState) {
        conversationsById = conversationsById + (id to state)
        updatedAt = updatedAt + (id to System.currentTimeMillis())
    }

    private fun emptyChatState(): AgentChatHomeUiState = AgentChatHomeUiState(
        messages = emptyList(),
        history = emptyList(),
        input = "",
        isStreaming = false,
        thinkingEnabled = false,
        reasoningEffort = ReasoningEffort.DEFAULT,
    )

    private fun persistCurrentLocked() {
        val id = selectedConversationId ?: return
        val state = conversationsById[id] ?: return
        conversationsById = conversationsById + (id to state.copy(input = input))
    }

    private fun persistAll() {
        scope.launch {
            withContext(Dispatchers.IO) {
                conversationStore.save(
                    context = appContext,
                    selectedConversationId = selectedConversationId,
                    conversationsById = conversationsById,
                    titles = titles,
                    updatedAt = updatedAt,
                )
            }
        }
    }
}

