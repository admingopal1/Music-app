package com.app.musicplayer

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real emulator launch check. This catches startup crashes that a successful
 * Kotlin compile and APK assembly cannot detect.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {
    @Test
    fun mainActivityLaunchesWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("MainActivity unexpectedly finishing", activity.isFinishing)
                assertFalse("MainActivity unexpectedly destroyed", activity.isDestroyed)
            }
        }
    }
}
