package com.changyow.mediadler.data.ytdlp

import android.os.Bundle
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Keeps the QA activity open while the unchanged production-pipeline test runs. */
@RunWith(AndroidJUnit4::class)
class ForegroundWeightedAdDownloadTest {
    @Test fun autonomousDownload() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit qaWeightedForeground=true", args.getString("qaWeightedForeground") == "true")
        assumeTrue("Requires explicit qaWeightedAds=true", args.getString("qaWeightedAds") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "com.changyow.mediadler.qa") {
            "Foreground benchmark requires the isolated QA target"
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // Window configuration is evidence of setup, not proof of foreground or an unlocked screen.
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "QA_WEIGHTED_FOREGROUND_WINDOW_CONFIGURED=true\n")
            })
            WeightedAdDownloadTest().autonomousDownload()
        } finally {
            scenario.close()
        }
    }
}
