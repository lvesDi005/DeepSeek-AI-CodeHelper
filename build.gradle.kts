plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.deepseek.plugin"
version = "2.3.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2024.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
}

intellijPlatform {
    pluginConfiguration {
        name = "DeepSeek AI CodeHelper"
        description = """
            <h2>DeepSeek AI CodeHelper - IntelliJ IDEA Plugin</h2>
            <ul>
              <li><b>Code Completion</b> - AI-powered inline suggestions + static analysis (annotation-aware + comment-aware)</li>
              <li><b>Chat Panel</b> - Sidebar chat window with streaming replies and file attachments</li>
              <li><b>Agent Actions</b> - Right-click to Ask DeepSeek / Explain Code / Generate / Review / Optimize</li>
            </ul>
            <br>
            <p>Configure API Key in <b>Settings -> Tools -> DeepSeek AI</b>.</p>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "263.*"
        }
        changeNotes = """
            <h3>v2.3.1</h3>
            <ul>
              <li>架构优化</li>
            </ul>
            <h3>v2.3.0</h3>
            <ul>
              <li>🧠 注解感知补全 — 自动分析 @Data/@Service/@RestController/@Entity 等注解，推荐合成方法（Lombok getter/setter/builder）和代码模式（Spring REST 骨架、JPA 映射、构造器注入）</li>
              <li>💬 注释感知补全 — 在注释中写自然语言即可生成代码，支持中文：<code>// 根据id删除订单</code> → deleteById、<code>// 查询所有</code> → findAll、<code>// 获取name字段</code> → getName 等 30+ 种模式</li>
              <li>🔖 @注解名自动补全 — 在类/方法/字段/参数位置输入 @ 时，自动推荐当前上下文适用的注解列表</li>
              <li>⚙️ 新增独立设置开关 — 可分别开启/关闭"注解感知补全"和"注释感知补全"</li>
              <li>🛡️ 静态分析前置过滤 — 基于 IntelliJ PSI/AST 的静态补全作为 AI 补全的前置过滤层，减少不必要的 API 调用</li>
            </ul>
            <h3>v2.2.5</h3>
            <ul>
              <li>发送和换行改为 Enter / Shift+Enter</li>
            </ul>
            <h3>v2.2.4</h3>
            <ul>
              <li>📐 消息面板响应式布局 — 消息气泡自适应宽度，窗口缩放时动态重排，消除气泡上下多余空白</li>
              <li>🔄 连续分栏布局 — 拖拽消息与输入区域之间的分隔条实时更新内容</li>
              <li>📏 输入区域拖拽手柄现在直接调整分隔条位置，操作更可控</li>
              <li>📦 代码块最大高度从 300px 提升至 1500px，适配长代码片段</li>
              <li>✂️ 收紧消息气泡内边距，减少用户/助手消息周围的垂直空白</li>
            </ul>
            <h3>v2.2.3</h3>
            <ul>
              <li>修复：按钮点击事件</li>
            </ul>
            <h3>v2.2.2</h3>
            <ul>
              <li>修复：JTextComponent.modelToView(int) -> modelToView2D(int)</li>
            </ul>
            <h3>v2.2.1</h3>
            <ul>
              <li>VSCode 工业暗色主题输入面板 — 4 层垂直布局：通知栏、工具栏、可缩放输入区、状态栏</li>
              <li>输入区自动伸缩 — 最多 12 行，超出滚动，支持拖拽调整高度</li>
              <li>简洁选中代码徽章 — 仅显示文件名和行范围，不展示代码片段</li>
              <li>附件上传和 UI 操作使用 IntelliJ 原生图标</li>
            </ul>
            <h3>v2.2.0</h3>
            <ul>
              <li>多模型供应商支持 — 设置面板可在 DeepSeek 和 Agnes 2.0 Flash 之间切换</li>
              <li>集成 Agnes 2.0 Flash — 可配置 API Key、模型名和 Base URL</li>
              <li>设置 UI 更新 — 新增 API Provider 选择器和 Agnes 专属配置区域</li>
            </ul>
            <h3>v2.1.0</h3>
            <ul>
              <li>版本更新弹窗 — 插件更新后自动显示更新记录</li>
              <li>多项 Bug 修复和稳定性改进</li>
              <li>向后兼容 IDEA 24.1.x ~ 26.3.x</li>
            </ul>
            <h3>v2.0.2</h3>
            <ul>
              <li>新增 Agent 模式 — 在聊天面板底部下拉菜单切换 "💬 问答" 和 "🤖 Agent"</li>
              <li>Agent 模式下 AI 自动扫描项目目录，定位目标文件，根据用户请求执行创建/修改/删除操作</li>
              <li>递归收集项目源文件（跳过 .git/build/target/node_modules 等）作为 AI 上下文</li>
              <li>AI 输出结构化 <code>&lt;file path="..."&gt;</code> 标签指定文件操作</li>
              <li>路径穿越防护 — 规范化路径校验确保所有写入操作限制在项目根目录内</li>
              <li>用户确认对话框 — 执行前展示完整的待操作文件列表</li>
              <li>Agent 模式下支持多轮对话 — 连续请求间保持完整消息历史</li>
              <li>Agent 激活时发送按钮文字变为 "执行 (Enter)"</li>
            </ul>
            <h3>v2.0.1</h3>
            <ul>
              <li>大规模 UI 重构 — ChatPanel 拆分为模块化组件（MessageBubble, ChatInputBar, ChatToolbar, SessionBar）</li>
              <li>AI 回复支持 Markdown 渲染（粗体、斜体、行内代码、链接、列表）</li>
              <li>消息美化：圆角、阴影、圆形头像图标（用户/助手）、时间戳显示</li>
              <li>用量和历史对话框抽取为独立类</li>
              <li>优化打包 — 从 okhttp JAR 中剔除 Android/安全平台类以通过 Plugin Verifier</li>
              <li>修复 IntelliJ 2025.1/2025.2 兼容性问题</li>
            </ul>
            <h3>v1.0.5</h3>
            <ul>
              <li>新增问题导航侧栏 — 每次显示 6 个节点，滚动时动态更新，修复点击节点未滚动到对应回复的问题</li>
              <li>消息气泡自适应宽度（容器宽度的 75%），布局更均衡易读</li>
            </ul>
            <h3>v1.0.4</h3>
            <ul>
              <li>聊天输入区文件上传按钮 — 点击选择本地文件，以上传预览卡片形式展示在输入区上方；文件内容随消息发送作为上下文</li>
              <li>选中代码预览卡片 — 显示文件、行范围和选中代码前 3 行，支持一键关闭</li>
              <li>代码块卡片（CodeBlockCard）抽取为可复用 UI 组件，带复制和插入按钮</li>
              <li>清空按钮移至会话栏，方便管理会话</li>
            </ul>
            <h3>v1.0.3</h3>
            <ul>
              <li>修复 AI 回复重复消耗双倍 Token 的问题</li>
              <li>修复右键菜单图标过大的问题</li>
              <li>修复 Agent 结果对话框过大的问题</li>
              <li>改进代码补全：单建议模式、优化 FIM 提示词</li>
            </ul>
            <h3>v1.0.0</h3>
            <ul>
              <li>AI 驱动的行内代码补全</li>
              <li>流式聊天问答面板</li>
              <li>Agent 模式：解释代码、生成代码、审查代码、优化代码</li>
              <li>右键菜单集成</li>
              <li>API Key 配置页面</li>
            </ul>
        """.trimIndent()
    }
}

// Write a Python script that filters unwanted classes from okhttp JAR
val filterScriptPath = layout.buildDirectory.file("tmp/filter_okhttp.py")
val writeFilterScript by tasks.registering {
    outputs.file(filterScriptPath)
    // Not compatible with configuration cache (writes to build dir)
    notCompatibleWithConfigurationCache("writes filter script to build dir")
    doLast {
        filterScriptPath.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
import zipfile, sys, os

exclude_prefixes = [
    'okhttp3/internal/platform/android/',
    'okhttp3/internal/platform/Android10Platform',
    'okhttp3/internal/platform/AndroidPlatform',
    'okhttp3/internal/platform/ConscryptPlatform',
    'okhttp3/internal/platform/BouncyCastlePlatform',
    'okhttp3/internal/platform/OpenJSSEPlatform',
]

jar_path = sys.argv[1]
tmp_path = jar_path + '.tmp'

with zipfile.ZipFile(jar_path, 'r') as zin, \
     zipfile.ZipFile(tmp_path, 'w', zipfile.ZIP_DEFLATED) as zout:
    for entry in zin.infolist():
        if not any(entry.filename.startswith(p) for p in exclude_prefixes):
            zout.writestr(entry, zin.read(entry.filename))

os.replace(tmp_path, jar_path)
print(f'Filtered: {jar_path}')
            """.trimIndent()
            )
        }
    }
}

