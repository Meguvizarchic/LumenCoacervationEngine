package com.lumen.coacervation.sample

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.bridge.meguviz.android.openLumenSurfaces
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import io.github.jichuo1.meguviz.android.window.WindowSession
import android.content.res.Configuration
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 桥的生命周期契约（只在 -Plumen.meguviz=true 的构建里存在）。像素与性能对照见 [MeguvizBridgeBenchmarkTest]。 */
@RunWith(AndroidJUnit4::class)
class MeguvizBridgeTest {
    @Test fun everyModeClosesItsSessionsWithTheActivity() {
        for (mode in listOf(MeguvizBridgeActivity.MODE_LUMEN, MeguvizBridgeActivity.MODE_SEPARATE, MeguvizBridgeActivity.MODE_BRIDGE)) {
            val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MeguvizBridgeActivity::class.java)
                .putExtra(MeguvizBridgeActivity.EXTRA_MODE, mode)
            lateinit var surfaces: LumenSurfaceSession
            var window: WindowSession? = null
            ActivityScenario.launch<MeguvizBridgeActivity>(intent).use { scenario ->
                scenario.onActivity { surfaces = it.surfaces; window = it.windowSession }
            }
            WindowRegressionCapture.main {
                assertTrue("$mode: Lumen session must close with the Activity", surfaces.isClosed)
                window?.let { assertTrue("$mode: window session must close with the Activity", it.lifecycle().isClosed) }
            }
        }
    }

    @Test fun aClosedWindowSessionRefusesANewLumenSession() {
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MeguvizBridgeActivity::class.java)
        ActivityScenario.launch<MeguvizBridgeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val window = WindowSession.open(activity.window, activity, "lumen-test", WindowSession.Permission.OBSERVE)
                val session = window.openLumenSurfaces()
                assertFalse(session.isClosed)
                window.close()
                assertTrue("closing the window session closes its Lumen session", session.isClosed)
                try {
                    window.openLumenSurfaces()
                    fail("a closed window session must not hand out a session nobody would close")
                } catch (expected: IllegalStateException) { }
            }
        }
    }

    @Test fun theOneLinerIsIdempotentRejectsConflictingArgumentsAndFollowsNightMode() {
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MeguvizBridgeActivity::class.java)
        ActivityScenario.launch<MeguvizBridgeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val first = activity.surfaces
                assertSame("a repeated call returns the open session", first, activity.openLumenSurfaces())
                try {
                    activity.openLumenSurfaces(palette = LumenPalette.neutral(true))
                    fail("different arguments must not be silently ignored")
                } catch (expected: IllegalStateException) { }
                val palette = LumenSurfaceSession::class.java.getDeclaredField("palette").apply { isAccessible = true }
                val night = Configuration(activity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                }
                activity.onConfigurationChanged(night)
                assertEquals("the default palette follows night mode", LumenPalette.neutral(true), palette.get(first))
                first.close()
                val reopened = activity.openLumenSurfaces()
                assertFalse("after an explicit close a new session opens", reopened.isClosed)
            }
            var offMain: Throwable? = null
            lateinit var target: MeguvizBridgeActivity
            scenario.onActivity { target = it }
            Thread { offMain = runCatching { target.openLumenSurfaces() }.exceptionOrNull() }.apply { start(); join() }
            assertTrue("off the main thread fails loudly: $offMain", offMain is IllegalStateException)
        }
    }
}
