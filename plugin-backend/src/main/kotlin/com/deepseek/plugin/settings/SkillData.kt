package com.deepseek.plugin.settings

/**
 * Represents a single uploaded skill file that provides constraints/guidance for the AI model.
 *
 * @param name        The display name of the skill (derived from the file name).
 * @param content     The full text content of the skill file.
 * @param enabled     Whether this skill is currently active and injected into the system prompt.
 * @param filePath    The original file path from which the skill was loaded (for reference).
 * @param tags        User-defined tags (e.g. ["java", "spring", "mybatis"]) for relevance-based injection.
 *                    When non-empty, the skill is only injected if the user's query matches one or more tags.
 *                    When empty, falls back to keyword matching against name/content.
 */
data class SkillData(
    val name: String,
    val content: String,
    val enabled: Boolean = true,
    val filePath: String = "",
    val tags: List<String>? = emptyList()
)
