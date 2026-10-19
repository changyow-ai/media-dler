package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.download.HlsResumeGuard
import kotlin.test.*
import org.junit.Test

class EightMapleHlsAdsTest {
    private val header = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n"
    private val closing = "#EXT-X-DISCONTINUITY\n"
    private val end = "#EXT-X-ENDLIST\n"

    @Test fun `observed shape removes two marked blocks and preserves all 758 retained URI positions`() {
        val original = buildString {
            append(header)
            repeat(758) { index ->
                if (index == 75 || index == 495) append(block("ad-$index"))
                if (index == 200) append(closing) // A drama discontinuity is not an ad marker.
                append(segment("drama/$index.ts"))
            }
            append(end)
        }
        assertEquals(772, uris(original).size)
        val filtered = EightMapleHlsAds.filter(original)
        assertEquals(2, filtered.skippedAds)
        assertEquals(14, filtered.skippedAdSegments)
        assertEquals(51.52, filtered.skippedAdSeconds, 0.000001)
        assertEquals((0 until 758).map { "drama/$it.ts" }, uris(filtered.manifest))
        assertEquals(original.replace(block("ad-75"), closing).replace(block("ad-495"), closing), filtered.manifest)
        assertEquals(3, filtered.manifest.lineSequence().count { it == "#EXT-X-DISCONTINUITY" })
        assertNotNull(HlsResumeGuard.fingerprint(filtered.manifest))
        assertUnchanged(filtered.manifest)
    }

    @Test fun `v1 supports arbitrary positive pair counts without duration or URL heuristics`() {
        val original = header + segment("before.ts") + block("anything", listOf(0.125, 8.0, 71.3)) +
            segment("after.ts") + end
        val filtered = EightMapleHlsAds.filter(original)
        assertEquals(1, filtered.skippedAds)
        assertEquals(3, filtered.skippedAdSegments)
        assertEquals(79.425, filtered.skippedAdSeconds, 0.000001)
        assertEquals(listOf("before.ts", "after.ts"), uris(filtered.manifest))
    }

    @Test fun `retained lines whitespace comments and CRLF endings are preserved verbatim`() {
        val original = (header + "# ordinary comment\n\n" + segment("before.ts") + block("ad") +
            "# comment after splice\n" + segment("after.ts") + end).replace("\n", "\r\n")
        val filtered = EightMapleHlsAds.filter(original)
        assertEquals(1, filtered.skippedAds)
        assertEquals(original.replace(block("ad").replace("\n", "\r\n"), closing.replace("\n", "\r\n")), filtered.manifest)
    }

    @Test fun `malformed later block rolls back the entire original rather than partially cutting`() {
        val malformed = block("later").replace("#EXT-X-DISCONTINUITY\n#EXTINF", "#EXTINF")
        val original = header + segment("before.ts") + block("first") + segment("middle.ts") +
            malformed + segment("after.ts") + end
        assertNotNull(HlsResumeGuard.fingerprint(original))
        assertUnchanged(original)
    }

    @Test fun `unknown versions nested markers missing close empty blocks and no retained media stay unchanged`() {
        val ordinary = header + segment("before.ts") + block("ad") + segment("after.ts") + end
        val withoutClose = header + segment("before.ts") + block("ad").removeSuffix(closing) + end
        val cases = listOf(
            ordinary.replace("# ppvod-ad-injected v1", "# ppvod-ad-injected v2"),
            ordinary.replace("# ppvod-ad-injected v1", "#ppvod-ad-injected v1"),
            ordinary.replace("# ppvod-ad-injected v1", "# ppvod-ad-injected v1 "),
            ordinary.replace("#EXTINF:4.0,\nad/0.ts", "# ppvod-ad-injected v1\n#EXTINF:4.0,\nad/0.ts"),
            withoutClose,
            header + segment("before.ts") + "# ppvod-ad-injected v1\n" + closing + closing + segment("after.ts") + end,
            header + block("only-media") + end,
        )
        cases.forEach(::assertUnchanged)
    }

    @Test fun `unsupported features anywhere or extra tags inside a block prevent every cut`() {
        val original = header + segment("before.ts") + block("first") + segment("middle.ts") + block("second") + end
        listOf(
            original.replace("#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-MAP:URI=\"init.mp4\""),
            original.replace("#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\""),
            original.replace("#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-BYTERANGE:100@0"),
            original.replace("#EXTINF:4.0,\nsecond/0.ts", "# harmless extra tag\n#EXTINF:4.0,\nsecond/0.ts"),
            original.replace("#EXT-X-ENDLIST\n", ""),
        ).forEach(::assertUnchanged)
    }

    @Test fun `unmarked discontinuities and unknown comments never authorize removal`() {
        val original = header + "# ads maybe\n" + segment("first.ts") + closing + segment("second.ts") + end
        assertUnchanged(original)
    }

    @Test fun `bounded block counts segment counts and durations fall back unchanged`() {
        val retained = segment("ordinary.ts")
        assertUnchanged(header + retained + (0 until 17).joinToString("") { block("ad$it", listOf(1.0)) } + end)
        assertUnchanged(header + retained + block("too-many", List(129) { 1.0 }) + end)
        assertUnchanged(header + retained + block("too-long", listOf(400.0, 201.0)) + end)
    }

    private fun assertUnchanged(original: String) {
        val filtered = EightMapleHlsAds.filter(original)
        assertEquals(original, filtered.manifest)
        assertEquals(0, filtered.skippedAds)
        assertEquals(0, filtered.skippedAdSegments)
        assertEquals(0.0, filtered.skippedAdSeconds)
    }

    private fun block(id: String, durations: List<Double> = List(6) { 4.0 } + 1.76) = buildString {
        append("# ppvod-ad-injected v1\n")
        append(closing)
        durations.forEachIndexed { index, duration -> append(segment("$id/$index.ts", duration)) }
        append(closing)
    }

    private fun segment(uri: String, duration: Double = 4.170833) = "#EXTINF:$duration,\n$uri\n"
    private fun uris(manifest: String) = manifest.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.toList()
}
