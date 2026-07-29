package com.changyow.mediadler.core

import com.changyow.mediadler.core.extract.ThreadsUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadsUrlTest {
    @Test fun rewritesThreadsComPostToEmbedAndDropsQuery() {
        assertEquals(
            "https://www.threads.net/@rico_y9527/post/DY3m2JQjXHZ/embed",
            ThreadsUrl.embedUrlOrNull("https://www.threads.com/@rico_y9527/post/DY3m2JQjXHZ?xmt=AQ&slof=1"),
        )
    }

    @Test fun rewritesThreadsNetPost() {
        assertEquals(
            "https://www.threads.net/@u/post/ABC123/embed",
            ThreadsUrl.embedUrlOrNull("https://www.threads.net/@u/post/ABC123"),
        )
    }

    @Test fun nullForNonThreads() {
        assertNull(ThreadsUrl.embedUrlOrNull("https://www.youtube.com/watch?v=abc"))
        assertNull(ThreadsUrl.embedUrlOrNull("https://www.threads.com/@someone"))
    }

    @Test fun extractsPostCode() {
        assertEquals("DY3m2JQjXHZ", ThreadsUrl.postCode("https://www.threads.com/@rico_y9527/post/DY3m2JQjXHZ?x=1"))
        assertNull(ThreadsUrl.postCode("https://youtube.com/watch?v=x"))
    }

    @Test fun recognizesOpaqueShareUrlWithoutTreatingItAsPostYet() {
        val share = "https://www.threads.com/share/_hqU7fBcZ/"
        assertTrue(ThreadsUrl.isSupported(share))
        assertTrue(ThreadsUrl.isShare(share))
        assertNull(ThreadsUrl.embedUrlOrNull(share))
        assertNull(ThreadsUrl.postCode(share))
    }

    @Test fun permalinkIsSupportedButDoesNotNeedShareResolution() {
        val post = "https://www.threads.com/@rico_y9527/post/DY3m2JQjXHZ"
        assertTrue(ThreadsUrl.isSupported(post))
        assertFalse(ThreadsUrl.isShare(post))
    }

    @Test fun rejectsLookalikeShareUrls() {
        assertFalse(ThreadsUrl.isSupported("https://threads.example/share/_hqU7fBcZ/"))
        assertFalse(ThreadsUrl.isSupported("https://www.threads.com/shares/_hqU7fBcZ/"))
        assertFalse(ThreadsUrl.isSupported("https://www.youtube.com/watch?v=abc"))
    }
}
