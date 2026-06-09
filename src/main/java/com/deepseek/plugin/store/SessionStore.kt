package com.deepseek.plugin.store

import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.chat.ChatSession
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists chat sessions to disk, loads them on startup.
 * Sessions are kept indefinitely.
 *
 * File: <project>/.idea/deepseek-sessions.json
 */
class SessionStore(private val projectBasePath: String?) {

    companion object {
        private val LOG = Logger.getInstance(SessionStore::class.java)
        private val gson = Gson()
    }

    data class SessionData(
        val name: String,
        val messages: MutableList<ChatMessage>,
        var totalTokens: Int,
        var lastActiveTime: Long
    )

    data class StoreData(
        val sessions: MutableList<SessionData> = mutableListOf(),
        var sessionCounter: Int = 1
    )

    private val file: File?
        get() {
            val base = projectBasePath ?: return null
            val dir = File(base, ".idea")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "deepseek-sessions.json")
        }

    /**
     * Load sessions from disk. Returns null if nothing saved.
     */
    fun load(): Pair<List<ChatSession>, Int>? {
        val f = file ?: return null
        if (!f.exists()) return null

        return try {
            val json = f.readText(Charsets.UTF_8)
            val store = gson.fromJson(json, StoreData::class.java) ?: return null

            val sessions = store.sessions.map { d ->
                ChatSession(d.name, d.messages, d.totalTokens, d.lastActiveTime)
            }

            Pair(sessions, store.sessionCounter)
        } catch (e: Exception) {
            LOG.warn("Failed to load sessions", e)
            null
        }
    }

    /**
     * Save sessions to disk.
     */
    fun save(sessions: List<ChatSession>, sessionCounter: Int) {
        val f = file ?: return
        try {
            // Atomic write: temp → rename
            val tmp = File(f.parentFile, f.name + ".tmp")
            val store = StoreData(
                sessions = sessions.map {
                    SessionData(it.name, it.messages, it.totalTokens, it.lastActiveTime)
                }.toMutableList(),
                sessionCounter = sessionCounter
            )
            tmp.writeText(gson.toJson(store), Charsets.UTF_8)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            LOG.warn("Failed to save sessions", e)
        }
    }
}
