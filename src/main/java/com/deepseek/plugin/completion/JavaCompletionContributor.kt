package com.deepseek.plugin.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns

/**
 * Java 代码补全贡献者 —— 仅在 Java 插件存在时注册。
 *
 * 包含基于 PSI/AST 的静态分析前置过滤，
 * 以及注解感知 / 注释感知补全。
 */
class JavaCompletionContributor : CompletionContributor() {

    init {
        // 静态分析 Provider —— 优先执行，候选充足时跳过 AI
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            StaticAnalysisCompletionProvider()
        )
    }
}
