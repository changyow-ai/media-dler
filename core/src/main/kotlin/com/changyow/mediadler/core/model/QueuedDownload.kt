package com.changyow.mediadler.core.model

import kotlinx.serialization.Serializable

/** A durable episode reference; temporary stream URLs are deliberately absent. */
@Serializable data class EpisodePage(
    val playbackUrl: String,
    val kind: SourceKind,
    val originalUrl: String = playbackUrl,
    val catalogUrl: String? = null,
    val lineId: String? = null,
    val lineName: String? = null,
    val title: String? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val thumbnailUrl: String? = null,
    val metadataOverride: EpisodeMetadata? = null,
)

@Serializable sealed interface QueuedSpec {
    @Serializable data class Page(val page: EpisodePage, val selection: FormatSelection) : QueuedSpec
    @Serializable data class Resolved(val request: DownloadRequest) : QueuedSpec
}

/** Continuation and item resume reuse this checkpoint; failed/canceled retries resolve the page again. */
@Serializable data class QueuedDownload(
    val task: DownloadTask,
    val spec: QueuedSpec,
    val attempt: Int = 0,
    val resolvedRequest: DownloadRequest? = null,
    val deleteRequested: Boolean = false,
    val reuseResolvedRequest: Boolean = false,
    /** Null denotes a legacy record that must be migrated before execution. */
    val adRemovalPolicy: AdRemovalPolicy? = null,
)

data class DownloadClaim(
    val id: String,
    val attempt: Int,
    val spec: QueuedSpec,
    val resolvedRequest: DownloadRequest? = null,
    val adRemovalPolicy: AdRemovalPolicy = AdRemovalPolicy.CONFIRMED_ONLY,
    val skipAdAnalysis: Boolean = false,
)
