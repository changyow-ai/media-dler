package com.changyow.mediadler.data.ytdlp

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.appContainer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly requested, read-only source inspection for the user's selected Moving episodes. */
class AdSourceProbeTest {
    @Test fun readSelectedEpisodeSources() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("qaAdSourceProbe") == "true")
        val context = instrumentation.targetContext
        val history = withTimeout(30_000) { context.appContainer.historyStore.history.first() }
        val selected = history.filter {
            (it.title.contains("Moving", ignoreCase = true) || it.seriesTitle.orEmpty().contains("Moving", ignoreCase = true)) &&
                (it.episodeNumber in setOf(11, 12) || it.episodeNumber == null)
        }.take(4)
        val report = buildJsonObject {
            put("readOnly", true)
            put("selectedCount", selected.size)
            put("episodes", buildJsonArray {
                selected.forEach { task ->
                    add(buildJsonObject {
                        put("title", task.title)
                        put("sourceKind", task.sourceKind.name)
                        put("pageUrl", task.pageUrl)
                        put("episodeNumber", task.episodeNumber)
                        put("lineId", task.lineId)
                        put("lineName", task.lineName)
                    })
                }
            })
        }
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "qa-ad-sources-${UUID.randomUUID()}")
        check(directory.mkdir())
        val file = File(directory, "sources.json").apply { writeText(report.toString()) }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "QA_AD_SOURCES_FILE=${file.absolutePath}\n")
        })
    }
}
