package com.deepseek.plugin.api

/**
 * 补全触发模式。
 *
 * - AUTO: 用户输入时自动触发，走缓存，低 temperature，较少 token，快速响应
 * - MANUAL: 用户主动触发（Alt+P / 右键菜单），跳过缓存，高 temperature，较多 token，生成更完整
 */
enum class TriggerMode {
    AUTO,
    MANUAL;

    companion object {
        /** AUTO 模式的默认 temperature */
        const val AUTO_TEMPERATURE = 0.0
        /** MANUAL 模式的默认 temperature */
        const val MANUAL_TEMPERATURE = 0.2
        /** AUTO 模式的默认 max_tokens */
        const val AUTO_MAX_TOKENS = 128
        /** MANUAL 模式的默认 max_tokens */
        const val MANUAL_MAX_TOKENS = 512
    }
}
