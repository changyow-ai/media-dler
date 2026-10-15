package com.changyow.mediadler.core.download

import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaResource
import com.changyow.mediadler.core.model.mergeHttpHeaders

data class DownloadPlan(
    val resource: MediaResource,
    val playlistIndex: Int?,
    val formatArguments: List<String>,
)

/** Keeps page re-extraction and direct-resource downloads on the same download path. */
object DownloadPlanner {
    fun plan(request: DownloadRequest): DownloadPlan {
        val item = request.item
        if (item.downloadTarget == DownloadTarget.PAGE) {
            // Do not substitute an observed stream URL: yt-dlp must refresh formats and
            // retain its own video/audio selection and merge behavior.
            return DownloadPlan(
                resource = MediaResource(item.sourceUrl, item.httpHeaders),
                playlistIndex = item.playlistIndex,
                formatArguments = FormatSelector.args(request.selection),
            )
        }

        val selected = (request.selection as? FormatSelection.SpecificFormat)?.format
        val resource = selected?.resource
            ?: item.formats.firstOrNull { it.resource?.url == item.sourceUrl }?.resource
        val selection = if (selected != null) {
            // The resource already selects the source's format. Its ID is not an ID
            // exposed by yt-dlp's generic extractor for this direct URL.
            if (selected.isImage) FormatSelection.ImageOriginal else FormatSelection.BestVideo
        } else request.selection
        return DownloadPlan(
            resource = MediaResource(
                url = resource?.url ?: item.sourceUrl,
                httpHeaders = mergeHttpHeaders(item.httpHeaders, resource?.httpHeaders.orEmpty()),
            ),
            playlistIndex = null,
            formatArguments = FormatSelector.args(selection),
        )
    }
}
