package com.lumen.coacervation.engine

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureHub
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 进程级 trim 自动接管：登记在真实 Application 上，由框架的 ComponentCallbacks 分发触发，
 * 只在 15 与 >= 80（及 onLowMemory）释放；重复登记不会重复回调。
 */
@RunWith(AndroidJUnit4::class)
class MemoryPressureAutoInstallInstrumentedTest {
    @Test fun applicationTrimReachesTheHubAtTheDocumentedLevelsOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as Application
        var releases = 0
        val listener = LumenMemoryPressureListener { releases++ }
        instrumentation.runOnMainSync {
            LumenMemoryPressureHub.installSystemCallbacks(context)
            LumenMemoryPressureHub.installSystemCallbacks(context)
            LumenMemoryPressureHub.addListener(listener)
            try {
                @Suppress("DEPRECATION")
                for (level in intArrayOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                    ComponentCallbacks2.TRIM_MEMORY_MODERATE)) application.onTrimMemory(level)
                assertEquals("ordinary backgrounding must keep the materials", 0, releases)
                @Suppress("DEPRECATION")
                application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                assertEquals("registered once: one release per dispatch", 1, releases)
                application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                assertEquals(2, releases)
                @Suppress("DEPRECATION")
                application.onLowMemory()
                assertEquals(3, releases)
            } finally { LumenMemoryPressureHub.removeListener(listener) }
        }
    }
}
