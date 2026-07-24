package com.deepseek.plugin.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicReference

/**
 * 补全状态管理服务。
 *
 * 跨组件共享补全的当前状态，供状态栏 Widget 和 Ghost Text 渲染器查询。
 * 每个状态变更自动通知状态栏刷新。
 */
@Service(Service.Level.APP)
class CompletionStatusService {

    companion object {
        private val LOG = Logger.getInstance(CompletionStatusService::class.java)

        val instance: CompletionStatusService
            get() = ApplicationManager.getApplication().getService(CompletionStatusService::class.java)
    }

    /** 补全状态枚举 */
    enum class State {
        /** 空闲 */
        IDLE,
        /** 正在生成补全 */
        GENERATING,
        /** 补全就绪 */
        READY,
        /** 发生错误 */
        ERROR
    }

    private val stateRef = AtomicReference(State.IDLE)
    private var errorMessage: String = ""
    private var consecutiveErrors: Int = 0
    private var listeners: MutableList<(State) -> Unit> = mutableListOf()

    /** 获取当前状态 */
    fun getState(): State = stateRef.get()

    /** 获取最近的错误消息 */
    fun getErrorMessage(): String = errorMessage

    /** 获取连续失败次数 */
    fun getConsecutiveErrors(): Int = consecutiveErrors

    /** 重置连续失败计数 */
    fun resetConsecutiveErrors() { consecutiveErrors = 0 }

    /** 切换到 GENERATING 状态 */
    fun onGenerating() {
        val prev = stateRef.getAndSet(State.GENERATING)
        if (prev != State.GENERATING) {
            LOG.debug("Completion status: GENERATING")
            notifyListeners(State.GENERATING)
        }
    }

    /** 切换到 READY 状态（有补全结果） */
    fun onReady() {
        consecutiveErrors = 0
        val prev = stateRef.getAndSet(State.READY)
        if (prev != State.READY) {
            LOG.debug("Completion status: READY")
            notifyListeners(State.READY)
        }
    }

    /** 切换到 ERROR 状态 */
    fun onError(message: String) {
        consecutiveErrors++
        errorMessage = message
        val prev = stateRef.getAndSet(State.ERROR)
        if (prev != State.ERROR) {
            LOG.debug("Completion status: ERROR - $message")
            notifyListeners(State.ERROR)
        }
    }

    /** 切换到 IDLE 状态 */
    fun onIdle() {
        consecutiveErrors = 0
        val prev = stateRef.getAndSet(State.IDLE)
        if (prev != State.IDLE) {
            notifyListeners(State.IDLE)
        }
    }

    /** 注册状态变更监听器 */
    fun addListener(listener: (State) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /** 移除状态变更监听器 */
    fun removeListener(listener: (State) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners(state: State) {
        synchronized(listeners) {
            listeners.forEach { it(state) }
        }
    }
}
