package com.deepseek.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "DeepSeekSettings",
    storages = [Storage("deepseek-ai.xml")]
)
class DeepSeekSettings : PersistentStateComponent<DeepSeekSettings> {
    var apiKey: String = ""
    var model: String = "deepseek-v4-flash"
    var maxTokens: Int = 4096
    var temperature: Double = 0.7
    var completionEnabled: Boolean = true
    var completionModel: String = "deepseek-v4-flash"  // 空=使用主模型, 可单独指定
    var completionMaxTokens: Int = 256
    var completionDelayMs: Long = 500
    var completionMinPrefix: Int = 2           // 最少输入字符数才触发补全
    var maxContextLines: Int = 30              // 补全时发送的最大上下文行数

    // 注解感知补全
    var annotationAwareEnabled: Boolean = true // 是否启用注解分析驱动的补全/代码模式

    // 注释感知补全
    var commentAwareEnabled: Boolean = true    // 是否启用注释分析驱动的代码补全

    // API Provider 选择: "deepseek" | "agnes" | "nvidia"
    var provider: String = "deepseek"

    // Agnes 2.0 Flash 配置
    var agnesApiKey: String = ""
    var agnesModel: String = "agnes-2.0-flash"
    var agnesBaseUrl: String = "https://apihub.agnes-ai.com/v1"

    // NVIDIA 配置
    var nvidiaApiKey: String = ""
    var nvidiaModel: String = "z-ai/glm-5.2"
    var nvidiaBaseUrl: String = "https://integrate.api.nvidia.com/v1"

    // OpenRouter 配置
    var openrouterApiKey: String = ""
    var openrouterModel: String = "poolside/laguna-xs-2.1:free"
    var openrouterBaseUrl: String = "https://openrouter.ai/api/v1"

    // 图片解析配置
    var imageParsingModel: String = "agnes"         // "agnes" | "stepfun" | "nvidia"
    var stepFunApiKey: String = ""                  // 仅 StepFun 需要独立密钥

    // 版本跟踪 — 用户上次看到的版本号，用于显示更新记录弹窗
    var lastSeenVersion: String = ""

    // 更新日志语言偏好: "zh" | "en"
    var changelogLanguage: String = "zh"

    // UI 界面语言: "zh" | "en"
    var language: String = "zh"

    // Agentic Search 配置
    var agenticSearchEnabled: Boolean = true   // 是否启用 Agentic Search（替代 RAG 的代码搜索）
    var agenticSearchMaxRounds: Int = 3         // 多轮搜索的最大轮次数（1=单轮模式）

    // Agent Pipeline 各 Phase 独立配置
    var agentPhase0Provider: String = "agnes"          // 意图确认 Provider
    var agentPhase0Model: String = "agnes-2.0-flash"  // 意图确认 Model

    // Phase 0 意图确认开关 — 关闭时跳过意图确认直接进入规划阶段
    var agentPhase0Enabled: Boolean = true

    // 流式输出开关 — 关闭时使用同步调用，一次性返回完整结果
    var streamingEnabled: Boolean = true
    var agentPhase1Provider: String = "deepseek"       // 规划 Provider
    var agentPhase1Model: String = "deepseek-v4-pro"   // 规划 Model
    var agentPhase2Provider: String = "deepseek"       // 编码 Provider
    var agentPhase2Model: String = "deepseek-v4-flash" // 编码 Model
    var agentPhase3Provider: String = "agnes"          // 审查 Provider
    var agentPhase3Model: String = "agnes-2.0-flash"   // 审查 Model

    // Q&A 分类器复用主 API Configuration 的 Provider/Model，无需单独存储字段

    override fun getState(): DeepSeekSettings = this
    override fun loadState(state: DeepSeekSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: DeepSeekSettings
            get() = ApplicationManager.getApplication().getService(DeepSeekSettings::class.java)
    }
}
