package com.deepseek.plugin.settings

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Stores and loads skill definitions from a JSON file in the project's .idea directory.
 */
class SkillStore(projectBasePath: String?) {

    private val skillsFile: File? = projectBasePath?.let {
        val dir = File(it, ".idea")
        if (dir.isDirectory || dir.mkdirs()) File(dir, "deepseek-skills.json") else null
    }

    private val gson = Gson()

    /**
     * Load all saved skills from the JSON file.
     */
    fun load(): MutableList<SkillData> {
        val file = skillsFile ?: return mutableListOf()
        if (!file.exists()) return mutableListOf()
        return try {
            val json = file.readText(Charsets.UTF_8)
            val type = object : TypeToken<MutableList<SkillData>>() {}.type
            gson.fromJson<MutableList<SkillData>>(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    /**
     * Save all skills to the JSON file.
     */
    fun save(skills: List<SkillData>) {
        val file = skillsFile ?: return
        try {
            file.writeText(gson.toJson(skills), Charsets.UTF_8)
        } catch (_: Exception) {
            // Silently ignore — skills are non-critical
        }
    }
}
