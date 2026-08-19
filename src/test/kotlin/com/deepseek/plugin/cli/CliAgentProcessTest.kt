package com.deepseek.plugin.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliAgentProcessTest {
    @Test
    fun `codex receives attachment directory and images`() {
        val directory = createTempDirectory("codex-attachments").toFile()
        val image = createTempFile("page", ".png").toFile()
        val args = process("codex", "plan", directory, image).buildArgs()

        assertTrue(args.containsAll(listOf("exec", "--json", "--add-dir", directory.absolutePath)))
        assertTrue(args.containsAll(listOf("--image", image.absolutePath)))
        assertTrue(args.containsAll(listOf("--sandbox", "read-only")))
        assertTrue(args.indexOf("exec") < args.indexOf("--image"))
        assertTrue(args.indexOf("--image") < args.indexOf("--skip-git-repo-check"))
        assertEquals("-", args.last())
    }

    @Test
    fun `claude receives attachment directory without codex image flag`() {
        val directory = createTempDirectory("claude-attachments").toFile()
        val image = createTempFile("page", ".png").toFile()
        val args = process("claude", "acceptEdits", directory, image).buildArgs()

        assertTrue(args.containsAll(listOf("--add-dir", directory.absolutePath)))
        assertTrue(args.containsAll(listOf("--permission-mode", "acceptEdits")))
        assertFalse(args.contains("--image"))
    }

    private fun process(cliType: String, permission: String, directory: File, image: File) =
        CliAgentProcess(
            cliType = cliType,
            projectDir = directory,
            permissionMode = permission,
            prompt = "inspect attachments",
            allowedDirectories = listOf(directory),
            imageFiles = listOf(image),
            onText = {},
            onComplete = {},
            onError = {}
        )
}
