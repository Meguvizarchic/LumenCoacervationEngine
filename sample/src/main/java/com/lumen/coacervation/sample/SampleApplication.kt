package com.lumen.coacervation.sample

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.LumenEngineConfig
import com.lumen.coacervation.engine.LumenStorageNames

/** 适配标准 §3：进程级接线。 */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 可选：改存储文件名（例如从旧版本迁移时沿用旧名）。必须在第一次使用引擎之前调用。
        LumenEngine.configure(LumenEngineConfig(storage = LumenStorageNames()))
        // 设置页选的深浅色：进程启动时应用一次，之后由设置页切换（AppCompat 自动重建）。
        AppCompatDelegate.setDefaultNightMode(SampleSettingsStore(this).nightMode)
    }

    // 进程级内存压力由引擎在 Application 上自动登记（15 与 >= 80 释放），不需要在这里转发。
}
