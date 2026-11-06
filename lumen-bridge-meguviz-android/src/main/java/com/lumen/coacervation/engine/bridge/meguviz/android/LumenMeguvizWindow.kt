@file:JvmName("LumenMeguvizWindow")

package com.lumen.coacervation.engine.bridge.meguviz.android

import android.content.Context
import android.content.res.Configuration
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.model.LumenPalette
import io.github.jichuo1.meguviz.android.runtime.core.Registration
import io.github.jichuo1.meguviz.android.window.WindowSession

/**
 * 在 MeguvizAndroid 的 [WindowSession] 上开一个 Lumen 局部会话，会话随窗口会话一起关闭。
 *
 * - 归属由 Meguviz 的 `LifecycleScope` 决定：窗口会话关闭时，登记在它上面的 [Registration] 在所有者线程上关闭会话。
 *   宿主提前 `close()` 会话也安全（关闭幂等）。
 * - 不另外注册 Activity 生命周期回调：[LumenSurfaceSession.forActivity] 用 Application 回调跟踪销毁，
 *   Meguviz 宿主已有窗口会话负责这件事，再登记一份是重复工作。
 * - [context] 默认是窗口会话的窗口所在的 Context；[palette] 默认按窗口会话资源快照的深浅色取
 *   `LumenPalette.neutral`，与 `forActivity` 读 `Configuration.uiMode` 的规则相同。
 * - 只能在窗口会话的所有者线程（主线程）调用；窗口会话已关闭或不再接受登记时抛 [IllegalStateException]，
 *   不会留下无人关闭的会话。
 *
 * 暂停与恢复仍由宿主决定（`LumenSurfaceSession.pause()` / `resume()`）：`WindowSession` 没有可订阅的可见性事件，
 * 本桥不猜测宿主的可见性语义。
 */
@JvmOverloads
public fun WindowSession.openLumenSurfaces(
    context: Context? = null,
    palette: LumenPalette? = null,
    options: LumenSurfaceSessionOptions = LumenSurfaceSessionOptions()
): LumenSurfaceSession {
    dispatcher().checkOwner()
    val lifecycle = lifecycle()
    check(!lifecycle.isClosed && !lifecycle.admissionClosed()) { "WindowSession is closed" }
    val host = context ?: checkNotNull(window()) { "WindowSession has no window" }.context
    val session = LumenSurfaceSession(host, palette ?: LumenPalette.neutral(isNight(this)), options)
    try {
        own(Registration(dispatcher(), session::close))
    } catch (failure: RuntimeException) {
        session.close()
        throw failure
    }
    return session
}

internal fun isNight(window: WindowSession): Boolean =
    window.resources().state().snapshot().uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
