package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.DownloadDestinationPolicy
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadDestinationPolicyTest {
    private val item = MediaItem("https://cdn/gimy/file", null, "1", "中文影片", null,
        null, false, emptyList())

    @Test fun generalKeepsBaseAndExistingNameEvenWithEpisodeMetadataOrSpecialCdnName() {
        val result = DownloadDestinationPolicy.destination(item.copy(seriesTitle = "劇名", episodeNumber = 2), "mp4")
        assertEquals(emptyList(), result.folders)
        assertEquals("中文影片.mp4", result.displayName)
    }

    @Test fun bothSpecialSourcesUseConfirmedMetadataWithoutGuessingUnknownSeason() {
        for (kind in listOf(SourceKind.GIMY, SourceKind.EIGHT_MAPLE)) {
            val special = item.copy(source = SourceContext("share", "page", kind), seriesTitle = "媽媽發瘋了",
                episodeNumber = 2)
            assertEquals(listOf("drama", "媽媽發瘋了"), DownloadDestinationPolicy.destination(special, "mp4").folders)
            assertEquals("E0002 中文影片.mp4", DownloadDestinationPolicy.destination(special, "mp4").displayName)
            assertEquals(listOf("drama", "媽媽發瘋了", "Season 03"),
                DownloadDestinationPolicy.destination(special.copy(seasonNumber = 3), "mp4").folders)
        }
    }

    @Test fun missingMetadataStaysUnknownAndTraversalControlCharactersCannotBecomeFolders() {
        val special = item.copy(title = "../劇名\u0000/影片", source = SourceContext("a", "b", SourceKind.GIMY),
            seriesTitle = "../逃出\\目錄", seasonNumber = 0, episodeNumber = -1)
        val result = DownloadDestinationPolicy.destination(special, "../mp4")
        assertEquals(listOf("drama", "_逃出_目錄"), result.folders)
        assertEquals("_劇名__影片.bin", result.displayName)
        assertEquals(listOf("drama"), DownloadDestinationPolicy.destination(special.copy(seriesTitle = null), "mp4").folders)
    }

    @Test fun episodePrefixSortsNaturallyAndLongChineseTitlesAreBounded() {
        val names = listOf(10, 2, 1).map { episode -> DownloadDestinationPolicy.destination(item.copy(
            title = "劇".repeat(200), source = SourceContext("a", "b", SourceKind.GIMY), episodeNumber = episode), "mp4").displayName }
        assertEquals(listOf("E0001", "E0002", "E0010"), names.sorted().map { it.substringBefore(' ') })
        assertTrue(names.all { it.length <= 124 })
    }
}
