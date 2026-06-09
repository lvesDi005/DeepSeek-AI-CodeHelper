plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.deepseek.plugin"
version = "2.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2025.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
}

intellijPlatform {
    pluginConfiguration {
        name = "DeepSeek AI CodeHelper"
        description = """
            <h2>DeepSeek AI Assistant for IntelliJ IDEA</h2>
            <ul>
              <li><b>Code Completion</b> — AI-powered inline suggestions as you type</li>
              <li><b>Chat Panel</b> — Ask anything in a side-panel chat window, streaming replies</li>
              <li><b>Agent Actions</b> — Right-click any code to Ask / Explain / Generate / Review / Optimize</li>
            </ul>
            <br>
            <p>Configure your DeepSeek API Key in <b>Settings → Tools → DeepSeek AI</b>.</p>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "253.*"
        }
        changeNotes = """
            <h3>v2.0.1</h3>
            <ul>
              <li>Major UI refactoring — ChatPanel split into modular components (MessageBubble, ChatInputBar, ChatToolbar, SessionBar)</li>
              <li>Markdown rendering for assistant replies (bold, italic, inline code, links, lists) using IntelliJ's bundled markdown library</li>
              <li>Message beautification: rounded corners, drop shadow, circular avatar icons (U/D), timestamp display</li>
              <li>Usage and History dialogs extracted as standalone classes</li>
              <li>Optimized packaging — Android/security platform classes stripped from okhttp JAR to pass Plugin Verifier</li>
              <li>Fixed IntelliJ 2025.1/2025.2 compatibility issues</li>
            </ul>
            <h3>v1.0.5</h3>
            <ul>
              <li>Added a question‑navigation sidebar that shows only 6 nodes at a time, dynamically updating as you scroll, and fixed the issue where clicking a node did not scroll to the corresponding reply</li>
              <li>Message bubbles now use an adaptive width (75% of the container) for a more readable, balanced layout</li>
            </ul>
            <h3>v1.0.4</h3>
            <ul>
              <li>File upload button in chat input — click to select local files, displayed as preview cards above input; file content sent as context with the message</li>
              <li>Selected code preview card above chat input — shows file, line range, and first 3 lines of selected code; supports one-click dismiss</li>
              <li>Modular code block cards (CodeBlockCard) extracted into reusable UI component with copy + insert buttons</li>
              <li>Clear button moved to session bar for easier session management</li>
            </ul>
            <h3>v1.0.3</h3>
            <ul>
              <li>Fixed duplicate AI responses consuming double tokens in chat</li>
              <li>Fixed right-click menu icon being oversized</li>
              <li>Fixed agent result dialog being too large</li>
              <li>Improved code completion: single-suggestion mode, better FIM prompt</li>
            </ul>
            <h3>v1.0.0</h3>
            <ul>
              <li>AI-powered inline code completion</li>
              <li>Streaming chat Q&amp;A panel</li>
              <li>Agent mode: Explain, Generate, Review, Optimize</li>
              <li>Right-click context menu integration</li>
              <li>Settings page for API Key configuration</li>
            </ul>
        """.trimIndent()
    }
}

// Write a Python script that filters unwanted classes from okhttp JAR
val filterScriptPath = layout.buildDirectory.file("tmp/filter_okhttp.py")
val writeFilterScript by tasks.registering {
    outputs.file(filterScriptPath)
    // Not compatible with configuration cache (writes to build dir)
    notCompatibleWithConfigurationCache("writes filter script to build dir")
    doLast {
        filterScriptPath.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
import zipfile, sys, os

exclude_prefixes = [
    'okhttp3/internal/platform/android/',
    'okhttp3/internal/platform/Android10Platform',
    'okhttp3/internal/platform/AndroidPlatform',
    'okhttp3/internal/platform/ConscryptPlatform',
    'okhttp3/internal/platform/BouncyCastlePlatform',
    'okhttp3/internal/platform/OpenJSSEPlatform',
]

jar_path = sys.argv[1]
tmp_path = jar_path + '.tmp'

with zipfile.ZipFile(jar_path, 'r') as zin, \
     zipfile.ZipFile(tmp_path, 'w', zipfile.ZIP_DEFLATED) as zout:
    for entry in zin.infolist():
        if not any(entry.filename.startswith(p) for p in exclude_prefixes):
            zout.writestr(entry, zin.read(entry.filename))

os.replace(tmp_path, jar_path)
print(f'Filtered: {jar_path}')
            """.trimIndent()
            )
        }
    }
}

val filterOkhttpLib by tasks.registering {
    dependsOn("prepareSandbox", writeFilterScript)
    inputs.file(filterScriptPath)
    // Not compatible with configuration cache (ProcessBuilder)
    notCompatibleWithConfigurationCache("spawns external process")
    doLast {
        val sandboxDir = layout.buildDirectory.dir("idea-sandbox").get()
        sandboxDir.asFile.walkTopDown().filter {
            it.name == "okhttp-4.12.0.jar"
        }.forEach { jarFile ->
            val script = filterScriptPath.get().asFile
            val pb = ProcessBuilder("python", script.absolutePath, jarFile.absolutePath)
            pb.inheritIO()
            val proc = pb.start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("filter_okhttp.py failed with exit code $exitCode")
            }
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
    }

    // Third-party deps are placed in lib/ by IntelliJ Platform Gradle Plugin 2.x automatically.
    // Do NOT extract them into the main plugin JAR — that would cause the Plugin Verifier
    // to attribute their internal deprecated API usage to our plugin.
    // The filterOkhttpLib task below handles stripping Android/security classes from lib/ JARs.

    // Ensure okhttp filtering happens before building the plugin ZIP and running IDE
    named("buildPlugin") {
        dependsOn(filterOkhttpLib)
    }
    named("runIde") {
        dependsOn(filterOkhttpLib)
    }
}
