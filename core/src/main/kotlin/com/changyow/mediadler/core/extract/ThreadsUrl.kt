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

    /** The post short-code (e.g. "DY3m2JQjXHZ") from a Threads URL, or null. */
    fun postCode(url: String): String? =
        POST.find(url)?.groupValues?.get(1)?.substringAfterLast('/')

    /**
     * The author handle (without "@") from a Threads post URL, e.g. "yatesvacuum".
     * On a reply link this is the reply's author, which the embed page uses to mark
     * which rendered post the share points at. Null if it isn't a Threads post URL.
     */
    fun handle(url: String): String? =
        POST.find(url)?.groupValues?.get(1)?.substringBefore("/post/")?.removePrefix("@")
}
