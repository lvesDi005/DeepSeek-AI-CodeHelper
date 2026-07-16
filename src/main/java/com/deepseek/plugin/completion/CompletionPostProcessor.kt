package com.deepseek.plugin.completion

import com.intellij.openapi.diagnostic.Logger

/**
 * 补全结果后处理器。
 *
 * 借鉴 deepseek-copilot 的 CompletionPostProcessor，
 * 实现行级 + 字符级重叠消除 + 括号平衡 + 光标标记清理，
 * 确保补全结果不会重复光标后已存在的代码，且语法上合理。
 */
object CompletionPostProcessor {

    private val LOG = Logger.getInstance(CompletionPostProcessor::class.java)

    /** 括号对映射：右括号 → 左括号 */
    private val CLOSE_TO_OPEN = mapOf(
        ')' to '(', '}' to '{', ']' to '[',
        '"' to '"', '\'' to '\''
    )

    /** 所有左括号集合 */
    private val OPEN_CHARS = CLOSE_TO_OPEN.values.toSet()

    /** 所有右括号集合 */
    private val CLOSE_CHARS = CLOSE_TO_OPEN.keys.toSet()

    /** 需要跳过配对验证的字符（引号） */
    private val SYMMETRIC_CHARS = setOf('"', '\'')

    /**
     * 完整后处理管道。
     *
     * 1. 行尾标准化 CRLF/CR → LF
     * 2. 去掉 Markdown 代码块标记
     * 3. 去掉自然语言前言（"Here is the code..."）
     * 4. 去掉与光标后文本（suffix）的重叠部分（行级 + 字符级）
     * 5. 如果模型重复了光标前的最后一个单词，去掉
     * 6. 括号/引号平衡修复（剔除多余闭括号/补全缺失闭括号）
     * 7. 去掉光标标记（<|cursor|> / $0 / [CURSOR]）
     * 8. 清理首尾空白
     *
     * @param raw 模型返回的原始文本
     * @param prefixLastLine 光标所在行的文本（光标前部分）
     * @param suffix 光标后的文本（用于重叠消除）
     * @return 清理后的补全文本
     */
    fun process(raw: String, prefixLastLine: String, suffix: String): String {
        var cleaned = raw

        // 0) 行尾标准化
        cleaned = cleaned.replace("\r\n", "\n").replace('\r', '\n')

        // 1) 去掉 Markdown 代码块标记
        cleaned = cleaned.replace(Regex("```[\\s\\S]*?```"), "").trim()
        cleaned = cleaned.replace(Regex("^```\\w*\\s*", RegexOption.MULTILINE), "")
        cleaned = cleaned.replace(Regex("```\\s*$", RegexOption.MULTILINE), "")

        // 2) 去掉 "Completion:" "Here is the completion:" 等自然语言前言
        cleaned = cleaned.replace(Regex("^(?i)\\s*(completion|here\\s+is|suggestion|result)\\s*[:：]\\s*"), "")

        // 3) 行级 + 字符级重叠消除（核心改进）
        cleaned = findMaxOverlapSuffixPrefix(cleaned, suffix)

        // 4) 如果模型重复了光标前的最后一个单词，去掉
        val lastWord = prefixLastLine.split(Regex("[\\s(){}\\[\\]=;,.]+")).lastOrNull()?.trim() ?: ""
        if (lastWord.isNotBlank() && lastWord.length >= 2 && cleaned.startsWith(lastWord)) {
            cleaned = cleaned.removePrefix(lastWord)
        }

        // 5) 括号/引号平衡修复（新增）
        cleaned = normalizeCharPairs(cleaned)

        // 6) 去掉光标标记
        cleaned = replaceCursorLocation(cleaned)

        // 7) 去掉前置空行
        cleaned = cleaned.trimStart()

        return cleaned
    }

    // ===================== 括号/引号平衡 =====================

