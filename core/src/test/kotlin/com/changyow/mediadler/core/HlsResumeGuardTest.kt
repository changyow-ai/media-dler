package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.HlsResumeGuard
import kotlin.test.*
import org.junit.Test

class HlsResumeGuardTest {
    private val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"

    @Test fun `identical VOD reuses fragments but a changed manifest does not`() {
        assertNotNull(HlsResumeGuard.fingerprint(vod))
        assertEquals(HlsResumeGuard.fingerprint(vod), HlsResumeGuard.fingerprint(vod))
        assertNotEquals(HlsResumeGuard.fingerprint(vod), HlsResumeGuard.fingerprint(vod.replace("a.ts", "b.ts")))
    }

    @Test fun `master live encrypted and unknown input cannot reuse fragments`() {
        listOf(vod.replace("#EXT-X-ENDLIST\n", ""), vod.replace("#EXTINF:", "#EXT-X-STREAM-INF:"),
            vod.replace("a.ts", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\na.ts"), "not a manifest")
            .forEach { assertNull(HlsResumeGuard.fingerprint(it)) }
    }
}
