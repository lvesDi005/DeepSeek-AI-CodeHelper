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

    // 补全结果缓存
    var completionCacheEnabled: Boolean = true // 是否启用 LRU 补全缓存
    var completionCacheSize: Int = 20          // LRU 缓存容量（条）

    // 手动触发补全（Alt+P）配置
    var completionManualTemperature: Double = 0.2  // 手动触发生成温度（AUTO 为 0.0）
    var completionManualMaxTokens: Int = 512        // 手动触发生成最大 token 数（AUTO 为 256）

    // 状态栏指示器
    var completionStatusBarEnabled: Boolean = true // 是否在状态栏显示补全状态

    // Ghost Text 渲染模式（替代 Lookup 列表）
    var completionGhostTextEnabled: Boolean = true // 是否启用 Ghost Text 渲染（默认开启）

    // API Provider 选择: "deepseek" | "agnes" | "nvidia"
    var provider: String = "deepseek"

    // Agnes 2.0 Flash 配置
    var agnesApiKey: String = ""
    var agnesModel: String = "agnes-2.5-flash"
    var agnesBaseUrl: String = "https://api.agnes-ai.cn/v1"

    // NVIDIA 配置
    var nvidiaApiKey: String = ""
    var nvidiaModel: String = "z-ai/glm-5.2"
    var nvidiaBaseUrl: String = "https://integrate.api.nvidia.com/v1"

    // OpenRouter 配置
    var openrouterApiKey: String = ""
    var openrouterModel: String = "inclusionai/ling-3.0-flash:free"
    var openrouterBaseUrl: String = "https://openrouter.ai/api/v1"

    // 智谱 AI GLM 配置
    var zhipuApiKey: String = ""
    var zhipuModel: String = "glm-4"
    var zhipuBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4"

    // Claude (Anthropic) 配置 — 走 Anthropic OpenAI 兼容端点
    var anthropicApiKey: String = ""
    var anthropicModel: String = "claude-sonnet-4-5"
    var anthropicBaseUrl: String = "https://api.anthropic.com/v1"

    // Codex (OpenAI) 配置 — 复用本地 ~/.codex 登录态
    var codexApiKey: String = ""
    var codexModel: String = ""
    var codexBaseUrl: String = "https://api.openai.com/v1"
    var codexReasoningEffort: String = "medium"

    // Claude/Codex CLI Agent 权限模式: "acceptEdits"(默认,仅文件编辑) | "bypass"(跳过全部权限) | "plan"(仅计划)
    var cliAgentPermissionMode: String = "acceptEdits"

    // 图片解析配置
    var imageParsingModel: String = "agnes"         // "agnes" | "stepfun" | "nvidia"
    var stepFunApiKey: String = ""                  // 仅 StepFun 需要独立密钥

    // 版本跟踪 — 用户上次看到的版本号，用于显示更新记录弹窗
    var lastSeenVersion: String = ""

    // 更新日志语言偏好: "zh" | "en"
    var changelogLanguage: String = "zh"

    // 语言设置（控制 UI 界面 + AI 输出）: "zh" | "en"
    var language: String = "zh"

    // 内容展示字号（聊天正文、Markdown、流式输出等）: 12~16
    var contentFontSize: Int = 13

    // 插件独立主题：follow=跟随 IDE / dark=固定深色 / light=固定浅色（不影响 IDE 全局主题）
    var pluginTheme: String = "follow"

    // Agentic Search 配置
    var agenticSearchEnabled: Boolean = true   // 是否启用 Agentic Search（替代 RAG 的代码搜索）
    var agenticSearchMaxRounds: Int = 3         // 多轮搜索的最大轮次数（1=单轮模式）

    // Agent Pipeline 各 Phase 独立配置
    var agentPhase0Provider: String = "agnes"          // 意图确认 Provider
    var agentPhase0Model: String = "agnes-2.5-flash"  // 意图确认 Model

    // Phase 0 意图确认开关 — 关闭时跳过意图确认直接进入规划阶段
    var agentPhase0Enabled: Boolean = true

    // Phase 1 规划开关 — 关闭时跳过规划阶段直接进入编码
    var agentPhase1Enabled: Boolean = true

    // Phase 3 审查开关 — 关闭时跳过审查阶段
    var agentPhase3Enabled: Boolean = true

    // 流式输出开关 — 关闭时使用同步调用，一次性返回完整结果
    var streamingEnabled: Boolean = true

    // 思考过程显示开关 — 关闭时 Q&A/Q&A 全文扫描模式下不显示模型的思考内容
    var thinkingEnabled: Boolean = true

    // 输出速度等级：0=最快(不限) 1=快速 2=适中 3=慢速
    var outputSpeedLevel: Int = 2

    var agentPhase1Provider: String = "deepseek"       // 规划 Provider
    var agentPhase1Model: String = "deepseek-v4-pro"   // 规划 Model
    var agentPhase2Provider: String = "deepseek"       // 编码 Provider
    var agentPhase2Model: String = "deepseek-v4-flash" // 编码 Model
    var agentPhase3Provider: String = "agnes"          // 审查 Provider
    var agentPhase3Model: String = "agnes-2.5-flash"   // 审查 Model

    // Q&A 分类器复用主 API Configuration 的 Provider/Model，无需单独存储字段

    // ===== MCP Server 配置 =====
    var mcpEnabled: Boolean = false      // 是否启用 MCP Server
    var mcpAutoStart: Boolean = false    // IDE 启动时自动启动
    var mcpPort: Int = 8080              // 监听端口

    override fun getState(): DeepSeekSettings = this
    override fun loadState(state: DeepSeekSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: DeepSeekSettings
            get() = ApplicationManager.getApplication().getService(DeepSeekSettings::class.java)
    }
}
