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
    var model: String = "deepseek-chat"
    var maxTokens: Int = 4096
    var temperature: Double = 0.7
    var completionEnabled: Boolean = true
    var completionModel: String = ""           // 空=使用主模型, 可单独指定如 "deepseek-chat"
    var completionMaxTokens: Int = 256
    var completionDelayMs: Long = 500
    var completionMinPrefix: Int = 2           // 最少输入字符数才触发补全
    var maxContextLines: Int = 30              // 补全时发送的最大上下文行数

    override fun getState(): DeepSeekSettings = this
    override fun loadState(state: DeepSeekSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: DeepSeekSettings
            get() = ApplicationManager.getApplication().getService(DeepSeekSettings::class.java)
    }
}
