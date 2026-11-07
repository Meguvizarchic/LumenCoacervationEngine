@file:JvmName("LumenMeguvizActivity")

package com.lumen.coacervation.engine.bridge.meguviz.android

import android.app.Activity
import android.content.res.Configuration
import android.os.Looper
import androidx.core.content.OnConfigurationChangedProvider
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.model.LumenPalette
import io.github.jichuo1.meguviz.android.lifecycle.LifecycleAdapters
import io.github.jichuo1.meguviz.android.runtime.core.Registration
import io.github.jichuo1.meguviz.android.window.WindowSession
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 本桥为每个 Activity 打开的会话；窗口会话关闭时移除。值用弱引用：会话持有 Activity，强引用会让弱键永远可达。仅主线程访问。 */
private val opened = WeakHashMap<Activity, WeakReference<LumenSurfaceSession>>()

/** 窗口会话的命名空间：只读观察，不占用窗口。 */
internal const val WINDOW_NAMESPACE = "lumen"

/**
 * 一行接入：在本 Activity 的窗口上打开 MeguvizAndroid 窗口会话（只观察，`Permission.OBSERVE`），交给 Activity 生命周期，
 * 再在窗口会话上开 Lumen 局部会话。宿主不需要写 `onDestroy`，也不需要自己调用 `LifecycleAdapters.bindWindow`。
 *
 * - **幂等**：同一 Activity 再次调用返回同一个未关闭的会话，在 `onResume` 之类会重复执行的地方调用也不会叠出第二个会话；
 *   参数与第一次不同时抛 [IllegalStateException]，不会悄悄忽略新参数。
 * - **配色**：[palette] 为 null 时取当前深浅色的 `LumenPalette.neutral`，Activity 自己处理 `uiMode` 变化（不重建）时
 *   自动更新配色；显式传入的配色不会被改写。
 * - **生命周期**：Activity 销毁时 Meguviz 关闭窗口会话，Lumen 会话随之关闭；宿主提前 `close()` 会话也安全。
 * - **误用直接报错**：非主线程、Activity 已销毁、Activity 不是 [LifecycleOwner]（例如裸 `android.app.Activity`）时抛异常，
 *   不会返回一个没人关闭的会话。
 *
 * 宿主已经自己管理 `WindowSession` 时改用 [WindowSession.openLumenSurfaces]。
 */
@JvmOverloads
public fun Activity.openLumenSurfaces(
    palette: LumenPalette? = null,
    options: LumenSurfaceSessionOptions = LumenSurfaceSessionOptions()
): LumenSurfaceSession {
    check(Looper.myLooper() === Looper.getMainLooper()) { "openLumenSurfaces must be called on the main thread" }
    require(this is LifecycleOwner) {
        "${javaClass.name} is not a LifecycleOwner; use a ComponentActivity (e.g. AppCompatActivity), " +
            "or open a WindowSession yourself and call WindowSession.openLumenSurfaces()"
    }
    val owner = this as LifecycleOwner
    check(!isDestroyed && owner.lifecycle.currentState != Lifecycle.State.DESTROYED) { "${javaClass.name} is destroyed" }
    val request = Request(palette, options)
    opened[this]?.get()?.takeIf { !it.isClosed }?.let { existing ->
        check(requests[existing] == request) {
            "Lumen surfaces are already open for this Activity with different arguments; close() them first"
        }
        return existing
    }
    val meguviz = WindowSession.open(window, this, WINDOW_NAMESPACE, WindowSession.Permission.OBSERVE)
    try {
        LifecycleAdapters.bindWindow(owner, meguviz)
        var night = isNight(resources.configuration)
        val session = meguviz.openLumenSurfaces(this, palette ?: LumenPalette.neutral(night), options)
        val activity = this
        opened[activity] = WeakReference(session)
        requests[session] = request
        meguviz.own(Registration(meguviz.dispatcher()) {
            if (opened[activity]?.get() === session) opened.remove(activity)
            requests.remove(session)
        })
        val provider = this as? OnConfigurationChangedProvider
        if (palette == null && provider != null) {
            val follow = Consumer<Configuration> { configuration ->
                val now = isNight(configuration)
                if (now != night && !session.isClosed) { night = now; session.updatePalette(LumenPalette.neutral(now)) }
            }
            provider.addOnConfigurationChangedListener(follow)
            meguviz.own(Registration(meguviz.dispatcher()) { provider.removeOnConfigurationChangedListener(follow) })
        }
        return session
    } catch (failure: RuntimeException) {
        meguviz.close()
        throw failure
    }
}

private data class Request(val palette: LumenPalette?, val options: LumenSurfaceSessionOptions)
private val requests = WeakHashMap<LumenSurfaceSession, Request>()

private fun isNight(configuration: Configuration): Boolean =
    configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
