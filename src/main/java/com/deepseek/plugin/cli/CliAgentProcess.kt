package com.deepseek.plugin.cli

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 Claude Code / Codex CLI Agent 进程封装（第四种模式「Claude/Codex」）。
 *
 * spawn 官方 CLI 非交互模式，stdin 喂 prompt，stdout 按行解析 JSON 事件流：
 *  - claude：`claude -p --input-format text --output-format stream-json ...`
 *  - codex：`codex exec --json ...`
 *
 * 凭据由 CLI 自行读取本地登录态（~/.claude、~/.codex），插件不注入密钥。
 *
 * @param cliType         "claude" 或 "codex"
 * @param projectDir      CLI 工作目录（项目根）
 * @param permissionMode  acceptEdits(仅文件编辑) | bypass(跳过全部权限) | plan(仅计划)
 * @param prompt          用户问题
 * @param onText          增量文本（后台线程调用，需自行切 EDT）
 * @param onThinking      思考内容（可空）
 * @param onComplete      正常完成（finalText）
 * @param onError         错误（message）
 */
class CliAgentProcess(
    private val cliType: String,
    private val projectDir: File,
    private val permissionMode: String,
    private val prompt: String,
    private val onText: (String) -> Unit,
    private val onThinking: ((String) -> Unit)? = null,
    private val onComplete: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private val parser = JsonParser()

        /** 在 PATH 中查找可执行文件（Windows 自动尝试 .exe/.cmd/.bat） */
        fun findExecutable(name: String): String? {
            val path = System.getenv("PATH") ?: return null
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val exts = if (isWindows) listOf("", ".exe", ".cmd", ".bat") else listOf("")
            for (dir in path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue
                for (ext in exts) {
                    val f = File(dir, name + ext)
                    if (f.isFile && f.canExecute()) return f.absolutePath
                }
            }
            return null
        }
    }

    @Volatile
    private var process: Process? = null
    private val finished = AtomicBoolean(false)
    private val textBuffer = StringBuilder()

    /** 当前进程句柄（供 ChatState 停止用） */
    val currentProcess: Process? get() = process

    fun start() {
        val args = buildArgs()
        val pb = ProcessBuilder(args)
        pb.directory(projectDir)
        pb.redirectErrorStream(false)

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            onError("启动 ${cliType} CLI 失败: ${e.message}")
            return
        }
        process = proc

        // stderr 线程（避免阻塞）
        Thread({
            try {
                proc.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        System.err.println("[cli-agent $cliType stderr] $line")
                    }
                }
            } catch (_: Exception) { }
        }, "cli-agent-stderr").apply { isDaemon = true; start() }

        // stdout 解析线程
        Thread({
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        val l = line?.trim()
                        if (!l.isNullOrEmpty()) parseEvent(l)
                    }
                }
            } catch (_: Exception) { }
        }, "cli-agent-stdout").apply { isDaemon = true; start() }

        // stdin 喂 prompt（关闭 stdin 触发非交互执行）
        Thread({
            try {
                proc.outputStream.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write(prompt)
                }
            } catch (_: Exception) { }
        }, "cli-agent-stdin").apply { isDaemon = true; start() }

        // 退出兜底：stdout EOF 后若尚未完成则收尾
        Thread({
            try {
                proc.waitFor()
            } catch (_: Exception) { }
            if (finished.compareAndSet(false, true)) {
                val finalText = synchronized(textBuffer) { textBuffer.toString() }
                onComplete(finalText)
            }
        }, "cli-agent-wait").apply { isDaemon = true; start() }
    }

    /** 停止：先温和 destroy，超时后强杀 */
    fun stop() {
        val p = process ?: return
        p.destroy()
        Thread({
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            } catch (_: Exception) {
                p.destroyForcibly()
            }
        }, "cli-agent-stop").apply { isDaemon = true; start() }
    }

    // ═══════════════════════════════════════════════════════════
    //  参数构造
    // ═══════════════════════════════════════════════════════════

    private fun buildArgs(): List<String> {
        return when (cliType) {
            "claude" -> listOf(
                "claude", "-p",
                "--input-format", "text",
                "--output-format", "stream-json",
                "--verbose",
                *permissionArgs()
            )
            "codex" -> listOf(
                "codex", "exec", "--json",
                *permissionArgs(),
                prompt
            )
            else -> listOf(cliType, prompt)
        }
    }

    /** 权限参数：claude 用 --permission-mode / --dangerously-skip-permissions；codex 用 --sandbox-mode */
    private fun permissionArgs(): Array<String> {
        return when (cliType) {
            "claude" -> when (permissionMode) {
                "bypass" -> arrayOf("--dangerously-skip-permissions")
                "plan" -> arrayOf("--permission-mode", "plan")
                else -> arrayOf("--permission-mode", "acceptEdits")
            }
            "codex" -> {
                val sandbox = when (permissionMode) {
                    "bypass" -> "danger-full-access"
                    "plan" -> "read-only"
                    else -> "workspace-write"
                }
                arrayOf("--sandbox-mode", sandbox)
            }
            else -> emptyArray()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  事件解析
    // ═══════════════════════════════════════════════════════════

    private fun parseEvent(line: String) {
        val obj = try {
            parser.parse(line) as? JsonObject ?: return
        } catch (_: Exception) {
            return
        }
        when (cliType) {
            "claude" -> parseClaudeEvent(obj)
            "codex" -> parseCodexEvent(obj)
        }
    }

    /** claude stream-json：assistant 消息（content 数组含 text/thinking 块）→ 文本；result → 完成 */
    private fun parseClaudeEvent(obj: JsonObject) {
        when (obj.get("type")?.asString) {
            "assistant" -> {
                val content = obj.getAsJsonObject("message")?.get("content") as? JsonArray ?: return
                for (block in content) {
                    val b = block.asJsonObject
                    when (b.get("type")?.asString) {
                        "text" -> appendText(b.get("text")?.asString ?: "")
                        "thinking" -> {
                            val t = b.get("thinking")?.asString ?: ""
                            if (t.isNotEmpty()) onThinking?.invoke(t)
                        }
                    }
                }
            }
            "result" -> {
                if (finished.compareAndSet(false, true)) {
                    val finalText = synchronized(textBuffer) { textBuffer.toString() }
                    onComplete(finalText)
                }
            }
        }
    }

    /** codex exec --json：assistant message 事件 → 文本；complete → 完成 */
    private fun parseCodexEvent(obj: JsonObject) {
        when (obj.get("type")?.asString) {
            "message" -> {
                val msg = obj.getAsJsonObject("message") ?: return
                if (msg.get("role")?.asString != "assistant") return
                appendMessageContent(msg.get("content"))
            }
            "item" -> {
                // 部分版本用 item.payload 包裹 assistant 消息
                val payload = obj.getAsJsonObject("payload") ?: return
                if (payload.get("role")?.asString != "assistant") return
                appendMessageContent(payload.get("content"))
            }
            "complete" -> {
                if (finished.compareAndSet(false, true)) {
                    val finalText = synchronized(textBuffer) { textBuffer.toString() }
                    onComplete(finalText)
                }
            }
        }
    }

    private fun appendMessageContent(content: Any?) {
        when (content) {
            is String -> if (content.isNotBlank()) appendText(content)
            is JsonArray -> for (block in content) {
                val b = block.asJsonObject
                if (b.get("type")?.asString == "text") appendText(b.get("text")?.asString ?: "")
            }
        }
    }

    private fun appendText(text: String) {
        if (text.isBlank()) return
        synchronized(textBuffer) { textBuffer.append(text) }
        onText(text)
    }
}
