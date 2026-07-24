package com.deepseek.plugin.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore

/**
 * Python 专属的 PSI 预检层 — 识别不需要 AI 补全的场景，
 * 直接返回本地候选，减少不必要的 API 调用。
 *
 * 在 PyCharm 中等效于 Java 的 [StaticAnalysisCompletionProvider]。
 * 通过 plugin.xml 的 optional 依赖 + 反射加载（需 Python 插件存在）。
 */
object PythonCompletionPreChecker {

    private val LOG = Logger.getInstance(PythonCompletionPreChecker::class.java)

    /** Python 内置函数集合 */
    private val PYTHON_BUILTINS = setOf(
        "print", "len", "str", "int", "float", "list", "dict", "set", "tuple",
        "range", "enumerate", "zip", "map", "filter", "sorted", "reversed",
        "open", "input", "type", "isinstance", "hasattr", "getattr", "setattr",
        "abs", "max", "min", "sum", "any", "all", "round", "pow",
        "format", "repr", "ord", "chr", "bin", "hex", "oct",
        "bool", "bytes", "bytearray", "memoryview",
        "iter", "next", "slice", "super", "object",
        "property", "staticmethod", "classmethod",
        "__init__", "__str__", "__repr__", "__len__", "__getitem__",
        "__setitem__", "__call__", "__enter__", "__exit__",
    )

    /** Python 关键字集合 */
    private val PYTHON_KEYWORDS = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await",
        "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield",
    )

    /** 常见 Python 魔术方法前缀 */
    private val MAGIC_METHOD_PREFIXES = listOf("__", "__i", "__a")

    /**
     * 判断当前上下文是否适合用 AI 补全。
     * @return true=继续走 AI, false=跳过 AI（本地已足够）
     */
    fun shouldUseAI(element: PsiElement?, file: PsiFile?, prefix: String): Boolean {
        if (element == null || file == null) return true

        // 仅对 Python 文件生效
        val language = PsiUtilCore.getLanguageAtOffset(file, element.textOffset)
        if (!language.id.equals("Python", ignoreCase = true) &&
            !file.name.endsWith(".py")) return true

        // 1. 内置函数/关键字 → 不需要 AI
        val trimmedPrefix = prefix.trim()
        val lastWord = trimmedPrefix.split("[^a-zA-Z0-9_.]".toRegex()).lastOrNull()
            ?.trim() ?: ""
        if (lastWord.isNotEmpty()) {
            if (lastWord in PYTHON_BUILTINS) return false
            if (lastWord in PYTHON_KEYWORDS) return false
        }

        // 2. 可能的魔术方法 → 不需要 AI（IDE 已有补全）
        if (lastWord.startsWith("__")) return false

        // 3. 数字字面量或简单运算符后 → 不需要 AI
        if (trimmedPrefix.matches(Regex(".*\\d$"))) return false
        if (trimmedPrefix.endsWith(".") || trimmedPrefix.endsWith(",")
            || trimmedPrefix.endsWith("(") || trimmedPrefix.endsWith("[")) {
            return true // 这些情况可能需要 AI 补全
        }

        return true // 默认走 AI
    }
}
