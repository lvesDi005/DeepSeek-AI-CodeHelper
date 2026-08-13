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
        private val isWindows = System.getProperty("os.name").lowercase().contains("win")
        private const val MAX_ERROR_LENGTH = 8_000

        /** 在 PATH 中查找可执行文件（Windows 自动尝试 .exe/.cmd/.bat） */
        fun findExecutable(name: String): String? {
            val direct = File(name)
            if ((direct.isAbsolute || direct.parent != null) && isUsableExecutable(direct)) {
                return direct.absolutePath
            }

            val directories = linkedSetOf<String>()
            System.getenv("PATH")
                ?.split(File.pathSeparator)
                ?.filterTo(directories) { it.isNotBlank() }
            if (isWindows) {
                // The IDE may have captured PATH before a CLI was installed.
                System.getenv("APPDATA")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { directories.add(File(it, "npm").absolutePath) }
                System.getenv("LOCALAPPDATA")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { directories.add(File(it, "Programs\\npm").absolutePath) }
            }

            val extensions = if (isWindows) listOf(".exe", ".cmd", ".bat", "") else listOf("")
            for (dir in directories) {
                for (ext in extensions) {
                    val f = File(dir, name + ext)
                    if (isUsableExecutable(f)) return f.absolutePath
                }
            }
            return null
        }

        private fun isUsableExecutable(file: File): Boolean =
            file.isFile && (isWindows || file.canExecute())

        private fun commandFor(executable: String, arguments: List<String>): List<String> {
            val extension = File(executable).extension.lowercase()
            if (!isWindows || (extension != "cmd" && extension != "bat")) {
                return listOf(executable) + arguments
            }

            val commandInterpreter = System.getenv("ComSpec")?.takeIf { it.isNotBlank() } ?: "cmd.exe"
            val command = (listOf(executable) + arguments).joinToString(" ") { quoteForCmd(it) }
            return listOf(commandInterpreter, "/d", "/s", "/c", "\"$command\"")
        }

        private fun quoteForCmd(argument: String): String =
            "\"${argument.replace("\"", "\"\"")}\""
    }

    @Volatile
    private var process: Process? = null
    private val finished = AtomicBoolean(false)
    private val textBuffer = StringBuilder()
    private val stderrBuffer = StringBuilder()
    @Volatile
    private var eventError: String? = null

    /** 当前进程句柄（供 ChatState 停止用） */
    val currentProcess: Process? get() = process

    fun start() {
        val executable = findExecutable(cliType)
        if (executable == null) {
            onError("Cannot find $cliType CLI executable. Install it and restart the IDE so PATH is refreshed.")
            return
        }
        val pb = ProcessBuilder(commandFor(executable, buildArgs()))
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
        val stderrThread = Thread({
            try {
                proc.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        synchronized(stderrBuffer) {
                            if (stderrBuffer.length < MAX_ERROR_LENGTH) {
                                if (stderrBuffer.isNotEmpty()) stderrBuffer.append('\n')
                                stderrBuffer.append(line)
                            }
                        }
                        System.err.println("[cli-agent $cliType stderr] $line")
                    }
                }
            } catch (_: Exception) { }
        }, "cli-agent-stderr").apply { isDaemon = true; start() }

        // stdout 解析线程
        val stdoutThread = Thread({
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
            val exitCode = try {
                proc.waitFor()
            } catch (_: Exception) {
                -1
            }
            // Process exit can race with the final JSON line being consumed. Drain both
            // readers before notifying the UI so the completed bubble cannot be empty.
            try { stdoutThread.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            try { stderrThread.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            if (finished.compareAndSet(false, true)) {
                val finalText = synchronized(textBuffer) { textBuffer.toString() }
                val failure = eventError ?: if (exitCode != 0) formatProcessError(exitCode) else null
                when {
                    failure != null -> onError(failure)
                    finalText.isBlank() -> onError("$cliType CLI completed without returning an assistant response.")
                    else -> onComplete(finalText)
                }
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
                "-p",
                "--input-format", "text",
                "--output-format", "stream-json",
                "--verbose",
                *permissionArgs()
            )
            "codex" -> listOf(
                "exec", "--json",
                // The IDE project root is not necessarily registered as a trusted
                // Codex workspace; the plugin already supplies the selected sandbox.
                "--skip-git-repo-check",
                *permissionArgs(),
                "-"
            )
            else -> listOf(prompt)
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
                arrayOf("--sandbox", sandbox)
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
                if (obj.get("is_error")?.asBoolean == true) {
                    eventError = obj.get("result")?.asString ?: "Claude CLI reported an error."
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
            "item.completed" -> {
                val item = obj.getAsJsonObject("item") ?: return
                if (item.get("type")?.asString == "agent_message") {
                    appendText(item.get("text")?.asString ?: "")
                }
            }
            "error", "turn.failed" -> {
                val error = obj.getAsJsonObject("error")
                eventError = error?.get("message")?.asString
                    ?: obj.get("message")?.asString
                    ?: "Codex CLI reported an error."
            }
        }
    }

    private fun formatProcessError(exitCode: Int): String {
        val stderr = synchronized(stderrBuffer) { stderrBuffer.toString().trim() }
        return if (stderr.isBlank()) {
            "$cliType CLI exited with code $exitCode."
        } else {
            "$cliType CLI exited with code $exitCode: $stderr"
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
