package com.changyow.mediadler.core.extract

object ThreadsUrl {
    private val POST = Regex("""https?://(?:www\.)?threads\.(?:net|com)/(@[^/?#]+/post/[^/?#]+)""")

    /**
     * yt-dlp has no Threads extractor, but a post's `/embed` page exposes the
     * media to the generic html5 extractor. Returns the embed URL for a Threads
     * post URL (dropping tracking query params), or null if it isn't one.
     */
    fun embedUrlOrNull(url: String): String? {
        val match = POST.find(url) ?: return null
        return "https://www.threads.net/${match.groupValues[1]}/embed"
    }
}
