package com.deepseek.plugin.completion

import com.intellij.codeInsight.completion.CompletionParameters

/**
 * Completion pipeline seam.
 *
 * Encapsulates the shared stages (pre-check, cache, post-processing) so
 * [DeepSeekCompletionProvider] delegates to a single abstraction instead of
 * calling stage implementations directly.
 */
interface CompletionPipeline {
    /** True when the request may proceed past the fast-fail pre-check stage. */
    fun canProceed(parameters: CompletionParameters): Boolean

    /** Look up a cached suggestion. */
    fun getCached(filePath: String, line: Int, column: Int, prefix: String): String?

    /** Store a successful suggestion. */
    fun putCached(filePath: String, line: Int, column: Int, prefix: String, suggestion: String)

    /** Normalize a raw model response before display. */
    fun postProcess(raw: String, prefixLastLine: String, suffix: String): String
}

/**
 * Default implementation backed by the existing focused completion helpers.
 */
class DefaultCompletionPipeline : CompletionPipeline {
    override fun canProceed(parameters: CompletionParameters): Boolean =
        CompletionPreChecker.canProceed(parameters)

    override fun getCached(filePath: String, line: Int, column: Int, prefix: String): String? =
        CompletionCache.getInstance().get(filePath, line, column, prefix)

    override fun putCached(filePath: String, line: Int, column: Int, prefix: String, suggestion: String) {
        CompletionCache.getInstance().put(filePath, line, column, prefix, suggestion)
    }

    override fun postProcess(raw: String, prefixLastLine: String, suffix: String): String =
        CompletionPostProcessor.process(raw, prefixLastLine, suffix)
}