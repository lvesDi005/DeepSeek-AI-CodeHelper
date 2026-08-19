package com.deepseek.plugin.di

import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.api.StepFunApiClient
import com.deepseek.plugin.access.ChainedFileAccess
import com.deepseek.plugin.access.FileAccessService
import com.deepseek.plugin.context.ProjectContextProvider
import com.deepseek.plugin.context.RagRetriever
import com.deepseek.plugin.context.SearchCoordinator
import com.deepseek.plugin.search.AgenticSearch
import com.deepseek.plugin.search.ToolUseEngine
import com.deepseek.plugin.store.SessionStore
import com.intellij.openapi.project.Project

/**
 * Lightweight manual DI container for the plugin.
 *
 * All service instances are scoped per-project. During tests,
 * individual factories can be overridden via [override].
 *
 * Usage:
 *   val module = AppModule.get(project)
 *   val chatClient = module.apiClient
 *   val search = module.agenticSearch
 */
class AppModule private constructor(
    val project: Project
) {
    // ── API clients ──
    val apiClient: DeepSeekApiClient by lazy { DeepSeekApiClient() }
    val stepFunClient: StepFunApiClient by lazy { StepFunApiClient() }

    // ── File access ──
    val fileAccess: FileAccessService by lazy { ChainedFileAccess() }

    // ── Context & search ──
    val contextProvider: ProjectContextProvider by lazy { ProjectContextProvider(project) }
    val ragRetriever: RagRetriever by lazy { RagRetriever(project) }
    val searchCoordinator: SearchCoordinator by lazy { SearchCoordinator(project) }
    val agenticSearch: AgenticSearch by lazy { AgenticSearch(project) }
    val toolUseEngine: ToolUseEngine by lazy { ToolUseEngine(project) }

    // ── Persistence ──
    val sessionStore: SessionStore by lazy { SessionStore(project.basePath) }

    // ── Override hooks for tests ──
    private val overrides = mutableMapOf<Class<*>, () -> Any>()

    fun <T : Any> override(clazz: Class<T>, factory: () -> T) {
        overrides[clazz] = factory
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: Class<T>): T? = overrides[clazz]?.invoke() as? T

    companion object {
        private val modules = mutableMapOf<String, AppModule>()

        fun get(project: Project): AppModule =
            modules.getOrPut(project.basePath ?: project.name) { AppModule(project) }

        /** Only for tests — inject a pre-built module for a project path. */
        fun registerTest(key: String, module: AppModule) {
            modules[key] = module
        }

        fun clear() = modules.clear()
    }
}