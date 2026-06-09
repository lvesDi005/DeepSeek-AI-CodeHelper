package com.deepseek.plugin.agent

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project

/**
 * Ask DeepSeek about the selected code — general Q&A.
 * Shows response in a dialog.
 */
class AskAction : BaseAgentAction() {

    override val systemPrompt = """You are an expert software developer.
Answer questions about the provided code concisely and clearly.
If the user asks a question, answer it directly. If no explicit question is asked,
provide a helpful analysis of what the code does."""

    override val progressTitle = "Asking DeepSeek..."
    override val emptySelectionMessage = "Select code or open a file to ask questions about it."
}

/**
 * Explain the selected code in detail.
 * Shows response in a dialog with the explanation.
 */
class ExplainAction : BaseAgentAction() {

    override val systemPrompt = """You are an expert code explainer.
Explain the provided code in detail:
1. Overall purpose — what does this code do?
2. Logic flow — step-by-step explanation
3. Key components — important functions, classes, patterns
4. Potential issues or edge cases
Be thorough but clear. Use Chinese if the code/context appears Chinese."""

    override val progressTitle = "Explaining Code..."
    override val emptySelectionMessage = "Select code to explain."
}

/**
 * Generate code based on natural language description or selected code context.
 * Replaces the selected code with the generated result.
 */
class GenerateAction : BaseAgentAction() {

    override val systemPrompt = """You are an expert code generator.
Given the user's description or selected code context, generate high-quality,
production-ready code. Include:
- Proper error handling
- Clear variable names
- Appropriate comments
- Follow best practices for the language/framework
Return ONLY the generated code (wrapped in ```language ... ```). No explanations unless asked."""

    override val progressTitle = "Generating Code..."
    override val emptySelectionMessage = "Type a description of the code you want to generate, or select existing code as context."

    override fun showResult(project: Project, originalCode: String, response: String) {
        val code = extractCodeBlock(response) ?: response
        val editor = capturedEditor

        if (editor != null) {
            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                val selectionModel = editor.selectionModel
                if (selectionModel.hasSelection()) {
                    document.replaceString(selectionModel.selectionStart, selectionModel.selectionEnd, code)
                } else {
                    val offset = editor.caretModel.offset
                    document.insertString(offset, code)
                }
            }
        } else {
            super.showResult(project, originalCode, response)
        }
    }

    private fun extractCodeBlock(response: String): String? {
        val regex = Regex("```(?:\\w+)?\\s*\\n?([\\s\\S]*?)```")
        val match = regex.find(response) ?: return null
        return match.groupValues[1].trim()
    }
}

/**
 * Review the selected code with suggestions for improvement.
 */
class ReviewAction : BaseAgentAction() {

    override val systemPrompt = """You are a senior code reviewer. Review the provided code:
1. Code quality — readability, naming, structure
2. Potential bugs — logic errors, edge cases, null safety
3. Performance issues — bottlenecks, inefficient patterns
4. Security concerns — injection risks, exposed data
5. Best practices — language idioms, framework conventions
6. Specific suggestions for improvement (with code examples)

Format your response with clear sections. Be constructive, not judgmental."""

    override val progressTitle = "Reviewing Code..."
    override val emptySelectionMessage = "Select code to review."
}

/**
 * Optimize the selected code for performance and readability.
 * Replaces the selected code with the optimized version.
 */
class OptimizeAction : BaseAgentAction() {

    override val systemPrompt = """You are an expert code optimizer. Optimize the provided code:
- Improve time and space complexity
- Eliminate redundant operations
- Use efficient data structures and algorithms
- Maintain readability — don't sacrifice clarity for micro-optimizations
- Preserve the original behavior exactly
Return ONLY the optimized code wrapped in ```language ... ```. Add brief inline comments noting what changed."""

    override val progressTitle = "Optimizing Code..."
    override val emptySelectionMessage = "Select code to optimize."

    override fun showResult(project: Project, originalCode: String, response: String) {
        val code = extractCodeBlock(response) ?: response
        val editor = capturedEditor

        if (editor != null) {
            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                val selectionModel = editor.selectionModel
                if (selectionModel.hasSelection()) {
                    document.replaceString(selectionModel.selectionStart, selectionModel.selectionEnd, code)
                } else {
                    super.showResult(project, originalCode, response)
                }
            }
        } else {
            super.showResult(project, originalCode, response)
        }
    }

    private fun extractCodeBlock(response: String): String? {
        val regex = Regex("```(?:\\w+)?\\s*\\n?([\\s\\S]*?)```")
        val match = regex.find(response) ?: return null
        return match.groupValues[1].trim()
    }
}
