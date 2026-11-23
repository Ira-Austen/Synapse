package com.synapse.agent.llm

import com.synapse.agent.tools.ToolDefinition
import com.synapse.protocol.MiniJson
import com.synapse.protocol.arr
import com.synapse.protocol.obj
import com.synapse.protocol.str
import com.synapse.protocol.strOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LlmConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.2,
    val timeoutMs: Int = 30_000
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class LlmMessage(
    val role: String, // system, user, assistant, tool
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList()
)

data class LlmResponse(
    val assistantMessage: LlmMessage
)

interface LlmClient {
    suspend fun chat(messages: List<LlmMessage>, tools: List<ToolDefinition>): LlmResponse
}

/**
 * Lightweight zero-dependency OpenAI-compatible Chat Completions client with Function Calling.
 */
class OpenAiCompatibleLlmClient(
    private val configProvider: () -> LlmConfig
) : LlmClient {

    override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolDefinition>): LlmResponse =
        withContext(Dispatchers.IO) {
            val cfg = configProvider()
            require(cfg.apiKey.isNotBlank()) { "LLM API Key is not configured" }

            val endpoint = cfg.baseUrl.trimEnd('/') + "/chat/completions"
            val bodyMap = linkedMapOf<String, Any?>(
                "model" to cfg.model,
                "temperature" to cfg.temperature,
                "messages" to messages.map(::serializeMessage),
                "tools" to tools.map { t ->
                    linkedMapOf<String, Any?>(
                        "type" to "function",
                        "function" to linkedMapOf<String, Any?>(
                            "name" to t.name,
                            "description" to t.description,
                            "parameters" to t.parametersSchema
                        )
                    )
                },
                "tool_choice" to "auto"
            )

            val conn = (URI.create(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = cfg.timeoutMs
                readTimeout = cfg.timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }

            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(MiniJson.encode(bodyMap))
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) {
                    throw IllegalStateException("LLM HTTP $code: ${raw.take(400)}")
                }
                parseResponse(raw)
            } finally {
                conn.disconnect()
            }
        }

    private fun serializeMessage(msg: LlmMessage): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("role" to msg.role)
        if (msg.content != null) m["content"] = msg.content
        if (msg.toolCallId != null) m["tool_call_id"] = msg.toolCallId
        if (msg.toolCalls.isNotEmpty()) {
            m["tool_calls"] = msg.toolCalls.map { tc ->
                linkedMapOf<String, Any?>(
                    "id" to tc.id,
                    "type" to "function",
                    "function" to linkedMapOf<String, Any?>(
                        "name" to tc.name,
                        "arguments" to tc.argumentsJson
                    )
                )
            }
        }
        return m
    }

    internal fun parseResponse(rawJson: String): LlmResponse {
        val root = MiniJson.parseObject(rawJson)
        val choices = root.arr("choices") ?: throw IllegalStateException("Missing 'choices' in LLM response")
        @Suppress("UNCHECKED_CAST")
        val first = choices.firstOrNull() as? Map<String, Any?>
            ?: throw IllegalStateException("Empty 'choices' in LLM response")
        val msg = first.obj("message") ?: throw IllegalStateException("Missing 'message' in choice")

        val content = msg.strOrNull("content")
        val calls = msg.arr("tool_calls")?.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val tc = item as? Map<String, Any?> ?: return@mapNotNull null
            val fn = tc.obj("function") ?: return@mapNotNull null
            LlmToolCall(
                id = tc.str("id", "call_0"),
                name = fn.str("name"),
                argumentsJson = fn.str("arguments", "{}")
            )
        } ?: emptyList()

        return LlmResponse(
            assistantMessage = LlmMessage(
                role = "assistant",
                content = content,
                toolCalls = calls
            )
        )
    }
}
