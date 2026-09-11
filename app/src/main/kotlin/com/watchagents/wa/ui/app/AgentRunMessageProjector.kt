package com.watchagents.wa.ui.app

import android.os.SystemClock
import com.watchagents.wa.agent.runtime.AgentEvent
import com.watchagents.wa.ui.model.AgentChatMessageUi
import com.watchagents.wa.ui.model.AgentMessageUi
import com.watchagents.wa.ui.model.ThinkingMessageUi
import com.watchagents.wa.ui.model.ToolActivityMessageUi
import com.watchagents.wa.ui.model.ToolActivityStatusUi

internal class AgentRunMessageProjector(
    private val nowElapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val thinkingStartedAt = mutableMapOf<String, Long>()

    fun appendTextDelta(
        runId: String,
        round: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty()) return messages

        val assistantId = assistantMessageId(runId, round)
        var updated = false
        val next = messages.map { message ->
            if (message is AgentMessageUi && message.id == assistantId) {
                updated = true
                message.copy(
                    content = message.content + delta,
                    isStreaming = true,
                    renderMarkdown = false,
                )
            } else {
                message
            }
        }
        if (updated) return next

        return next + AgentMessageUi(
            id = assistantId,
            content = delta,
            isStreaming = true,
            renderMarkdown = false,
        )
    }

    fun appendReasoningDelta(
        runId: String,
        round: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty()) return messages

        val thinkingId = thinkingMessageId(runId, round)
        val elapsedSeconds = elapsedSeconds(thinkingId)
        var updated = false
        val next = messages.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                updated = true
                message.copy(
                    content = message.content + delta,
                    isStreaming = true,
                    elapsedSeconds = elapsedSeconds,
                    collapsed = false,
                )
            } else {
                message
            }
        }
        if (updated) return next

        return next.insertBeforeAssistant(
            runId = runId,
            round = round,
            message = ThinkingMessageUi(
                id = thinkingId,
                content = delta,
                isStreaming = true,
                elapsedSeconds = elapsedSeconds,
                collapsed = false,
            )
        )
    }

    fun ensureCompletedThinking(
        runId: String,
        round: Int,
        content: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val thinkingId = thinkingMessageId(runId, round)
        if (messages.any { it is ThinkingMessageUi && it.id == thinkingId }) {
            return finalizeThinkingRound(runId, round, messages)
        }

        return messages.insertBeforeAssistant(
            runId = runId,
            round = round,
            message = ThinkingMessageUi(
                id = thinkingId,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds(thinkingId),
                collapsed = true,
            )
        )
    }

    fun finalizeThinking(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ThinkingMessageUi && message.id.startsWith("$runId-thinking-")) {
                message.finished()
            } else {
                message
            }
        }

    fun finalizeThinkingRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val thinkingId = thinkingMessageId(runId, round)
        return messages.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                message.finished()
            } else {
                message
            }
        }
    }

    fun finalizeText(
        runId: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is AgentMessageUi && message.id.startsWith(assistantMessagePrefix(runId))) {
                message.copy(content = message.content.trimEnd(), isStreaming = false)
            } else {
                message
            }
        }

    fun finalizeTextRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 定稿时裁掉尾部空白：模型输出常以换行收尾，Markdown 渲染会把每个尾部
        // 换行节点变成一段固定间距，在正文与后续工具卡片之间形成莫名的空行。
        val assistantId = assistantMessageId(runId, round)
        return messages.map { message ->
            if (message is AgentMessageUi && message.id == assistantId) {
                message.copy(content = message.content.trimEnd(), isStreaming = false)
            } else {
                message
            }
        }
    }

    fun startTool(
        runId: String,
        event: AgentEvent.ToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 同一轮内文本一定先于工具执行到达（工具要等 assistant 消息完整返回后才启动），
        // 工具行直接追加在该轮正文之后，不能插到正文前面。
        val message = ToolActivityMessageUi(
            id = toolActivityMessageId(runId, event.round, event.toolCallId),
            toolName = event.name,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = event.argsPreview,
            command = event.command,
        )
        if (messages.any { it.id == message.id }) return messages
        return messages + message
    }

    fun finishTool(
        runId: String,
        event: AgentEvent.ToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        // success 字段优先；旧版本 Runtime/归档事件缺省时回退到摘要文本判断
        val status = when (event.success) {
            true -> ToolActivityStatusUi.Success
            false -> ToolActivityStatusUi.Failed
            null -> if (event.resultSummary.contains("ok=false", ignoreCase = true)) {
                ToolActivityStatusUi.Failed
            } else {
                ToolActivityStatusUi.Success
            }
        }
        val targetIndex = messages.indexOfLast { it is ToolActivityMessageUi && it.id == targetId }
        if (targetIndex < 0) return messages

        return messages.mapIndexed { index, message ->
            if (index == targetIndex && message is ToolActivityMessageUi) {
                message.copy(
                    status = status,
                    resultSummary = event.resultSummary,
                    imageCount = event.imageCount,
                )
            } else {
                message
            }
        }
    }

    fun startHostedTool(
        runId: String,
        event: AgentEvent.HostedToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val message = ToolActivityMessageUi(
            id = toolActivityMessageId(runId, event.round, event.toolCallId),
            toolName = event.name,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "",
        )
        return if (messages.any { it.id == message.id }) messages else messages + message
    }

    fun finishHostedTool(
        runId: String,
        event: AgentEvent.HostedToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        return messages.map { message ->
            if (message is ToolActivityMessageUi && message.id == targetId) {
                message.copy(
                    status = if (event.success) ToolActivityStatusUi.Success else ToolActivityStatusUi.Failed,
                    resultSummary = null,
                )
            } else {
                message
            }
        }
    }

    fun failRunningTools(
        reason: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                message.copy(
                    status = ToolActivityStatusUi.Failed,
                    resultSummary = reason.take(MAX_TOOL_RESULT_PREVIEW_CHARS),
                )
            } else {
                message
            }
        }

    fun clearRun(runId: String) {
        thinkingStartedAt.keys.removeAll { it.startsWith("$runId-thinking-") }
    }

    private fun ThinkingMessageUi.finished(): ThinkingMessageUi =
        copy(
            isStreaming = false,
            elapsedSeconds = thinkingStartedAt[id]?.let { startedAt ->
                ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
            } ?: elapsedSeconds,
            collapsed = true,
        )

    private fun elapsedSeconds(thinkingId: String): Int {
        val startedAt = thinkingStartedAt.getOrPut(thinkingId, nowElapsedRealtime)
        return ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
    }

    private fun List<AgentChatMessageUi>.insertBeforeAssistant(
        runId: String,
        round: Int,
        message: AgentChatMessageUi,
    ): List<AgentChatMessageUi> {
        val assistantIndex = indexOfFirst {
            it is AgentMessageUi && it.id == assistantMessageId(runId, round)
        }
        return if (assistantIndex >= 0) {
            toMutableList().apply { add(assistantIndex, message) }
        } else {
            this + message
        }
    }

    private fun assistantMessageId(runId: String, round: Int): String =
        "${assistantMessagePrefix(runId)}$round"

    private fun assistantMessagePrefix(runId: String): String =
        "assistant-$runId-"

    private fun thinkingMessageId(runId: String, round: Int): String =
        "$runId-thinking-$round"

    private fun toolActivityMessageId(runId: String, round: Int, toolCallId: String): String =
        "$runId-tool-$round-${toolCallId.ifBlank { "unknown" }}"
}

private const val MAX_TOOL_RESULT_PREVIEW_CHARS = 48
