package com.deepseek.plugin

/**
 * Read-only helper to obtain the current plugin version at runtime.
 *
 * Tries, in order:
 *  1. Package-level `Implementation-Version` from the JAR manifest
 *  2. Manifest `Implementation-Version` attribute via JarFile
 *  3. Static fallback matching the current [build.gradle.kts] version
 */
object PluginVersion {

    /** The current plugin version. Cached after first lookup. */
    @JvmStatic
    val current: String by lazy { resolveVersion() }

    /**
     * Resolve the implementation version from JAR metadata.
     */
    private fun resolveVersion(): String {
        // 1. Package implementation version
        val pkg = PluginVersion::class.java.`package`
        val implVersion = pkg?.implementationVersion
        if (!implVersion.isNullOrBlank()) return implVersion

        // 2. Manifest attribute
        try {
            val cls = PluginVersion::class.java
            val location = cls.protectionDomain?.codeSource?.location ?: return FALLBACK
            // Only works when running from a JAR (not from plain .class files)
            if (location.path.endsWith(".jar")) {
                val jarFile = java.util.jar.JarFile(location.path)
                val attr = jarFile.manifest?.mainAttributes?.getValue("Implementation-Version")
                jarFile.close()
                if (attr != null) return attr
            }
        } catch (_: Exception) {
            // Ignore
        }

        return FALLBACK
    }

    private const val FALLBACK = "2.3.1"
}
