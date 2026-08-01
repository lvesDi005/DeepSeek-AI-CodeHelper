package com.deepseek.plugin.settings

import com.deepseek.plugin.api.SettingsSnapshot

/** 将 [DeepSeekSettings] 转换为纯数据快照 [SettingsSnapshot]，用于跨模块传递。 */
fun DeepSeekSettings.toSnapshot(): SettingsSnapshot = SettingsSnapshot(
    provider = provider,
    apiKey = apiKey,
    model = model,
    agnesApiKey = agnesApiKey,
    agnesModel = agnesModel,
    agnesBaseUrl = agnesBaseUrl,
    nvidiaApiKey = nvidiaApiKey,
    nvidiaModel = nvidiaModel,
    nvidiaBaseUrl = nvidiaBaseUrl,
    openrouterApiKey = openrouterApiKey,
    openrouterModel = openrouterModel,
    openrouterBaseUrl = openrouterBaseUrl,
    zhipuApiKey = zhipuApiKey,
    zhipuModel = zhipuModel,
    zhipuBaseUrl = zhipuBaseUrl,
)
