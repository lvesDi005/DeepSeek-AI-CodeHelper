package com.deepseek.plugin.agent

import com.deepseek.plugin.i18n.I18n

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

    override val progressTitle = I18n.tr("agent.explaining")
    override val emptySelectionMessage = I18n.tr("agent.select.code.explain")
    override val menuTextKey = "agent.menu.explain"
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

    override val progressTitle = I18n.tr("agent.reviewing")
    override val emptySelectionMessage = I18n.tr("agent.select.code.review")
    override val menuTextKey = "agent.menu.review"
}


