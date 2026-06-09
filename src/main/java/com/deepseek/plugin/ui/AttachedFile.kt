package com.deepseek.plugin.ui

/**
 * Represents a file attached to a chat message.
 */
class AttachedFile(
    val name: String,
    val absolutePath: String,
    val size: Long
) {
    /** Cached text content (read on first access via [readContent]). */
    var content: String? = null
        private set

    /** Human-readable file size string. */
    val sizeDisplay: String
        get() {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
                else -> "%.1f MB".format(size / (1024.0 * 1024.0))
            }
        }

    /**
     * Read the file content as UTF-8 text and cache it.
     * Returns the cached content on subsequent calls.
     */
    fun readContent(): String? {
        if (content != null) return content
        return try {
            val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(absolutePath))
            String(bytes, Charsets.UTF_8).also { content = it }
        } catch (e: Exception) {
            null
        }
    }
}