val filterOkhttpLib by tasks.registering {
    dependsOn("prepareSandbox", writeFilterScript)
    inputs.file(filterScriptPath)
    // Not compatible with configuration cache (ProcessBuilder)
    notCompatibleWithConfigurationCache("spawns external process")
    doLast {
        val sandboxDir = layout.buildDirectory.dir("idea-sandbox").get()
        sandboxDir.asFile.walkTopDown().filter {
            it.name == "okhttp-4.12.0.jar"
        }.forEach { jarFile ->
            val script = filterScriptPath.get().asFile
            val pb = ProcessBuilder("python", script.absolutePath, jarFile.absolutePath)
            pb.inheritIO()
            val proc = pb.start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("filter_okhttp.py failed with exit code $exitCode")
            }
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    // Third-party deps are placed in lib/ by IntelliJ Platform Gradle Plugin 2.x automatically.
    // Do NOT extract them into the main plugin JAR — that would cause the Plugin Verifier
    // to attribute their internal deprecated API usage to our plugin.
    // The filterOkhttpLib task below handles stripping Android/security classes from lib/ JARs.

    // Ensure okhttp filtering happens before building the plugin ZIP and running IDE
    named("buildPlugin") {
        dependsOn(filterOkhttpLib)
    }
    named("runIde") {
        dependsOn(filterOkhttpLib)
    }
}
