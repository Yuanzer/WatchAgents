package com.watchagents.wa.agent.model

import com.watchagents.wa.agent.runtime.AgentRunController
import com.watchagents.wa.agent.runtime.AgentTokenUsage
import com.watchagents.wa.data.model.OpenAiEndpointMode
import java.io.BufferedReader
import java.io.InputStreamReader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiResponsesProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "openai_responses"

    override val capabilities = ProviderCapabilities(
        endpoint = EndpointKind.RESPONSES,
        streamingText = true,
        streamingToolCalls = true,
        imageInput = true,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = true,
    )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val config = request.config
        require(config.openAiEndpointMode == OpenAiEndpointMode.RESPONSES) {
            "当前 Provider 未配置为 Responses API"
        }
        val body = buildRequestJson(config, request.messages, request.tools)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) add("Authorization", "Bearer ${config.apiKey}")
            }
            .also { CustomHeaderFilter.mergeInto(it, config.customHeaders) }
            .build()
        val httpRequest = Request.Builder()
            .url(ProviderUrls.openAiResponsesUrl(config.baseUrl))
            .headers(headers)
            .post(body)
            .build()
        val call = AgentHttpClient.client.newCall(httpRequest)
        val binding = runController.register(call::cancel)

        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                runController.throwIfCancelled()
                if (!response.isSuccessful) {
                    error("模型接口返回 HTTP ${response.code}：${response.body.string().compactError()}")
                }
                val assistant = readStreamingResponse(
                    stream = response.body.byteStream(),
                    runController = runController,
                    onEvent = onEvent,
                )
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
                return ProviderResponse(assistant)
            }
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        } finally {
            binding.close()
        }
    }

    internal fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
    ): JSONObject = ResponsesRequestBuilder.build(config, messages, tools)

    private fun readStreamingResponse(
        stream: java.io.InputStream?,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): JSONObject {
        if (stream == null) error("模型接口未返回响应流")
        val streamedText = StringBuilder()
        val streamedReasoning = StringBuilder()
        val toolCalls = linkedMapOf<String, StreamingFunctionCall>()
        val hostedTools = linkedMapOf<String, Boolean>()
        var textBlockIndex: Int? = null
        var reasoningBlockIndex: Int? = null
        var nextBlockIndex = 0
        var terminal: JSONObject? = null
        var terminalType: String? = null
        var sawEvent = false

        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            val dataLines = mutableListOf<String>()
            fun consumeFrame() {
                if (dataLines.isEmpty()) return
                val payload = dataLines.joinToString("\n").trim()
                dataLines.clear()
                if (payload.isBlank() || payload == "[DONE]") return
                sawEvent = true
                val event = JSONObject(payload)
                throwEventError(event)
                when (val type = event.optString("type")) {
                    "response.output_text.delta" -> {
                        val delta = event.optString("delta")
                        if (delta.isNotEmpty()) {
                            val block = textBlockIndex ?: nextBlockIndex++.also {
                                textBlockIndex = it
                                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, it))
                            }
                            streamedText.append(delta)
                            onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, block, delta))
                        }
                    }
                    "response.reasoning_summary_text.delta",
                    "response.reasoning_text.delta" -> {
                        val delta = event.optString("delta")
                        if (delta.isNotEmpty()) {
                            val block = reasoningBlockIndex ?: nextBlockIndex++.also {
                                reasoningBlockIndex = it
                                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, it))
                            }
                            streamedReasoning.append(delta)
                            onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, block, delta))
                        }
                    }
                    "response.output_item.added" -> {
                        val item = event.optJSONObject("item") ?: JSONObject()
                        val itemId = item.optString("id").ifBlank { "item_${event.optInt("output_index", 0)}" }
                        when (item.optString("type")) {
                            "function_call" -> {
                                val call = StreamingFunctionCall(
                                    itemId = itemId,
                                    contentIndex = nextBlockIndex++,
                                    callId = item.optString("call_id"),
                                    name = item.optString("name"),
                                    arguments = StringBuilder(item.optString("arguments")),
                                )
                                toolCalls[itemId] = call
                                onEvent(
                                    ProviderEvent.BlockStart(
                                        AssistantBlockKind.TOOL_CALL,
                                        call.contentIndex,
                                        blockId = call.callId.ifBlank { null },
                                        name = call.name.ifBlank { null },
                                    ),
                                )
                            }
                            "web_search_call" -> {
                                hostedTools[itemId] = false
                                onEvent(ProviderEvent.HostedToolStarted(itemId, "网页搜索"))
                            }
                        }
                    }
                    "response.function_call_arguments.delta" -> {
                        val itemId = event.optString("item_id")
                        val call = toolCalls[itemId] ?: return
                        val delta = event.optString("delta")
                        call.arguments.append(delta)
                        if (delta.isNotEmpty()) {
                            onEvent(
                                ProviderEvent.BlockDelta(
                                    AssistantBlockKind.TOOL_CALL,
                                    call.contentIndex,
                                    delta,
                                ),
                            )
                        }
                    }
                    "response.function_call_arguments.done" -> {
                        val call = toolCalls[event.optString("item_id")] ?: return
                        if (event.has("arguments")) {
                            call.arguments.clear()
                            call.arguments.append(event.optString("arguments"))
                        }
                    }
                    "response.output_item.done" -> {
                        val item = event.optJSONObject("item") ?: JSONObject()
                        val itemId = item.optString("id").ifBlank { "item_${event.optInt("output_index", 0)}" }
                        when (item.optString("type")) {
                            "function_call" -> toolCalls[itemId]?.let { call ->
                                call.callId = item.optString("call_id").ifBlank { call.callId }
                                call.name = item.optString("name").ifBlank { call.name }
                                if (item.has("arguments")) {
                                    call.arguments.clear()
                                    call.arguments.append(item.optString("arguments"))
                                }
                                if (!call.ended) {
                                    call.ended = true
                                    onEvent(call.endEvent())
                                }
                            }
                            "web_search_call" -> {
                                hostedTools[itemId] = true
                                onEvent(
                                    ProviderEvent.HostedToolFinished(
                                        itemId,
                                        "网页搜索",
                                        item.optString("status") != "failed",
                                    ),
                                )
                            }
                        }
                    }
                    "response.completed", "response.incomplete", "response.failed" -> {
                        terminalType = type
                        terminal = event.optJSONObject("response") ?: event
                    }
                    "error" -> throwTopLevelEventError(event)
                }
            }

            while (true) {
                runController.throwIfCancelled()
                val line = reader.readLine()
                if (line == null) {
                    consumeFrame()
                    break
                }
                if (line.isBlank()) {
                    consumeFrame()
                } else if (line.startsWith("data:")) {
                    dataLines += line.removePrefix("data:").trimStart()
                }
            }
        }

        if (!sawEvent) error("模型接口未返回 SSE data chunk")
        val finalResponse = terminal ?: error("模型接口 Responses SSE 流缺少合法终止事件")
        if (terminalType == "response.failed") throwResponseFailure(finalResponse)

        val terminalOutput = finalResponse.optJSONArray("output")
        val hasTerminalOutput = terminalOutput != null && terminalOutput.length() > 0
        val output = terminalOutput ?: JSONArray()
        val finalResult = if (terminalType == "response.completed" && !hasTerminalOutput) {
            // 部分兼容接口只流式下发正文，终态 output 为空。这里只恢复本轮已经收到的
            // 标准增量；不对非空终态做字段级拼补，也不把本地结果冒充为 opaque items。
            finalOutputFromStream(streamedText, streamedReasoning, toolCalls.values)
        } else {
            extractFinalOutput(output)
        }
        emitMissingSuffix(
            streamed = streamedReasoning,
            authoritative = finalResult.reasoning,
            kind = AssistantBlockKind.THINKING,
            blockIndex = reasoningBlockIndex,
            allocateIndex = { nextBlockIndex++ },
            onStart = { reasoningBlockIndex = it },
            onEvent = onEvent,
        )
        emitMissingSuffix(
            streamed = streamedText,
            authoritative = finalResult.rawText,
            kind = AssistantBlockKind.TEXT,
            blockIndex = textBlockIndex,
            allocateIndex = { nextBlockIndex++ },
            onStart = { textBlockIndex = it },
            onEvent = onEvent,
        )
        reasoningBlockIndex?.let {
            onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.THINKING, it, content = finalResult.reasoning))
        }
        textBlockIndex?.let {
            onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, it, content = finalResult.text))
        }

        toolCalls.values.forEach { call ->
            if (!call.ended) onEvent(call.endEvent())
        }
        finalResult.toolCalls.forEach { finalCall ->
            if (toolCalls.values.none { it.callId == finalCall.callId }) {
                val call = StreamingFunctionCall(
                    itemId = finalCall.itemId,
                    contentIndex = nextBlockIndex++,
                    callId = finalCall.callId,
                    name = finalCall.name,
                    arguments = StringBuilder(finalCall.arguments),
                )
                onEvent(
                    ProviderEvent.BlockStart(
                        AssistantBlockKind.TOOL_CALL,
                        call.contentIndex,
                        call.callId,
                        call.name,
                    ),
                )
                onEvent(call.endEvent())
            }
        }
        hostedTools.filterValues { !it }.forEach { (id, _) ->
            onEvent(ProviderEvent.HostedToolFinished(id, "网页搜索", success = true))
        }

        parseUsage(finalResponse.optJSONObject("usage"))?.let { onEvent(ProviderEvent.Usage(it)) }
        val finishReason = finishReason(terminalType, finalResponse, finalResult.toolCalls.isNotEmpty())
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", finalResult.text)
            .put("reasoning_content", finalResult.reasoning)
            .put("finish_reason", finishReason)
        if (finalResult.toolCalls.isNotEmpty()) {
            assistant.put(
                "tool_calls",
                JSONArray().also { calls ->
                    finalResult.toolCalls.forEach { call -> calls.put(call.toChatToolCall()) }
                },
            )
        }
        if (hasTerminalOutput) {
            ResponsesEphemeralState.attachOutputItems(assistant, output)
        }
        return assistant
    }

    private fun finalOutputFromStream(
        text: StringBuilder,
        reasoning: StringBuilder,
        calls: Collection<StreamingFunctionCall>,
    ): FinalOutput = FinalOutput(
        text = text.toString(),
        rawText = text.toString(),
        reasoning = reasoning.toString(),
        toolCalls = calls.map { call ->
            FinalFunctionCall(
                itemId = call.itemId,
                callId = call.callId,
                name = call.name,
                arguments = call.arguments.toString().ifBlank { "{}" },
            )
        },
    )

    private fun extractFinalOutput(output: JSONArray): FinalOutput {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val annotations = mutableListOf<CitationAnnotation>()
        val calls = mutableListOf<FinalFunctionCall>()
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: JSONArray()
                    for (contentIndex in 0 until content.length()) {
                        val part = content.optJSONObject(contentIndex) ?: continue
                        if (part.optString("type") != "output_text") continue
                        val offset = text.length
                        text.append(part.optString("text"))
                        val partAnnotations = part.optJSONArray("annotations") ?: continue
                        for (annotationIndex in 0 until partAnnotations.length()) {
                            val annotation = partAnnotations.optJSONObject(annotationIndex) ?: continue
                            val citation = annotation.optJSONObject("url_citation") ?: annotation
                            if (citation.optString("type") == "url_citation" || citation.has("url")) {
                                annotations += CitationAnnotation(
                                    start = citation.optInt("start_index", -1).takeIf { it >= 0 }?.plus(offset),
                                    end = citation.optInt("end_index", -1).takeIf { it >= 0 }?.plus(offset),
                                    url = citation.optString("url"),
                                    title = citation.optString("title"),
                                )
                            }
                        }
                    }
                }
                "reasoning" -> {
                    val summary = item.optJSONArray("summary") ?: JSONArray()
                    for (summaryIndex in 0 until summary.length()) {
                        val part = summary.optJSONObject(summaryIndex) ?: continue
                        appendSeparated(reasoning, part.optString("text"))
                    }
                    appendSeparated(reasoning, item.optString("reasoning_text"))
                }
                "function_call" -> calls += FinalFunctionCall(
                    itemId = item.optString("id").ifBlank { "item_$index" },
                    callId = item.optString("call_id").ifBlank { "tool_call_$index" },
                    name = item.optString("name").ifBlank { "unknown_tool" },
                    arguments = item.optString("arguments").ifBlank { "{}" },
                )
            }
        }
        return FinalOutput(
            text = ResponsesCitationFormatter.apply(text.toString(), annotations),
            rawText = text.toString(),
            reasoning = reasoning.toString(),
            toolCalls = calls,
        )
    }

    private fun emitMissingSuffix(
        streamed: StringBuilder,
        authoritative: String,
        kind: AssistantBlockKind,
        blockIndex: Int?,
        allocateIndex: () -> Int,
        onStart: (Int) -> Unit,
        onEvent: (ProviderEvent) -> Unit,
    ) {
        if (authoritative.isEmpty() || authoritative == streamed.toString()) return
        val suffix = if (authoritative.startsWith(streamed.toString())) {
            authoritative.substring(streamed.length)
        } else {
            authoritative
        }
        if (suffix.isEmpty()) return
        val index = blockIndex ?: allocateIndex().also {
            onStart(it)
            onEvent(ProviderEvent.BlockStart(kind, it))
        }
        onEvent(ProviderEvent.BlockDelta(kind, index, suffix))
    }

    private fun finishReason(terminalType: String?, response: JSONObject, hasCalls: Boolean): String {
        if (hasCalls && terminalType == "response.completed") return "tool_calls"
        if (terminalType == "response.completed") return "stop"
        val reason = response.optJSONObject("incomplete_details")?.optString("reason")
            .orEmpty()
            .lowercase()
        return when {
            "max" in reason || "length" in reason -> "length"
            "filter" in reason || "content" in reason -> "content_filter"
            else -> "incomplete"
        }
    }

    private fun parseUsage(usage: JSONObject?): AgentTokenUsage? {
        usage ?: return null
        return AgentTokenUsage(
            contextTokens = usage.intValue("total_tokens"),
            inputTokens = usage.intValue("input_tokens", "prompt_tokens"),
            outputTokens = usage.intValue("output_tokens", "completion_tokens"),
            cachedTokens = usage.optJSONObject("input_tokens_details")?.intValue("cached_tokens")
                ?: usage.optJSONObject("prompt_tokens_details")?.intValue("cached_tokens"),
            reasoningTokens = usage.optJSONObject("output_tokens_details")?.intValue("reasoning_tokens")
                ?: usage.optJSONObject("completion_tokens_details")?.intValue("reasoning_tokens"),
        ).takeUnless { it.isEmpty }
    }

    private fun throwEventError(event: JSONObject) {
        val error = event.optJSONObject("error") ?: return
        val message = error.optString("message").ifBlank { "未提供错误信息" }.compactError()
        val type = error.optString("type").takeIf { it.isNotBlank() }
        error("模型接口 SSE 返回错误${type?.let { " (type=$it)" }.orEmpty()}：$message")
    }

    private fun throwTopLevelEventError(event: JSONObject): Nothing {
        val message = event.optString("message").ifBlank { "未提供错误信息" }.compactError()
        val code = event.optString("code").takeIf { it.isNotBlank() }
        error("模型接口 SSE 返回错误${code?.let { " (code=$it)" }.orEmpty()}：$message")
    }

    private fun throwResponseFailure(response: JSONObject): Nothing {
        val error = response.optJSONObject("error")
        val message = error?.optString("message").orEmpty().ifBlank { "未提供错误信息" }
        kotlin.error("模型接口 Responses 请求失败：${message.compactError()}")
    }

    private fun JSONObject.intValue(vararg keys: String): Int? {
        keys.forEach { key ->
            when (val value = opt(key)) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun appendSeparated(target: StringBuilder, value: String) {
        if (value.isBlank()) return
        if (target.isNotEmpty() && !target.endsWith("\n")) target.append('\n')
        target.append(value)
    }

    private fun String.compactError(): String =
        replace('\n', ' ').replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }

    private data class StreamingFunctionCall(
        val itemId: String,
        val contentIndex: Int,
        var callId: String,
        var name: String,
        val arguments: StringBuilder,
        var ended: Boolean = false,
    ) {
        fun endEvent() = ProviderEvent.BlockEnd(
            kind = AssistantBlockKind.TOOL_CALL,
            index = contentIndex,
            blockId = callId.ifBlank { null },
            name = name.ifBlank { null },
            content = arguments.toString(),
        )
    }

    private data class FinalFunctionCall(
        val itemId: String,
        val callId: String,
        val name: String,
        val arguments: String,
    ) {
        fun toChatToolCall(): JSONObject = JSONObject()
            .put("id", callId)
            .put("type", "function")
            .put(
                "function",
                JSONObject().put("name", name).put("arguments", arguments),
            )
    }

    private data class FinalOutput(
        val text: String,
        val rawText: String,
        val reasoning: String,
        val toolCalls: List<FinalFunctionCall>,
    )
}
