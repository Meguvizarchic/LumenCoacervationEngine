package com.lumen.coacervation.engine.liquid

import android.content.Context
import android.os.PowerManager
import androidx.annotation.RequiresApi

/** API 29+ Thermal 状态监听；回调由应用主线程 Executor 串行分发。 */
@RequiresApi(29)
internal class LiquidThermalMonitorApi29(
    private val context: Context,
    private val onStatusChanged: (Int) -> Unit
) : LiquidThermalMonitor {
    /**
     * 登记给系统的监听只经 [target] 回到本监视器。PowerManager 把监听包成 Binder 存根交给系统进程，注销后
     * 存根要等系统进程 GC 掉远端引用才释放（本进程里是一个 JNI 全局引用），期间登记出去的对象仍然可达。
     * [stop] 清空 [target]：这段时间里留住的只是这个空壳，不会再经回调链留住渲染器与 Activity
     * （2026-11-06 Demo APK API 31 的 hprof：存根 → 监听 → 监视器 → 性能协调器 → 渲染器 → Activity）。
     */
    private class Relay(@Volatile var target: LiquidThermalMonitorApi29?) : PowerManager.OnThermalStatusChangedListener {
        override fun onThermalStatusChanged(status: Int) {
            target?.dispatch(status)
        }
    }

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var relay: Relay? = null

    override var currentThermalStatus: Int = LiquidPerformancePolicy.THERMAL_STATUS_NONE
        private set

    private fun dispatch(status: Int) {
        if (relay == null) return
        val normalized = LiquidPerformancePolicy.normalizeThermalStatus(status)
        currentThermalStatus = normalized
        onStatusChanged(normalized)
    }

    override fun start() {
        if (relay != null || powerManager == null) return
        currentThermalStatus = runCatching { powerManager.currentThermalStatus }
            .map(LiquidPerformancePolicy::normalizeThermalStatus)
            .getOrDefault(LiquidPerformancePolicy.THERMAL_STATUS_NONE)
        val next = Relay(this)
        if (runCatching { powerManager.addThermalStatusListener(context.mainExecutor, next) }.isSuccess) {
            relay = next
        } else {
            next.target = null
        }
    }

    override fun stop() {
        val current = relay ?: return
        relay = null
        current.target = null
        runCatching { powerManager?.removeThermalStatusListener(current) }
    }

    override fun close() = stop()
}
