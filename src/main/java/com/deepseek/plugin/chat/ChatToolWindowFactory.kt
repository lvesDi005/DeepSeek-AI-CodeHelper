package com.deepseek.plugin.chat

import com.deepseek.plugin.PluginVersion
import com.deepseek.plugin.settings.DeepSeekSettings
import com.deepseek.plugin.ui.ChangelogDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = ChatPanel(project)
        val content = ContentFactory.getInstance().createContent(
            chatPanel, "", false
        )
        toolWindow.contentManager.addContent(content)

        // Focus input when window is activated
        toolWindow.activate {
            chatPanel.focusInput()
        }

        // ── Check version and show changelog on update ──
        checkVersionAndShowChangelog(project)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    /**
     * Compare the last-seen version with the current plugin version.
     * If they differ, show the changelog dialog and update the stored version.
     */
    private fun checkVersionAndShowChangelog(project: Project) {
        val settings = DeepSeekSettings.instance
        val currentVersion = PluginVersion.current

        // First install or version changed → show changelog
        if (settings.lastSeenVersion != currentVersion) {
            val previousVersion = settings.lastSeenVersion.ifBlank { null }

            val dialog = ChangelogDialog(
                project = project,
                previousVersion = previousVersion,
                currentVersion = currentVersion,
                changeLogHtml = CHANGELOG_HTML,
                changeLogHtmlEn = CHANGELOG_HTML_EN,
                initialLanguage = settings.changelogLanguage
            )
            dialog.show()

            // Persist language preference after dialog closes
            settings.changelogLanguage = dialog.selectedLanguage

            // Persist the new version so the dialog doesn't show again
            settings.lastSeenVersion = currentVersion

            // Navigate to settings page if user clicked the setup link
            if (dialog.navigateToSettingsOnClose) {
                ChatPanel.currentInstance?.showSettingsPage("apiConfig")
            }
        }
    }

    companion object {
        /**
         * HTML changelog content displayed in the update dialog.
         */
        private val CHANGELOG_HTML = """
            <h3>v2.7.1</h3>
            <ul>
              <li>【新增】智谱配置项</li>
            </ul>
            <h3>v2.7.0</h3>
            <ul>
              <li>切换Agnes国内可用</li>
              <li>【新增】MCP Server — 在 IDE 内启动 MCP (Model Context Protocol) 服务，以 SSE/HTTP 端点暴露 IDEA 能力，外部 AI 客户端（Claude Desktop、Cursor 等）可连接调用文件读写、代码搜索、符号导航等 Tool</li>
              <li>【新增】MCP 设置 — 主题设置中新增 "MCP Server" 选项卡，可配置启用/关闭、端口（默认 8080）、自动启动</li>
              <li>【新增】内置 8 个 MCP Tool — read_file / write_file / list_directory / search_in_project / find_symbol / get_project_info / get_open_files / get_active_editor_content</li>
              <li>【新增】Extension Point — 提供 mcpToolProvider EP，供其他插件注册自定义 MCP Tool</li>
            </ul>
            <h3>v2.6.2</h3>
            <ul>
              <li>【新增】思考过程开关 — 设置菜单新增"显示思考过程"复选项，关闭后 Q&A/Q&A 全文扫描模式隐藏 AI 推理内容，AGENT 模式不受影响</li>
              <li>【优化】停止保留内容 — 点击停止时已生成的流式内容自动保留并渲染为完整消息气泡（Markdown/代码块/表格），不再丢失</li>
              <li>【修复】Agent 内容左对齐 — 修复 Agent 模式下代码块下方文本左侧空白过大的问题</li>
            </ul>
            <h3>v2.6.1</h3>
            <ul>
            <li>Layout optimization</li>
            </ul>
            <h3>v2.6.0</h3>
            <ul>
              <li>【新增】深度思考过程显示 — AI 回复中的推理内容（reasoning_content）折叠展示，可展开/收起，字数统计，深色/浅色主题自适应</li>
              <li>【新增】字体大小设置 — 主题设置新增「字体大小」下拉框(12px~16px)，控制聊天面板内容文字大小</li>
              <li>【新增】语言设置合并 — 「界面语言」与「AI 输出语言」合并为一个「语言」设置</li>
              <li>【新增】OpenRouter 默认模型更换 — 默认模型改为 inclusionai/ling-3.0-flash:free，模型输入框改为可编辑下拉框</li>
              <li>【优化】代码补全 — Ghost Text 默认开启、状态栏恢复显示 AI 状态、连续失败气泡提示、Python 预检层减少无效 API 调用</li>
              <li>【优化】代码补全 — 流式 FIM：SSE 逐 token 返回，首 token 即显示到 Ghost Text，降低感知延迟</li>
              <li>【优化】代码补全 — 新增 Ctrl+Shift+, 快捷键切换补全启用/禁用</li>
            </ul>
            <h3>v2.5.9</h3>
            <ul>
              <li>agent模式意图解析优化</li>
            </ul>
            <h3>v2.5.8</h3>
            <ul>
              <li>UI优化</li>
            </ul>
            <h3>v2.5.7</h3>
            <ul>
              <li>【新增】右键菜单「Set Language / 设置语言」— 一键跳转主题设置，独立控制右键输出语言（与聊天面板隔离）</li>
              <li>【新增】右键输出语言独立设置 — 主题设置中新增「右键输出语言」下拉框，支持中文/English 切换</li>
              <li>【优化】右键菜单文本动态中英文 — Set Language / Explain Code / Review Code / Upload to Chat 随语言设置实时变化</li>
              <li>【优化】Explain/Review 流式输出 — 改为流式 SSE 逐 token 显示，去掉领域限制提示，首 token 即展示，响应速度提升 30-50%</li>
            </ul>
            <h3>v2.5.6</h3>
            <ul>
              <li>优化图像处理</li>
            </ul>
            <h3>v2.5.5</h3>
            <ul>
              <li>agent模式流水线优化</li>
              <li>新增agent模式Token 统计</li>
            </ul>
            <h3>v2.5.4</h3>
            <ul>
              <li>NVIDIA NIM 模型支持+OpenRouter 模型支持— 新增 NVIDIA API Provider（z-ai/glm-5.2、minimaxai/minimax-m3、stepfun-ai/step-3.7-flash）+OpenRouter API Provider，支持下拉框选择模型</li>
              <li>Agent 规划/编码阶段使用当前 Provider 模型 — Phase 1（规划）+ Phase 2（编码）不再硬编码 DeepSeek，改为使用 Settings 中选中的 Provider + Model</li>
              <li>Agent Pipeline 各 Phase 独立配置 — Settings 新增「Agent Pipeline (Agent 流水线配置)」区域，可分别为意图确认、规划、编码、审查四个阶段指定 Provider + Model</li>
              <li>设置按钮 + 弹出菜单 — 状态栏左侧新增 ⚙ 齿轮设置按钮，弹出菜单包含：Q&A/Agent 模式切换 + 快捷进入 Agent Pipeline Phase 设置</li>
              <li>修复 NVIDIA API 请求格式 — topP/frequencyPenalty/presencePenalty 参数改为 snake_case，兼容 OpenAI 协议规范</li>
              <li>Q&A 意图分类 — 问答模式下先调用 Phase 0 模型识别用户意图，普通问题直接回答（不扫描项目），报错/选中代码时自动读取源文件上下文</li>
              <li>模型目录弹窗 — Settings 中新增「View Models」按钮，点击弹窗展示各品牌商的可用模型列表</li>
            </ul>
            <h3>v2.5.3</h3>
            <ul>
              <li>图片解析设置升级 — 设置中「StepFun Image Parsing (图像解析)」改为一级标题「Image Parsing (图像解析)」，新增下拉框选择解析模型：Agnes Image 2.1 Flash / StepFun，默认 Agnes</li>
              <li>密钥复用 — Agnes复用自已配置的 API Key，无需额外输入；StepFun 保留独立密钥输入</li>
              <li>剪贴板粘贴图片 — 输入框支持 Ctrl+V 直接粘贴剪贴板图片，自动添加为附件</li>
              <li>图片解析无需模态弹窗 — 图片直接展示在用户消息气泡中，AI 流式气泡显示「解析中...」状态，解析完成后自动喂给 AI 进行二次优化输出，全程无弹窗打断</li>
            </ul>
            <h3>v2.5.2</h3>
            <ul>
              <li>深度领域限制 — 为插件内所有大模型交互注入 CS-only 领域限制提示词，锁定模型为纯计算机科学领域专用 AI 助手</li>
              <li>智能体自我介绍 — 非 CS 话题越界时，模型以完整但简洁的自我介绍替代冷冰冰的拒绝话术，引导用户回到技术话题</li>
              <li>绝对越狱防御 — 对非 CS 话题执行主题白名单过滤，逐字输出固定自我解释，严禁添加任何额外内容</li>
              <li>边缘裁决准则 — 涉及技术伦理或选型对比时，仅从时间复杂度、吞吐量等纯技术量化指标分析</li>
              <li>输出格式规范 — 强制结构化 Markdown 分层回复、代码语法高亮标注语言类型、承认未知概念禁止幻觉</li>
              <li>全覆盖注入 — 限制生效于全部 6 条 LLM 路径：Q&A / Agent 多阶段 / 右键菜单 Agent / 代码补全 / Agentic Search / 翻译</li>
            </ul>
            <h3>v2.5.1</h3>
            <ul>
              <li>变更管理面板— Agent 模式修改文件后，不再在源目录生成 .bak 文件，改为统一记录在「变更管理」面板中</li>
              <li>工具栏新增「变更管理」按钮（使用原生图标），点击进入变更管理页面</li>
              <li>每次 Agent 操作自动创建变更记录，标题格式：「对xx需求的变更」+ 时间戳</li>
              <li>记录可展开查看具体修改的文件列表</li>
              <li>↩ 每个文件提供「回滚」按钮，一键还原原始内容</li>
              <li>👁 每个文件提供「查看变更」按钮，打开 IntelliJ Diff 窗口对比差异</li>
            </ul>
            <h3>v2.5.0</h3>
            <ul>
              <li>引入多智能体分工协作 — Agent 模式拆分为三个专用 Agent 流水线作业</li>
              <li>规划 Agent（DeepSeek-V4-Pro）— 分析需求，制定代码修改计划</li>
              <li>编码 Agent（DeepSeek-V4-Flash）— 根据计划生成具体的代码修改</li>
              <li>审查 Agent（Agnes-2.0-Flash）— 审查代码质量、安全性和正确性</li>
              <li>Q&A 模式保持单智能体，不受影响</li>
              <li>API 客户端扩展 — 新增 chatStreamWithExplicitConfig 和 chatSyncWithExplicitConfig 方法，支持按 Agent 指定不同模型/Provider</li>
              <li>Agent 模式文件操作备份机制保持不变，修改前自动创建 .bak 备份</li>
            </ul>
            <h3>v2.4.4</h3>
            <ul>
              <li>增加技能设置功能 — 会话栏新增齿轮图标设置按钮，点击进入全区域技能管理面板</li>
              <li>支持上传技能文件（.md/.txt/.yaml）— 按钮上传和拖拽上传两种方式</li>
              <li>技能列表管理 — 启用/停用切换、内容预览、删除技能</li>
              <li>技能注入系统提示 — 已启用的技能内容自动追加到 Agent 模式的 system prompt 中，约束和规范 AI 行为</li>
              <li>社区 Skill 库链接 — 设置面板内提供指向社区 Skill 库的快捷跳转</li>
            </ul>
            <h3>v2.4.3</h3>
            <ul>
              <li>引入 Agentic Search（Agentic 代码搜索）— 替代 RAG 进行代码检索：模型可像人类程序员一样自主调用 grep/glob/read 工具「搜索→阅读→判断→再搜索」，代码精确匹配远超 BM25 语义检索</li>
              <li>RAG 回归文本/文档检索 — RagIndexer 仅索引 .md/.txt/.rst 等文档文件，代码搜索使用 AgenticSearch（grep/glob/read）实现零延迟、高精度匹配</li>
              <li>新增 Agentic Search 配置 — 可在设置中启用/禁用，配置搜索轮次（1=单轮快速模式，>1=多轮深度搜索模式）</li>
              <li>增加查询自动分类 — 系统自动判断用户问题是代码相关还是文档相关，选择最优检索策略</li>
            </ul>
            <h3>v2.4.2</h3>
            <ul>
              <li>引入 RAG（BM25 + 按行分块）— 用于文本/文档检索</li>
            </ul>
            <h3>v2.4.1</h3>
            <ul>
              <li>流式回复消息框随内容自动撑开 — 移除 JBScrollPane 固定高度，streamTextArea 直接放入 BorderLayout，每次 token 追加后 revalidate() 触发布局重算，气泡随 AI 逐字输出逐渐拉长，不再等待完整生成后跳变</li>
              <li>每个消息气泡独立自绘圆角背景 — 扁平化架构，移除 card/outer/wrapper/padded 四层嵌套，MessageBubble 改为 GridBagLayout 自绘，一个气泡尺寸变化不影响其他气泡布局</li>
              <li>代码块排版紧凑化 — 字体 13→12、内边距 14x18→8x14、HTML 行高设为 1.35，代码显示更紧凑</li>
            </ul>
            <h3>v2.4.0</h3>
            <ul>
              <li>支持 PyCharm / DataGrip 等非 Java IDE — Java 依赖改为可选，无 Java 插件的 IDE 中聊天/Agent/AI 补全正常使用，Java 专属功能（静态分析、注解/注释感知补全）优雅降级</li>
              <li>按钮悬浮提示修复 — 用量查看、会话历史、新建会话等 9 个按钮的 tooltip 现在正常显示</li>
            </ul>
            <h3>v2.3.5</h3>
            <ul>
              <li>修复换行失效、输入框截断</li>
            </ul>
            <h3>v2.3.4</h3>
            <ul>
              <li>新增代码变更预览 — Generate/Optimize 生成代码后弹出 side-by-side 对比页面，用户审查后再确认是否保存</li>
              <li>新增翻译对话框 — 聊天输入栏上传按钮旁添加翻译入口，弹出横向双栏翻译界面，支持 10 种语言互译</li>
            </ul>
            <h3>v2.3.3</h3>
            <ul>
              <li>深色扁平化 UI 重构 — 所有消息卡片改用大圆角绘制 (arc=18)，暗色背景统一 #24242A</li>
              <li>统一紫色圆形头像 — 用户 (toolwindow.svg) 与 AI (action.svg) 均为 26×26 紫色圆形，视觉一致</li>
              <li>用户头像右侧添加 "me" 名称标签，与 AI "DP Helper" 对称</li>
              <li>卡片内边距均衡 — 统一 EmptyBorder(12,16,16,16)，消除顶部多余空白</li>
              <li>删除按钮复用系统图标 — 替换 emoji 为 AllIcons.Actions.Close，与清空按钮一致</li>
              <li>代码面板完整渲染 — 移除 JBScrollPane，全部代码行无截断无滚动，内边距增至 14×18</li>
              <li>新增可视化表格组件 — MessageTable 一体化 GridBagLayout 网格，支持 ✓/✕ 彩色标识、自动换行</li>
              <li>Markdown 管道表格自动识别 — parseResponse() 自动检测 |...| 表格语法，渲染为 MessageTable</li>
            </ul>
            <h3>v2.3.2</h3>
            <ul>
              <li>新增功能：Upload to Chat（控制台选中内容上传到聊天面板）</li>
            </ul>            
            <h3>v2.3.1</h3>
            <ul>
              <li>架构优化</li>
            </ul>
            <h3>v2.3.0</h3>
            <ul>
              <li>注解感知补全 — 自动分析 @Data/@Service/@RestController/@Entity 等注解，推荐合成方法（Lombok getter/setter/builder）和代码模式（Spring REST 骨架、JPA 映射、构造器注入）</li>
              <li>注释感知补全 — 在注释中写自然语言即可生成代码，支持中文：<code>// 根据id删除订单</code> → deleteById、<code>// 查询所有</code> → findAll、<code>// 获取name字段</code> → getName 等 30+ 种模式</li>
              <li>@注解名自动补全 — 在类/方法/字段/参数位置输入 @ 时，自动推荐当前上下文适用的注解列表</li>
              <li>新增独立设置开关 — 可分别开启/关闭"注解感知补全"和"注释感知补全"</li>
              <li>静态分析前置过滤 — 基于 IntelliJ PSI/AST 的静态补全作为 AI 补全的前置过滤层，减少不必要的 API 调用</li>
            </ul>
            <h3>v2.2.5</h3>
            <ul>
              <li>发送和换行改为 Enter / Shift+Enter</li>
            </ul>
            <h3>v2.2.4</h3>
            <ul>
              <li>消息面板响应式布局 — 消息气泡自适应宽度，窗口缩放时动态重排，消除气泡上下多余空白</li>
              <li>连续分栏布局 — 拖拽消息与输入区域之间的分隔条实时更新内容</li>
              <li>输入区域拖拽手柄现在直接调整分隔条位置，操作更可控</li>
              <li>代码块最大高度从 300px 提升至 1500px，适配长代码片段</li>
              <li>收紧消息气泡内边距，减少用户/助手消息周围的垂直空白</li>
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

        /**
         * English version of the changelog HTML.
         */
        private val CHANGELOG_HTML_EN = """
            <h3>v2.7.1</h3>
            <ul>
              <li>[New] Zhipu configuration options</li>
            </ul>
            <h3>v2.7.0</h3>
            <ul>
              <li>Switch to Agnes is available in China</li>
              <li>[New] MCP Server — Launches an MCP (Model Context Protocol) server within the IDE, exposing IDEA capabilities via SSE/HTTP endpoint. External AI clients (Claude Desktop, Cursor, etc.) can connect to invoke tools: file read/write, code search, symbol navigation, and more</li>
              <li>[New] MCP Settings — New "MCP Server" tab in theme settings: enable/disable, port (default 8080), auto-start on IDE launch</li>
              <li>[New] 8 built-in MCP Tools — read_file / write_file / list_directory / search_in_project / find_symbol / get_project_info / get_open_files / get_active_editor_content</li>
              <li>[New] Extension Point — Exposes mcpToolProvider EP for other plugins to register custom MCP Tools</li>
            </ul>
            <h3>v2.6.2</h3>
            <ul>
              <li>[New] Reasoning toggle — New "Show Reasoning" checkbox in settings menu; when disabled, hides AI reasoning content in Q&A and Q&A Full Scan modes (Agent mode unaffected)</li>
              <li>[Improved] Stop preserves content — Clicking Stop now retains streamed content and renders it as a complete message bubble (Markdown/code blocks/tables) instead of discarding it</li>
              <li>[Fixed] Agent left-alignment — Fixed excessive left margin on text below code blocks in Agent mode</li>
            </ul>
            <h3>v2.6.1</h3>
            <ul>
            <li>Layout optimization</li>
            </ul>
            <h3>v2.6.0</h3>
            <ul>
              <li>[New] Reasoning display — AI reasoning content (reasoning_content) shown in collapsible panel with word count</li>
              <li>[New] Font Size setting — Theme settings now include "Font Size" dropdown (12px~16px)</li>
              <li>[New] Unified Language setting — "UI Language" and "AI Output Language" merged into single "Language"</li>
              <li>[New] OpenRouter default model changed to inclusionai/ling-3.0-flash:free; model selector now editable</li>
              <li>[Improved] Code Completion — Ghost Text enabled by default, status bar restored, failure notifications, Python pre-check</li>
              <li>[Improved] Code Completion — Streaming FIM: SSE token-by-token, first token appears immediately as Ghost Text</li>
              <li>[Improved] Code Completion — New Ctrl+Shift+, shortcut to toggle on/off</li>
            </ul>
            <h3>v2.5.9</h3>
            <ul>
            <li>Optimized intent parsing in agent mode</li>
            </ul>
            <h3>v2.5.8</h3>
            <ul>
            <li>UI optimization</li>
            </ul>
            <h3>v2.5.7</h3>
            <ul>
              <li>[New] Right-click "Set Language" — One-click jump to theme settings, independently control agent output language (isolated from chat panel)</li>
              <li>[New] Independent Agent Language Setting — New "Agent Output Language" dropdown in theme settings, supports 中文/English toggle</li>
              <li>[Improved] Dynamic menu text — Set Language / Explain Code / Review Code / Upload to Chat update in real-time as language changes</li>
              <li>[Improved] Explain/Review streaming output — Switched to SSE streaming (token-by-token), removed domain restriction prompt, first token appears immediately — 30-50% faster response</li>
            </ul>
            <h3>v2.5.6</h3>
            <ul>
              <li>Optimize image processing</li>
            </ul>
            <h3>v2.5.5</h3>
            <ul>
            <li>Optimized agent mode pipeline</li>
            <li>Added agent mode Token statistics</li>
            </ul>
            <h3>v2.5.4</h3>
            <ul>
              <li>NVIDIA NIM Support+OpenRouter Support — New NVIDIA API Provider with model selection dropdown: z-ai/glm-5.2, minimaxai/minimax-m3, stepfun-ai/step-3.7-flash</li>
              <li>Agent Phase 1+2 Uses Current Provider — Planning and Coding phases no longer hardcode DeepSeek; they use the provider and model selected in Settings</li>
              <li>Per-Phase Agent Pipeline Configuration — New "Agent Pipeline" settings section lets you assign independent Provider + Model for each phase: Intent Confirmation, Planning, Coding, Review</li>
              <li>Settings Button + Popup Menu — Gear icon on the status bar left side replaces the mode dropdown; popup menu includes Q&A/Agent mode switch and quick access to Agent Pipeline Phase settings</li>
              <li>Fixed NVIDIA API Request Format — topP/frequencyPenalty/presencePenalty parameters now use snake_case for OpenAI protocol compatibility</li>
              <li>Q&A Intent Classification — Q&A mode now classifies user intent via Phase 0 model first: general questions answered directly (no project scan), errors/selected code trigger source file reading + project scan</li>
              <li>Model Catalog Dialog — New "View Models" button in Settings shows a dialog listing all available models per provider</li>
            </ul>
            <h3>v2.5.3</h3>
            <ul>
              <li>Image Parsing Settings Upgrade — Changed "StepFun Image Parsing" to a top-level "Image Parsing" section with a dropdown to select the parsing model: Agnes Image 2.1 Flash / StepFun (default: Agnes)</li>
              <li>Key Reuse — Agnes reuse already-configured API keys; StepFun retains its own dedicated key field</li>
              <li>Clipboard Image Paste — Input area now supports Ctrl+V to paste images from clipboard, automatically added as attachments</li>
              <li>No More Modal Dialogs — Images are displayed inline in the user message bubble; the AI streaming bubble shows "Parsing..." status; parsed results are automatically fed to the AI for a second-pass optimization — all without blocking popups</li>
            </ul>
            <h3>v2.5.2</h3>
            <ul>
              <li>Deep Domain Restriction — Injected a CS-only domain restriction prompt into every LLM interaction path within the plugin, locking the model as a pure computer science AI assistant</li>
              <li>Agent Self-Introduction — When users ask non-CS questions, the model responds with a concise self-introduction instead of a cold rejection, gently guiding them back to technical topics</li>
              <li>Absolute Jailbreak Defense — Topic whitelist filtering for non-CS subjects; verbatim output of the fixed self-intro with no additional content allowed</li>
              <li>Edge Case Ruling — When addressing technical ethics or technology comparison, analysis is limited to purely quantitative metrics (time complexity, throughput, etc.)</li>
              <li>Output Format Standards — Enforced structured Markdown hierarchical replies, syntax-highlighted code blocks with language labels, and admitting unknown concepts instead of hallucinating</li>
              <li>Full Coverage — Restriction applied to all 6 LLM paths: Q&A, multi-stage Agent, right-click Agent actions, code completion (FIM), Agentic Search, and Translation</li>
            </ul>
            <h3>v2.5.1</h3>
            <ul>
               <li>Change Management Panel — Agent mode no longer creates .bak files; all changes are now recorded in the unified Change Management panel</li>
               <li>New "Change Management" toolbar button (native icon) to access the panel</li>
               <li>Each Agent operation auto-creates a change record with title format: "Change for 'xxx request'" + timestamp</li>
               <li>Records are expandable to show the list of modified files</li>
               <li>↩ Each file has a "Rollback" button to restore original content with one click</li>
               <li>👁 Each file has a "View Changes" button that opens the IntelliJ Diff window</li>
            </ul>
            <h3>v2.5.0</h3>
            <ul>
              <li>Multi-Agent collaboration introduced — Agent mode split into three specialized agents working in a pipeline</li>
              <li>Planner Agent (DeepSeek-V4-Pro) — analyzes requirements and produces a structured code modification plan</li>
              <li>Coder Agent (DeepSeek-V4-Flash) — generates concrete code changes based on the plan</li>
              <li>Reviewer Agent (Agnes-2.0-Flash) — reviews code quality, security, and correctness</li>
              <li>Q&A mode remains single-agent, unaffected</li>
              <li>API client extended — new chatStreamWithExplicitConfig and chatSyncWithExplicitConfig methods for per-agent model/provider configuration</li>
              <li>Agent mode file backup mechanism preserved — .bak backup created before every file modification</li>
            </ul>
            <h3>v2.4.4</h3>
            <ul>
              <li>New Skill Settings — gear icon added to the toolbar for accessing the full-area skill management panel</li>
              <li>Upload skill files (.md/.txt/.yaml) — via file chooser button or drag-and-drop</li>
              <li>Skill list management — enable/disable toggle, content preview, delete skill</li>
              <li>Skills injected into system prompt — enabled skills are automatically appended to the Agent mode system prompt to constrain and guide AI behavior</li>
              <li>Community Skill Library link — quick-access link in the settings panel to the community-maintained skill collection</li>
            </ul>
            <h3>v2.4.3</h3>
            <ul>
              <li>Agentic Search replaces RAG for code retrieval — the model autonomously calls grep/glob/read tools like a human programmer: search, read, judge, re-search. Deterministic exact matching far beyond BM25 semantic retrieval</li>
              <li>RAG scoped to text/document retrieval — indexes only .md/.txt/.rst etc. documentation files</li>
              <li>New Agentic Search settings — enable/disable, configure search rounds (1=fast single-round, >1=deep multi-round)</li>
              <li>Auto query classification — system detects code vs doc queries for optimal search strategy</li>
            </ul>
            <h3>v2.4.2</h3>
            <ul>
              <li>RAG (BM25 + line-based chunking) — for text/document retrieval</li>
            </ul>
            <h3>v2.4.1</h3>
            <ul>
              <li>Streaming message bubble now auto-expands with content — Removed JBScrollPane fixed height, streamTextArea placed directly in BorderLayout, revalidate() triggers layout recalc on each token append, the bubble grows smoothly as AI generates text instead of jumping to full height on completion</li>
              <li>Each message bubble is now a self-contained unit — Flattened architecture, removed 4 layers of nesting (card/outer/wrapper/padded), MessageBubble uses GridBagLayout and self-draws its rounded background; one bubble's resize no longer affects others</li>
              <li>Tighter code block layout — Font 13→12, padding 14×18→8×14, HTML line-height set to 1.35 for a more compact display</li>
            </ul>
            <h3>v2.4.0</h3>
            <ul>
              <li>Cross-IDE support — Java dependency is now optional; chat panel, Agent actions, and AI completion work in PyCharm / DataGrip etc., Java-specific features (static analysis, annotation/comment-aware completion) gracefully degrade</li>
              <li>Button tooltip fix — All 9 toolbar buttons now show tooltips on hover</li>
            </ul>
            <h3>v2.3.5</h3>
            <ul>
              <li>Fixed broken line breaks and input box truncation</li>
            </ul>
            <h3>v2.3.4</h3>
            <ul>
              <li>New code diff preview — Generate/Optimize actions now show a side-by-side diff before applying, user reviews and confirms changes</li>
              <li>New translate dialog — Added translate button next to file upload in chat input bar, opens a two-column translation UI supporting 10 languages</li>
            </ul>
            <h3>v2.3.3</h3>
            <ul>
              <li>Dark flat UI redesign — All message cards use large rounded corners (arc=18), unified dark background #24242A</li>
              <li>Unified purple circular avatars — User (toolwindow.svg) and AI (action.svg) both 26×26 purple circles for consistent look</li>
              <li>Added "me" label next to user avatar, symmetric with AI's "DP Helper"</li>
              <li>Balanced card padding — Unified EmptyBorder(12,16,16,16), eliminated excess top whitespace</li>
              <li>Delete button uses system icon — Replaced emoji with AllIcons.Actions.Close, matching the clear button</li>
              <li>Code panel full rendering — Removed JBScrollPane, all code lines fully visible without scrolling, padding increased to 14×18</li>
              <li>New visual table component — MessageTable with unified GridBagLayout grid, colored ✓/✕ markers, auto-wrapping text</li>
              <li>Auto-detection of Markdown pipe tables — parseResponse() automatically recognizes |...| table syntax and renders as MessageTable</li>
            </ul>
            <h3>v2.3.2</h3>
            <ul>
              <li>New feature: Upload to Chat (upload selected content from the console to the chat panel)</li>
            </ul>
            <h3>v2.3.1</h3>
            <ul>
              <li>Architecture optimization</li>
            </ul>
            <h3>v2.3.0</h3>
            <ul>
              <li>Annotation-aware completion — Automatically analyzes @Data/@Service/@RestController/@Entity annotations and recommends synthetic methods (Lombok getter/setter/builder) and code patterns (Spring REST skeleton, JPA mapping, constructor injection)</li>
              <li>Comment-aware completion — Write natural language in comments to generate code, supporting Chinese: <code>// 根据id删除订单</code> → deleteById, <code>// 查询所有</code> → findAll, <code>// 获取name字段</code> → getName and 30+ other patterns</li>
              <li>@-annotation auto-completion — When typing @ on a class/method/field/parameter, automatically recommends applicable annotations for the current context</li>
              <li>New independent settings toggles — Enable/disable "Annotation-aware completion" and "Comment-aware completion" separately</li>
              <li>Static analysis pre-filter — IntelliJ PSI/AST-based static completion as a pre-filter layer for AI completion, reducing unnecessary API calls</li>
            </ul>
            <h3>v2.2.5</h3>
            <ul>
              <li>Send and newline changed to Enter / Shift+Enter</li>
            </ul>
            <h3>v2.2.4</h3>
            <ul>
              <li>Responsive message panel layout — Message bubbles auto-fit width, dynamically reflow on window resize, eliminating excess vertical space around bubbles</li>
              <li>Continuous split-pane layout — Dragging the divider between messages and input area updates content in real-time</li>
              <li>Input area drag handle now directly adjusts divider position for more controllable operation</li>
              <li>Max code block height increased from 300px to 1500px for long code snippets</li>
              <li>Tighter padding inside message bubbles, reducing vertical whitespace around user/assistant messages</li>
            </ul>
            <h3>v2.2.3</h3>
            <ul>
              <li>Fixed: button click event handling</li>
            </ul>
            <h3>v2.2.2</h3>
            <ul>
              <li>Fixed: JTextComponent.modelToView(int) → modelToView2D(int)</li>
            </ul>
            <h3>v2.2.1</h3>
            <ul>
              <li>VSCode industrial dark theme input panel — 4-layer vertical layout: notification bar, toolbar, scalable input area, status bar</li>
              <li>Auto-expanding input area — up to 12 lines, scrolls beyond, supports drag-to-resize</li>
              <li>Compact selected-code badge — shows only file name and line range, no code snippet preview</li>
              <li>Attachment upload and UI operations use IntelliJ native icons</li>
            </ul>
            <h3>v2.2.0</h3>
            <ul>
              <li>Multi-model provider support — settings panel can switch between DeepSeek and Agnes 2.0 Flash</li>
              <li>Agnes 2.0 Flash integration — configurable API Key, model name, and Base URL</li>
              <li>Settings UI update — new API Provider selector and Agnes-specific config area</li>
            </ul>
            <h3>v2.1.0</h3>
            <ul>
              <li>Version update dialog — automatically shows release notes after plugin update</li>
              <li>Multiple bug fixes and stability improvements</li>
              <li>Backward compatible with IDEA 24.1.x ~ 26.3.x</li>
            </ul>
            <h3>v2.0.2</h3>
            <ul>
              <li>New Agent mode — switch between "💬 Q&A" and "🤖 Agent" via dropdown at the bottom of the chat panel</li>
              <li>In Agent mode, AI automatically scans the project directory, locates target files, and performs create/modify/delete operations based on user requests</li>
              <li>Recursively collects project source files (skipping .git/build/target/node_modules etc.) as AI context</li>
              <li>AI outputs structured <code>&lt;file path="..."&gt;</code> tags to specify file operations</li>
              <li>Path traversal protection — normalized path validation ensures all write operations stay within the project root</li>
              <li>User confirmation dialog — shows complete file operation list before execution</li>
              <li>Multi-turn conversation support in Agent mode — full message history preserved across consecutive requests</li>
              <li>Send button text changes to "执行 (Enter)" when Agent is active</li>
            </ul>
            <h3>v2.0.1</h3>
            <ul>
              <li>Major UI refactor — ChatPanel split into modular components (MessageBubble, ChatInputBar, ChatToolbar, SessionBar)</li>
              <li>AI replies now support Markdown rendering (bold, italic, inline code, links, lists)</li>
              <li>Message beautification: rounded corners, shadows, circular avatar icons (user/assistant), timestamp display</li>
              <li>Usage and history dialogs extracted into standalone classes</li>
              <li>Optimized packaging — stripped Android/security classes from okhttp JAR to pass Plugin Verifier</li>
              <li>Fixed IntelliJ 2025.1/2025.2 compatibility issues</li>
            </ul>
            <h3>v1.0.5</h3>
            <ul>
              <li>New Question Navigator sidebar — shows 6 nodes at a time, dynamically updates on scroll, fixed issue where clicking a node didn't scroll to the corresponding reply</li>
              <li>Message bubbles now auto-fit to width (75% of container), more balanced and readable layout</li>
            </ul>
            <h3>v1.0.4</h3>
            <ul>
              <li>File upload button in chat input area — click to select local file, displayed as upload preview card above the input area; file content sent as context with the message</li>
              <li>Selected code preview card — shows file, line range, and first 3 lines of selected code, supports one-click close</li>
              <li>CodeBlockCard extracted as reusable UI component with copy and insert buttons</li>
              <li>Clear button moved to session bar for easier session management</li>
            </ul>
            <h3>v1.0.3</h3>
            <ul>
              <li>Fixed AI reply consuming double tokens issue</li>
              <li>Fixed right-click context menu icon size issue</li>
              <li>Fixed Agent result dialog size issue</li>
              <li>Improved code completion: single-suggestion mode, optimized FIM prompt</li>
            </ul>
            <h3>v1.0.0</h3>
            <ul>
              <li>AI-powered inline code completion</li>
              <li>Streaming chat Q&A panel</li>
              <li>Agent mode: explain code, generate code, review code, optimize code</li>
              <li>Right-click context menu integration</li>
              <li>API Key configuration page</li>
            </ul>
        """.trimIndent()
    }
}
