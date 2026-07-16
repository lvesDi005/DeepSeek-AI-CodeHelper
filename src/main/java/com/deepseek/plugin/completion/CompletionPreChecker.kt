package com.deepseek.plugin.completion

import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile

/**
 * 补全预检查器 — 在触发 AI API 调用前做快速否决检查。
 *
 * 借鉴 deepseek-copilot 的 CompletionPreChecker，
 * 检查链采用"一票否决"模式：
 * 任何一项检查不通过 → 跳过本次补全。
 *
 * 目的：节省 Token 消耗，避免无效 API 调用，提升用户体验。
 */
object CompletionPreChecker {

    private val LOG = Logger.getInstance(CompletionPreChecker::class.java)

    /** 最大文件大小（字节），超过此大小跳过 AI 补全 */
    private const val MAX_FILE_SIZE_BYTES = 100 * 1024 // 100KB

    /** 需要跳过 AI 补全的文件扩展名（小写） */
    private val SKIP_EXTENSIONS = setOf(
        "md", "txt", "log", "csv", "tsv",
        "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "gradle", "properties", "env"
    )

    /** 需要跳过 AI 补全的文件名（精确匹配，小写） */
    private val SKIP_FILE_NAMES = setOf(
        "dockerfile", "makefile", "cmakelists.txt",
        "package.json", "package-lock.json", "yarn.lock",
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        ".gitignore", ".dockerignore", ".editorconfig",
        "tsconfig.json", ".eslintrc", ".prettierrc"
    )

    /** 需要跳过 AI 补全的语言类型 */
    private val SKIP_LANGUAGE_IDS = setOf(
        "json", "xml", "yaml", "text", "properties",
        "csv", "markdown", "log", "plaintext"
    )

    /**
     * 预检查入口。
     *
     * @param parameters 补全参数
     * @return true = 可以继续（通过检查），false = 应跳过
     */
    fun canProceed(parameters: CompletionParameters): Boolean {
        val settings = DeepSeekSettings.instance
        if (!settings.completionEnabled || settings.apiKey.isBlank()) return false

        // --- 1. 多光标检查 ---
        if (parameters.editor.caretModel.caretCount > 1) {
            LOG.debug("PreCheck SKIP: multi-caret (${parameters.editor.caretModel.caretCount})")
            return false
        }

        // --- 2. 只读文档检查 ---
        if (!parameters.editor.document.isWritable) {
            LOG.debug("PreCheck SKIP: read-only document")
            return false
        }

        // --- 3. 文件大小检查 ---
        val file = parameters.originalFile.virtualFile
        if (file != null && file.length > MAX_FILE_SIZE_BYTES) {
            LOG.debug("PreCheck SKIP: file too large (${file.length} bytes)")
            return false
        }

        // --- 4. 文件类型/扩展名检查 ---
        if (shouldSkipByFileType(file, parameters.originalFile.fileType)) {
            return false
        }

        // --- 5. 补充：已扩展补全跳过 ---
        if (parameters.isExtendedCompletion) return false

        return true
    }

    /**
     * 根据文件类型和扩展名判断是否应跳过 AI 补全。
     */
    private fun shouldSkipByFileType(file: VirtualFile?, fileType: FileType): Boolean {
        val languageId = fileType.name.lowercase()
        if (languageId in SKIP_LANGUAGE_IDS) {
            LOG.debug("PreCheck SKIP: language=$languageId")
            return true
        }

        // 纯文本文件：检查扩展名
        if (languageId == "plaintext" || languageId == "text") {
            val fileName = file?.name?.lowercase() ?: return false
            val ext = fileName.substringAfterLast('.', "")

            // 检查扩展名黑名单
            if (ext in SKIP_EXTENSIONS) {
                LOG.debug("PreCheck SKIP: extension=.${ext}")
                return true
            }

            // 检查文件名黑名单
            if (fileName in SKIP_FILE_NAMES) {
                LOG.debug("PreCheck SKIP: filename=$fileName")
                return true
            }
        }

        return false
    }
}
