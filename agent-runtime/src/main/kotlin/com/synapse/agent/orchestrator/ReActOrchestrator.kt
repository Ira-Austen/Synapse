package com.synapse.agent.orchestrator

import com.synapse.agent.llm.LlmClient
import com.synapse.agent.llm.LlmMessage
import com.synapse.agent.tools.AgentToolRegistry
import com.synapse.protocol.MiniJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed class AgentStreamEvent {
    data class StepStarted(val step: Int, val maxSteps: Int) : AgentStreamEvent()
    data class AssistantThought(val text: String) : AgentStreamEvent()
    data class ToolInvoked(val toolName: String, val argsJson: String) : AgentStreamEvent()
    data class ToolFinished(val toolName: String, val success: Boolean, val outputJson: String) : AgentStreamEvent()
    data class Completed(val finalAnswer: String, val stepsUsed: Int) : AgentStreamEvent()
    data class Failed(val error: String) : AgentStreamEvent()
}

data class BatchHostingItem(
    val pkg: String,
    val userId: Int = 0,
    val status: String = "PENDING",
    val summary: String = ""
)

/**
 * ReAct orchestrator with hard step budget (`maxSteps = 8`), automatic logcat self-correction,
 * and graceful fallback to `KEEP_ALIVE_ONLY` when isolated wakeup is impossible.
 */
class ReActOrchestrator(
    private val llm: LlmClient,
    private val tools: AgentToolRegistry,
    private val maxSteps: Int = DEFAULT_MAX_STEPS
) {

    fun diagnoseAndOptimize(
        pkg: String,
        userId: Int = 0,
        userInstruction: String = "请诊断该应用的后台推送通道，优先寻找可独立保活的推送子进程并验证安全配置。"
    ): Flow<AgentStreamEvent> = flow {
        val toolDefs = tools.definitions()
        val history = ArrayList<LlmMessage>().apply {
            add(LlmMessage(role = "system", content = SYSTEM_PROMPT))
            add(
                LlmMessage(
                    role = "user",
                    content = "目标包名: $pkg (userId=$userId)\n任务要求: $userInstruction"
                )
            )
        }

        for (step in 1..maxSteps) {
            emit(AgentStreamEvent.StepStarted(step, maxSteps))
            val response = try {
                llm.chat(history, toolDefs)
            } catch (t: Throwable) {
                emit(AgentStreamEvent.Failed("LLM 调用失败: ${t.message}"))
                return@flow
            }

            val assistant = response.assistantMessage
            history.add(assistant)

            if (!assistant.content.isNullOrBlank()) {
                emit(AgentStreamEvent.AssistantThought(assistant.content))
            }

            if (assistant.toolCalls.isEmpty()) {
                emit(AgentStreamEvent.Completed(assistant.content.orEmpty(), step))
                return@flow
            }

            for (tc in assistant.toolCalls) {
                emit(AgentStreamEvent.ToolInvoked(tc.name, tc.argumentsJson))
                val parsedArgs = runCatching { MiniJson.parseObject(tc.argumentsJson) }.getOrElse { emptyMap() }
                val toolRes = tools.invoke(tc.name, parsedArgs)
                emit(AgentStreamEvent.ToolFinished(tc.name, toolRes.success, toolRes.jsonOutput))
                history.add(
                    LlmMessage(
                        role = "tool",
                        toolCallId = tc.id,
                        content = toolRes.jsonOutput
                    )
                )
            }
        }

        emit(
            AgentStreamEvent.Completed(
                finalAnswer = "已达到最大诊断步数 ($maxSteps)，请检查上述探测结果或按保守 KEEP_ALIVE_ONLY 规则提交。",
                stepsUsed = maxSteps
            )
        )
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 8

        val SYSTEM_PROMPT: String = """
            你是 Synapse 端侧 Root 诊断智能体，负责以“零破坏、纯内存接管、低功耗”为原则分析 Android 应用的后台推送架构。
            工作铁律：
            1. 先调用 `inspect_manifest` 与 `inspect_live_state` 定位持有活跃 TCP/UDP 长连接的独立子进程（如 :push）。
            2. 严禁修改系统设置文件或使用无声播放音频等偏门手段；遵守 Android 17 Memory Limiter 规范，确保主/子进程分离。
            3. 遇到高风控应用（RiskAppGuard 拦截）时，严禁主动探测，仅采用只读分析并保守降级为 `KEEP_ALIVE_ONLY`。
            4. 使用 `probe_wake_component` 试错时，若首次拉起报错，阅读返回的 logcat 补全 `wakeAction` 或切换候选组件重试；若任何组件都会误拉起主进程或弹窗，则自动降级为 `KEEP_ALIVE_ONLY`。
            5. 验证通过后调用 `commit_and_verify_rule` 写入策略，并在最终回复中给出清晰简洁的中文结论。
        """.trimIndent()
    }
}
