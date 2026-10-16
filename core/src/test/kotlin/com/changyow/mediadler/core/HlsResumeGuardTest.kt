package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.HlsResumeGuard
import kotlin.test.*
import org.junit.Test

class HlsResumeGuardTest {
    private val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"
    private val master = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild.m3u8\n"

    @Test fun `identical VOD reuses fragments but any changed manifest does not`() {
        assertNotNull(HlsResumeGuard.fingerprint(vod))
        assertEquals(HlsResumeGuard.fingerprint(vod), HlsResumeGuard.fingerprint(vod))
        assertNotEquals(HlsResumeGuard.fingerprint(vod), HlsResumeGuard.fingerprint(vod.replace("a.ts", "b.ts")))
        assertNotEquals(HlsResumeGuard.fingerprint(vod), HlsResumeGuard.fingerprint(vod + "\n"))
    }

    @Test fun `ordinary discontinuities keep every TS fragment eligible`() {
        assertNotNull(HlsResumeGuard.fingerprint(vod.replace("#EXTINF:", "#EXT-X-DISCONTINUITY\n#EXTINF:")))
        assertNotNull(HlsResumeGuard.fingerprint(vod.replace("a.ts", "#EXT-X-KEY:METHOD=NONE\na.ts?token=one")))
    }

    @Test fun `master live encrypted and unknown input cannot reuse fragments`() {
        listOf(master, vod.replace("#EXT-X-ENDLIST\n", ""),
            vod.replace("a.ts", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\na.ts"), "not a manifest")
            .forEach { assertNull(HlsResumeGuard.fingerprint(it)) }
    }

    @Test fun `mapping-changing and unsupported tags cannot reuse fragments`() {
        listOf("#EXT-X-BYTERANGE:10", "#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-PART:URI=\"part.ts\"",
            "#EXT-X-GAP", "#EXT-X-DEFINE:NAME=\"x\",VALUE=\"y\"", "#EXT-X-SKIP:SKIPPED-SEGMENTS=1",
            "#ANVATO-SEGMENT-INFO:type=ad", "#UPLYNK-SEGMENT:ad", "#EXT-X-ANVATO-SEGMENT-INFO:type=ad", "#EXT-X-UPLYNK-SEGMENT:ad", "#EXT-X-SESSION-KEY:METHOD=NONE",
            "#EXT-X-START:TIME-OFFSET=10", "#EXT-X-UNKNOWN:1").forEach { tag ->
            assertNull(HlsResumeGuard.fingerprint(vod.replace("#EXTINF:", "$tag\n#EXTINF:")), tag)
        }
    }

    @Test fun `malformed segment lists and unsupported segment URLs are rejected`() {
        listOf(vod.replace("#EXTINF:4,\n", ""), vod.replace("a.ts", "a.ts\nb.ts"),
            vod.replace("a.ts", "#EXTINF:4,\na.ts"), vod.replace("a.ts", "a.mp4"),
            vod.replace("a.ts", "file:///a.ts"), vod.replace("a.ts", "https://user:password@cdn.example/a.ts"),
            vod.replace("a.ts", "https://cdn.example/a.ts#fragment"), vod.replace("4,", "NaN,"))
            .forEach { assertNull(HlsResumeGuard.fingerprint(it), it) }
    }

    @Test fun `single variant resolves against final master directory including absolute and parent URLs`() {
        assertEquals("https://cdn.example/folder/child.m3u8", HlsResumeGuard.singleVariantUrl(master, "https://cdn.example/folder/master.m3u8"))
        assertEquals("https://cdn.example/child.m3u8", HlsResumeGuard.singleVariantUrl(master.replace("child.m3u8", "../child.m3u8"), "https://cdn.example/folder/master.m3u8"))
        assertEquals("https://other.example/media.m3u8", HlsResumeGuard.singleVariantUrl(master.replace("child.m3u8", "https://other.example/media.m3u8"), "https://cdn.example/master.m3u8"))
    }

    @Test fun `ambiguous variants renditions keys and invalid URLs are rejected`() {
        listOf(master + "#EXT-X-STREAM-INF:BANDWIDTH=2000\nsecond.m3u8\n",
            master.replace("child.m3u8", "child.m3u8\nsecond.m3u8"), master.replace("child.m3u8\n", ""),
            master.replace("child.m3u8", "file:///child.m3u8"),
            master.replace("child.m3u8", "https://user:password@cdn.example/child.m3u8"),
            master.replace("#EXT-X-STREAM-INF:", "#EXT-X-STREAM-INF:AUDIO=\"audio\","),
            master + "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"\n",
            master + "#EXT-X-I-FRAME-STREAM-INF:URI=\"iframe.m3u8\"\n",
            master + "#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"key\"\n", vod)
            .forEach { assertNull(HlsResumeGuard.singleVariantUrl(it, "https://cdn.example/master.m3u8"), it) }
    }
}
