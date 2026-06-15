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
                previousVersion = previousVersion,
                currentVersion = currentVersion,
                changeLogHtml = CHANGELOG_HTML
            )
            dialog.show()

            // Persist the new version so the dialog doesn't show again
            settings.lastSeenVersion = currentVersion
        }
    }

    companion object {
        /**
         * HTML changelog content displayed in the update dialog.
         */
        private val CHANGELOG_HTML = """
            <h3>v2.1.0</h3>
            <ul>
              <li>🎨 New welcome screen — a beautiful landing page with quick tips and keyboard shortcuts when the chat panel is empty</li>
              <li>🆕 Version update dialog — automatically shows the changelog when the plugin is updated to a new version</li>
              <li>🐛 Various bug fixes and stability improvements</li>
            </ul>
            <h3>v2.0.2</h3>
            <ul>
              <li>Added Agent mode — switch between "💬 Q&A" and "🤖 Agent" via a dropdown at the bottom of the chat panel</li>
              <li>In Agent mode, AI automatically scans the project directory, locates target files, and performs create/modify/delete operations based on user requests</li>
              <li>Recursively collects project source files (skipping .git/build/target/node_modules etc.) as context for the AI</li>
              <li>AI outputs structured <code>&lt;file path="..."&gt;</code> tags to specify file operations</li>
              <li>Path traversal protection — canonical path validation ensures all writes stay within the project root</li>
              <li>User confirmation dialog — shows the full list of pending file operations before applying them</li>
              <li>Multi-turn conversation support in Agent mode — maintains full message history across consecutive requests</li>
              <li>Send button label changes to "Execute (Ctrl+Enter)" when Agent mode is active</li>
            </ul>
            <h3>v2.0.1</h3>
            <ul>
              <li>Major UI refactoring — ChatPanel split into modular components (MessageBubble, ChatInputBar, ChatToolbar, SessionBar)</li>
              <li>Markdown rendering for assistant replies (bold, italic, inline code, links, lists) using IntelliJ's bundled markdown library</li>
              <li>Message beautification: rounded corners, drop shadow, circular avatar icons (U/D), timestamp display</li>
              <li>Usage and History dialogs extracted as standalone classes</li>
              <li>Optimized packaging — Android/security platform classes stripped from okhttp JAR to pass Plugin Verifier</li>
              <li>Fixed IntelliJ 2025.1/2025.2 compatibility issues</li>
            </ul>
            <h3>v1.0.5</h3>
            <ul>
              <li>Added a question‑navigation sidebar that shows only 6 nodes at a time, dynamically updating as you scroll, and fixed the issue where clicking a node did not scroll to the corresponding reply</li>
              <li>Message bubbles now use an adaptive width (75% of the container) for a more readable, balanced layout</li>
            </ul>
            <h3>v1.0.4</h3>
            <ul>
              <li>File upload button in chat input — click to select local files, displayed as preview cards above input; file content sent as context with the message</li>
              <li>Selected code preview card above chat input — shows file, line range, and first 3 lines of selected code; supports one-click dismiss</li>
              <li>Modular code block cards (CodeBlockCard) extracted into reusable UI component with copy + insert buttons</li>
              <li>Clear button moved to session bar for easier session management</li>
            </ul>
            <h3>v1.0.3</h3>
            <ul>
              <li>Fixed duplicate AI responses consuming double tokens in chat</li>
              <li>Fixed right-click menu icon being oversized</li>
              <li>Fixed agent result dialog being too large</li>
              <li>Improved code completion: single-suggestion mode, better FIM prompt</li>
            </ul>
            <h3>v1.0.0</h3>
            <ul>
              <li>AI-powered inline code completion</li>
              <li>Streaming chat Q&amp;A panel</li>
              <li>Agent mode: Explain, Generate, Review, Optimize</li>
              <li>Right-click context menu integration</li>
              <li>Settings page for API Key configuration</li>
            </ul>
        """.trimIndent()
    }
}