    /**
     * 括号/引号平衡修复。
     *
     * 使用栈扫描 LLM 输出的括号/引号对：
     * - 剔除多余的闭括号（模型常多输出 } 或 )）
     * - 补全缺失的闭括号（模型输出被截断时）
     *
     * 支持的符号：() {} [] "" ''
     */
    private fun normalizeCharPairs(text: String): String {
        if (text.isBlank()) return text

        val stack = ArrayDeque<BracketInfo>()
        val result = StringBuilder()

        for (ch in text) {
            when {
                // 左括号：压栈
                ch in OPEN_CHARS -> {
                    stack.addLast(BracketInfo(ch, result.length))
                    result.append(ch)
                }
                // 右括号
                ch in CLOSE_CHARS -> {
                    val expectedOpen = CLOSE_TO_OPEN[ch]
                    if (stack.isEmpty()) {
                        // 多余的闭括号 — 跳过（不追加）
                        continue
                    }
                    if (ch in SYMMETRIC_CHARS && stack.last().char == ch) {
                        // 对称引号配对成功
                        stack.removeLast()
                        result.append(ch)
                    } else if (stack.last().char == expectedOpen) {
                        // 正常配对
                        stack.removeLast()
                        result.append(ch)
                    } else {
                        // 括号不匹配 — 跳过这个多余的闭括号
                        // 例如: text = "func(a }" → 跳过 }
                        continue
                    }
                }
                else -> result.append(ch)
            }
        }

        // 补全未闭合的左括号（只在文本末尾追加）
        // 从栈底到栈顶依次补全（最早打开的括号先闭合）
        if (stack.isNotEmpty()) {
            // 检查栈里每个未闭合的括号，只在末尾补全
            for (info in stack) {
                val closeChar = findCloseChar(info.char)
                if (closeChar != null) {
                    result.append(closeChar)
                }
            }
        }

        return result.toString()
    }

    /** 括号位置信息 */
    private data class BracketInfo(
        val char: Char,
        val position: Int
    )

    /** 查找左括号对应的右括号 */
    private fun findCloseChar(open: Char): Char? {
        return when (open) {
            '(' -> ')'
            '{' -> '}'
            '[' -> ']'
            '"' -> '"'
            '\'' -> '\''
            else -> null
        }
    }

    // ===================== 光标标记清理 =====================

    /**
     * 去掉 LLM 常输出的光标位置标记。
     */
    private fun replaceCursorLocation(text: String): String {
        var result = text
        // 常见的标记格式
        result = result.replace(Regex("<\\|?cursor\\|?>"), "")
        result = result.replace("\\$0", "")
        result = result.replace(Regex("\\[CURSOR]"), "")
        result = result.replace(Regex("\\[cursor]"), "")
        return result.trim()
    }

    // ===================== 行级 + 字符级重叠消除 =====================

    /**
     * 行级 + 字符级重叠消除。
     *
     * 算法思路：
     * 1. 将补全文本按行分割
     * 2. 从补全文本的最后一行开始，尝试与 suffix 的第一行匹配
     * 3. 如果行匹配成功，继续匹配更多行
     * 4. 行级匹配失败时，退回到字符级匹配（从后缀开头向后匹配）
     * 5. 剪掉补全文本中的重叠部分
     *
     * @param completion 模型返回的补全文本
     * @param suffix 光标后的代码文本
     * @return 去除重叠后的补全文本
     */
    private fun findMaxOverlapSuffixPrefix(completion: String, suffix: String): String {
        if (completion.isBlank() || suffix.isBlank()) return completion

        val completionLines = completion.split("\n")
        val suffixLines = suffix.split("\n")

        // ─── 阶段 1: 行级重叠检测 ───
        val maxOverlapLines = minOf(completionLines.size, suffixLines.size)
        var bestLineOverlap = 0

        for (overlap in 1..maxOverlapLines) {
            val compSegment = completionLines.takeLast(overlap).joinToString("\n").trimEnd()
            val suffixSegment = suffixLines.take(overlap).joinToString("\n").trimEnd()
            if (compSegment == suffixSegment) {
                bestLineOverlap = overlap
            }
        }

        if (bestLineOverlap > 0) {
            val trimmed = completionLines.dropLast(bestLineOverlap).joinToString("\n").trimEnd()
            if (trimmed.isNotBlank()) {
                return trimmed
            }
        }

        // ─── 阶段 2: 字符级重叠检测 ───
        val compText = completion.trimEnd()
        val suffixText = suffix.trimStart()

        var bestCharOverlap = 0
        for (overlapLen in suffixText.length downTo 2) {
            val suffixStart = suffixText.take(overlapLen)
            if (compText.endsWith(suffixStart)) {
                bestCharOverlap = overlapLen
                break
            }
        }

        if (bestCharOverlap > 0) {
            val trimmed = compText.dropLast(bestCharOverlap).trimEnd()
            if (trimmed.isNotBlank()) {
                return trimmed
            }
        }

        return completion.trimEnd()
    }
}
