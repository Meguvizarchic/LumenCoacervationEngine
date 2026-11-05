package com.lumen.coacervation.engine.runtime

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.MainThread
import java.util.WeakHashMap

/**
 * 宿主进程内、可丢弃图形资源的弱登记表。
 *
 * 进程级内存告急（例如宿主自己的内存协调器发出的 TRIM）不一定经过 Activity.onTrimMemory，所以不能只靠各
 * Activity 自己的回调。这里只释放高级材质的图形资源；不关页面、不清偏好。
 *
 * 引擎第一次拿到 Context（局部会话、Activity 会话或 Mica 背景创建时）就在 Application 上登记一次系统回调
 * （[installSystemCallbacks]）：`TRIM_MEMORY_RUNNING_CRITICAL`、`>= TRIM_MEMORY_COMPLETE` 与 onLowMemory 自动释放，
 * 宿主不必再在 Application.onTrimMemory 里转发。宿主自己的内存协调器仍可调用 LumenEngine.releaseGraphics()。
 */
internal fun interface LumenMemoryPressureListener {
    fun onReleaseGraphics()
}

internal object LumenMemoryPressureHub {
    private val lock = Any()
    private val listeners = WeakHashMap<LumenMemoryPressureListener, Unit>()
    @Volatile private var systemInstalled = false

    /** 与 Activity/局部会话一致的门槛：20（UI_HIDDEN）只是普通切后台，在那里释放会让每次切后台都丢掉高级材质。 */
    fun shouldRelease(level: Int): Boolean =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE

    private val systemCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = releaseGraphics()
        override fun onTrimMemory(level: Int) { if (shouldRelease(level)) releaseGraphics() }
    }

    /** 进程内只登记一次，之后的调用只读一个 volatile 标志；登记在 Application 上，随进程存在，不持有 Activity。 */
    fun installSystemCallbacks(context: Context) {
        if (systemInstalled) return
        val application = context.applicationContext ?: return
        synchronized(lock) {
            if (systemInstalled) return
            systemInstalled = true
        }
        application.registerComponentCallbacks(systemCallbacks)
    }

    fun addListener(listener: LumenMemoryPressureListener) {
        synchronized(lock) { listeners[listener] = Unit }
    }

    fun removeListener(listener: LumenMemoryPressureListener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    @MainThread
    fun releaseGraphics() {
        snapshot().forEach { listener ->
            runCatching { listener.onReleaseGraphics() }
        }
    }

    private fun snapshot(): List<LumenMemoryPressureListener> = synchronized(lock) {
        listeners.keys.toList()
    }
}
