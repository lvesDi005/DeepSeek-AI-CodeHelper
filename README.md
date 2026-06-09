
<div align="center">

[English](#english) | [中文](#中文)

</div>

---

<a name="english"></a>
# DeepSeek AI CodeHelper - IntelliJ IDEA Plugin

DeepSeek AI intelligent assistant plugin for IntelliJ IDEA, integrating code completion, chat conversation, and intelligent code operation features.

## Features

### 1. Code Completion
- AI-powered inline code suggestions
- FIM (Fill-in-the-Middle) completion mode
- Supports multiple programming languages
- Configurable trigger delay and context lines

### 2. Chat Panel
- Sidebar chat window
- Streaming response display
- File attachment support
- Selected code preview
- Session management

### 3. Agent Actions
Right-click code to quickly execute:
- **Ask DeepSeek** - Ask any question about the selected code
- **Explain Code** - Explain the selected code in detail
- **Generate Code** - Generate code based on selection or description
- **Review Code** - Code review with suggestions
- **Optimize Code** - Optimize code performance and readability

### 4. Settings Configuration
- API Key configuration
- Model selection
- Token limit
- Temperature parameter adjustment
- Code completion toggle
- Separate completion model configuration

## Tech Stack

- **Language**: Kotlin
- **Build Tool**: Gradle
- **Target Platform**: IntelliJ IDEA 2025.1+ (IC-2025.1)
- **Network Library**: OkHttp 4.12.0 (with SSE streaming support)
- **JSON Processing**: Gson 2.11.0
- **Minimum Java Version**: 21

## Installation &amp; Configuration

### Prerequisites
- IntelliJ IDEA 2025.1 or later
- DeepSeek API Key (from https://platform.deepseek.com)
- Java 21

### Installation Steps

#### Build from Source
1. Clone the repository
   ```bash
   git clone &lt;repository-url&gt;
   cd dp-connection
   ```

2. Build the plugin using Gradle
   ```bash
   # Windows
   .\gradlew.bat buildPlugin

   # Linux/Mac
   ./gradlew buildPlugin
   ```

3. After building, the plugin file is located in `build/distributions/` directory

#### Run in IDE
```bash
# Windows
.\gradlew.bat runIde

# Linux/Mac
./gradlew runIde
```

### Configure Plugin

1. Open IntelliJ IDEA Settings
2. Navigate to **Tools → DeepSeek AI CodeHelper**
3. Configure the following:
   - **API Key**: Enter your DeepSeek API Key
   - **Model**: Select model (default: deepseek-chat)
   - **Max Tokens**: Maximum generated tokens (default: 4096)
   - **Temperature**: Temperature parameter (default: 0.7)
   - **Completion Enabled**: Enable/disable code completion
   - **Completion Model**: Dedicated completion model (optional)
   - **Completion Max Tokens**: Completion max tokens (default: 256)
   - **Completion Delay (ms)**: Completion trigger delay (default: 500ms)
   - **Completion Min Prefix**: Minimum prefix characters to trigger (default: 2)
   - **Max Context Lines**: Maximum context lines for completion (default: 30)

## Project Structure

```
dp-connection/
├── src/main/
│   ├── java/com/deepseek/plugin/
│   │   ├── agent/              # Agent action implementations
│   │   │   ├── AgentActions.kt
│   │   │   └── BaseAgentAction.kt
│   │   ├── api/                # API client
│   │   │   ├── DeepSeekApiClient.kt
│   │   │   └── Models.kt
│   │   ├── chat/               # Chat panel
│   │   │   ├── ChatPanel.kt
│   │   │   └── ChatToolWindowFactory.kt
│   │   ├── completion/         # Code completion
│   │   │   └── DeepSeekCompletionContributor.kt
│   │   ├── context/            # Project context provider
│   │   │   └── ProjectContextProvider.kt
│   │   ├── icons/              # Icon resources
│   │   │   └── PluginIcons.kt
│   │   ├── settings/           # Settings configuration
│   │   │   ├── DeepSeekSettings.kt
│   │   │   └── DeepSeekSettingsConfigurable.kt
│   │   ├── store/              # Session storage
│   │   │   └── SessionStore.kt
│   │   └── ui/                 # UI components
│   │       ├── AttachedFile.kt
│   │       ├── CodeBlockCard.kt
│   │       ├── FileAttachmentPreview.kt
│   │       └── SelectedCodePreview.kt
│   └── resources/
│       ├── META-INF/
│       │   ├── plugin.xml      # Plugin configuration file
│       │   └── pluginIcon.*
│       └── icons/
├── gradle/                     # Gradle wrapper
├── build.gradle.kts            # Gradle build script
├── settings.gradle.kts         # Gradle settings
└── gradle.properties           # Gradle property configuration
```

## Core Module Description

### DeepSeekApiClient (`api/DeepSeekApiClient.kt`)
- Responsible for communicating with DeepSeek API
- Supports both synchronous and streaming call modes
- Implements FIM (Fill-in-the-Middle) code completion
- Manages API requests and responses

### ChatPanel (`chat/ChatPanel.kt`)
- Implements chat interface
- Supports streaming response display
- Handles file attachments
- Displays selected code preview

### Agent Actions (`agent/`)
- Implements right-click menu operations
- Includes Ask, Explain, Generate, Review, Optimize features
- Built on BaseAgentAction base class

### DeepSeekSettings (`settings/DeepSeekSettings.kt`)
- Manages plugin configuration
- Persistent storage
- Singleton pattern access

### DeepSeekCompletionContributor (`completion/DeepSeekCompletionContributor.kt`)
- Integrates with IDE's code completion system
- Calls FIM API for suggestions
- Manages completion trigger timing

## Version History

### v1.0.4
- Added file upload button in chat input
- Selected code preview card
- Modular code block card component (CodeBlockCard)
- Clear button moved to session bar

### v1.0.3
- Fixed duplicate AI responses consuming double tokens in chat
- Fixed oversized right-click menu icon
- Fixed oversized agent result dialog
- Improved code completion: single-suggestion mode, better FIM prompt

### v1.0.0
- AI-powered inline code completion
- Streaming chat Q&amp;A panel
- Agent mode: Explain, Generate, Review, Optimize
- Right-click context menu integration
- API Key configuration page

## Development Guide

### Environment Setup
1. Install IntelliJ IDEA 2025.1+
2. Install Kotlin plugin
3. Install Gradle
4. Clone project and open in IDE

### Common Gradle Tasks

```bash
# Build plugin
.\gradlew.bat buildPlugin

# Run IDE test instance
.\gradlew.bat runIde

# Clean build
.\gradlew.bat clean

# Compile Kotlin
.\gradlew.bat compileKotlin
```

### Debug Plugin
Use IntelliJ IDEA's `Run IDE with Plugin` run configuration for debugging.

## License

Copyright 2026 DeepSeek

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## Contact

- Official Website: https://deepseek.com
- Plugin ID: com.deepseek.plugin
- Plugin Name: DeepSeek AI CodeHelper

## Notes

- Requires valid DeepSeek API Key to use
- Code completion feature sends code context to DeepSeek server
- It is recommended to read DeepSeek's privacy policy before use
- Plugin persists configuration information (not including API Key)

---

<a name="中文"></a>
# DeepSeek AI CodeHelper - IntelliJ IDEA 插件

为 IntelliJ IDEA 提供的 DeepSeek AI 智能助手插件，集成代码补全、聊天对话和智能代码操作功能。

## 功能特性

### 1. 代码补全
- AI 驱动的内联代码建议
- FIM (Fill-in-the-Middle) 补全模式
- 支持多种编程语言
- 可配置的触发延迟和上下文行数

### 2. 聊天面板
- 侧边栏聊天窗口
- 流式响应显示
- 文件附件支持
- 选中代码预览
- 会话管理

### 3. 智能代理操作 (Agent Actions)
右键点击代码，快速执行：
- **Ask DeepSeek** - 询问关于选中代码的任何问题
- **Explain Code** - 详细解释选中代码
- **Generate Code** - 根据选择或描述生成代码
- **Review Code** - 代码审查并提供建议
- **Optimize Code** - 优化代码性能和可读性

### 4. 设置配置
- API Key 配置
- 模型选择
- Token 限制
- 温度参数调整
- 代码补全开关
- 独立的补全模型配置

## 技术栈

- **语言**: Kotlin
- **构建工具**: Gradle
- **目标平台**: IntelliJ IDEA 2025.1+ (IC-2025.1)
- **网络库**: OkHttp 4.12.0 (支持 SSE 流式传输)
- **JSON 处理**: Gson 2.11.0
- **最低 Java 版本**: 21

## 安装与配置

### 前置要求
- IntelliJ IDEA 2025.1 或更高版本
- DeepSeek API Key (从 https://platform.deepseek.com 获取)
- Java 21

### 安装步骤

#### 从源码构建
1. 克隆项目
   ```bash
   git clone &lt;repository-url&gt;
   cd dp-connection
   ```

2. 使用 Gradle 构建插件
   ```bash
   # Windows
   .\gradlew.bat buildPlugin

   # Linux/Mac
   ./gradlew buildPlugin
   ```

3. 构建完成后，插件文件位于 `build/distributions/` 目录

#### 在 IDE 中运行
```bash
# Windows
.\gradlew.bat runIde

# Linux/Mac
./gradlew runIde
```

### 配置插件

1. 打开 IntelliJ IDEA 设置
2. 导航到 **Tools → DeepSeek AI CodeHelper**
3. 配置以下项：
   - **API Key**: 输入你的 DeepSeek API Key
   - **Model**: 选择模型 (默认: deepseek-chat)
   - **Max Tokens**: 最大生成 Token 数 (默认: 4096)
   - **Temperature**: 温度参数 (默认: 0.7)
   - **Completion Enabled**: 启用/禁用代码补全
   - **Completion Model**: 代码补全专用模型 (可选)
   - **Completion Max Tokens**: 补全最大 Token 数 (默认: 256)
   - **Completion Delay (ms)**: 补全触发延迟 (默认: 500ms)
   - **Completion Min Prefix**: 触发补全的最小前缀字符数 (默认: 2)
   - **Max Context Lines**: 补全上下文最大行数 (默认: 30)

## 项目结构

```
dp-connection/
├── src/main/
│   ├── java/com/deepseek/plugin/
│   │   ├── agent/              # 代理操作实现
│   │   │   ├── AgentActions.kt
│   │   │   └── BaseAgentAction.kt
│   │   ├── api/                # API 客户端
│   │   │   ├── DeepSeekApiClient.kt
│   │   │   └── Models.kt
│   │   ├── chat/               # 聊天面板
│   │   │   ├── ChatPanel.kt
│   │   │   └── ChatToolWindowFactory.kt
│   │   ├── completion/         # 代码补全
│   │   │   └── DeepSeekCompletionContributor.kt
│   │   ├── context/            # 项目上下文提供
│   │   │   └── ProjectContextProvider.kt
│   │   ├── icons/              # 图标资源
│   │   │   └── PluginIcons.kt
│   │   ├── settings/           # 设置配置
│   │   │   ├── DeepSeekSettings.kt
│   │   │   └── DeepSeekSettingsConfigurable.kt
│   │   ├── store/              # 会话存储
│   │   │   └── SessionStore.kt
│   │   └── ui/                 # UI 组件
│   │       ├── AttachedFile.kt
│   │       ├── CodeBlockCard.kt
│   │       ├── FileAttachmentPreview.kt
│   │       └── SelectedCodePreview.kt
│   └── resources/
│       ├── META-INF/
│       │   ├── plugin.xml      # 插件配置文件
│       │   └── pluginIcon.*
│       └── icons/
├── gradle/                     # Gradle 包装器
├── build.gradle.kts            # Gradle 构建脚本
├── settings.gradle.kts         # Gradle 设置
└── gradle.properties           # Gradle 属性配置
```

## 核心模块说明

### DeepSeekApiClient (`api/DeepSeekApiClient.kt`)
- 负责与 DeepSeek API 通信
- 支持同步和流式两种调用模式
- 实现 FIM (Fill-in-the-Middle) 代码补全
- 管理 API 请求和响应

### ChatPanel (`chat/ChatPanel.kt`)
- 实现聊天界面
- 支持流式响应展示
- 处理文件附件
- 显示选中代码预览

### Agent Actions (`agent/`)
- 实现右键菜单操作
- 包括 Ask、Explain、Generate、Review、Optimize 功能
- 基于 BaseAgentAction 基类构建

### DeepSeekSettings (`settings/DeepSeekSettings.kt`)
- 管理插件配置
- 持久化存储
- 单例模式访问

### DeepSeekCompletionContributor (`completion/DeepSeekCompletionContributor.kt`)
- 集成 IDE 的代码补全系统
- 调用 FIM API 获取建议
- 管理补全触发时机

## 版本历史

### v1.0.4
- 聊天输入框添加文件上传按钮
- 选中代码预览卡片
- 模块化代码块卡片组件 (CodeBlockCard)
- 清除按钮移至会话栏

### v1.0.3
- 修复聊天中 AI 响应重复消费 Token 问题
- 修复右键菜单图标过大问题
- 修复代理结果对话框过大问题
- 改进代码补全：单建议模式、更好的 FIM 提示

### v1.0.0
- AI 驱动内联代码补全
- 流式聊天问答面板
- 代理模式：解释、生成、审查、优化
- 右键上下文菜单集成
- API Key 配置页面

## 开发指南

### 环境设置
1. 安装 IntelliJ IDEA 2025.1+
2. 安装 Kotlin 插件
3. 安装 Gradle
4. 克隆项目并在 IDE 中打开

### 常用 Gradle 任务

```bash
# 构建插件
.\gradlew.bat buildPlugin

# 运行 IDE 测试实例
.\gradlew.bat runIde

# 清理构建
.\gradlew.bat clean

# 编译 Kotlin
.\gradlew.bat compileKotlin
```

### 调试插件
使用 IntelliJ IDEA 的 `Run IDE with Plugin` 运行配置进行调试。

## 许可证

Copyright 2026 DeepSeek

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## 联系方式

- 官方网站: https://deepseek.com
- 插件 ID: com.deepseek.plugin
- 插件名称: DeepSeek AI CodeHelper

## 注意事项

- 需要有效的 DeepSeek API Key 才能使用
- 代码补全功能会发送代码上下文到 DeepSeek 服务器
- 建议在使用前阅读 DeepSeek 的隐私政策
- 插件会持久化存储配置信息 (不包括 API Key)

