package com.changyow.mediadler.core.download

import com.changyow.mediadler.core.model.DownloadPhase
import com.changyow.mediadler.core.model.DownloadProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** One instance per native analysis attempt. A heartbeat can call [current] during long native work. */
class AdAnalysisProgressAccumulator(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var stage = "source"
    private var completed = 0
    private var completedKnown = false
    private var total: Int? = null
    private var unknown: Int? = null
    private var ads: Int? = null
    private var elapsed = 0L
    private var anchor = nowMillis()
    private var running = true
    private var incomplete = false
    private var structured = false
    private var elapsedKnown = false
    private var checkpointNotice: String? = null

    /** Accept complete JSON snapshots and older stage/count messages without clearing accumulated fields. */
    @Synchronized fun accept(line: String): DownloadProgress? {
        val value = line.trim()
        when {
            value.startsWith(PREFIX) -> {
                val data = runCatching { Json.parseToJsonElement(value.removePrefix(PREFIX)) as? JsonObject }
                    .getOrNull() ?: return null
                val nextStage = (data["stage"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in STAGES }
                    ?: return null
                fun count(key: String) = (data[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
                val nextCompleted = count("completed")
                val nextTotal = count("total")
                val nextUnknown = count("unknown")
                if (nextCompleted != null && nextTotal != null && nextCompleted > nextTotal) return null
                if (nextUnknown != null && nextCompleted != null && nextUnknown > nextCompleted) return null
                advance()
                completed = maxOf(completed, nextCompleted ?: completed)
                completedKnown = completedKnown || nextCompleted != null
                nextTotal?.let { total = maxOf(total ?: 0, it, completed) }
                nextUnknown?.let { unknown = maxOf(unknown ?: 0, it).coerceAtMost(completed) }
                count("ads")?.let { ads = it }
                (data["elapsed_seconds"] as? JsonPrimitive)?.doubleOrNull
                    ?.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE / 1000.0 }
                    ?.let {
                        elapsed = maxOf(elapsed, (it * 1000).toLong())
                        elapsedKnown = true
                    }
                stage = nextStage
                (data["checkpoint_reason"] as? JsonPrimitive)?.contentOrNull?.let { reason ->
                    checkpointNotice = when {
                        reason == "checkpoint_source_changed" -> "來源串流資訊已變更，僅沿用相容分析紀錄"
                        reason == "checkpoint_runtime_policy_changed" -> "分析版本已變更，先前分析未沿用"
                        reason == "checkpoint_candidates_changed" -> "候選清單已變更，先前分析未沿用"
                        reason.startsWith("checkpoint_missing_candidate_") ||
                            reason.startsWith("checkpoint_invalid_candidate_") ||
                            reason in setOf("checkpoint_header_invalid", "checkpoint_active_binding_invalid",
                                "checkpoint_candidate_invalid", "checkpoint_candidate_missing") ->
                                "部分分析紀錄無效，已保留可用進度"
                        else -> null
                    }
                }
                running = stage != "complete"
                structured = true
            }
            value.startsWith("HLS_AD_STAGE ") -> {
                // Structured snapshots contain the more specific source/OCR phase.
                if (structured) return null
                val next = value.removePrefix("HLS_AD_STAGE ").takeIf { it in STAGES } ?: return null
                advance()
                stage = next
                elapsedKnown = true
            }
            value.startsWith("HLS_AD_CANDIDATE ") -> {
                if (structured) return null
                val parts = value.removePrefix("HLS_AD_CANDIDATE ").split(' ')
                val done = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val count = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= done } ?: return null
                advance()
                completed = maxOf(completed, done)
                completedKnown = true
                elapsedKnown = true
                total = maxOf(total ?: 0, count, completed)
            }
            else -> return null
        }
        return render()
    }

    /** Updates elapsed time without requiring another native output line. No ETA is inferred. */
    @Synchronized fun current(): DownloadProgress {
        advance()
        return render()
    }

    /** The frozen plan is authoritative for ad groups; candidate counts are a separate quantity. */
    @Synchronized fun finish(skippedAds: Int, analysisComplete: Boolean?): DownloadProgress {
        advance()
        running = false
        stage = "complete"
        ads = skippedAds.coerceAtLeast(0)
        incomplete = analysisComplete == false
        return render()
    }

    /** Carry the frozen analysis summary into later media-download and validation reports. */
    @Synchronized fun decorate(progress: DownloadProgress): DownloadProgress {
        advance()
        return progress.copy(detail = listOfNotNull(progress.detail, summary()).joinToString("\n"))
    }

    private fun advance() {
        val now = nowMillis()
        if (running) elapsed += (now - anchor).coerceAtLeast(0)
        anchor = now
    }

    private fun render() = DownloadProgress(DownloadPhase.AD_ANALYSIS,
        detail = stageText() + "\n" + summary(), canResume = true)

    private fun stageText(): String = when (stage) {
        "source" -> "等待來源／下載分析片段"
        "scan" -> "分析廣告候選"
        "probe", "remux" -> "分析候選影音"
        "ocr" -> "辨識候選文字"
        "restoring" -> "恢復既有分析"
        "validate" -> "最終驗證"
        "complete" -> when {
            incomplete || (unknown ?: 1) > 0 || total == null -> "分析已結束，保留未確認片段"
            ads == 0 -> "廣告分析完成，未辨識出可移除廣告"
            else -> "廣告分析完成"
        }
        else -> "分析廣告候選"
    }

    private fun summary(): String {
        val counts = (if (completedKnown) "候選已處理 $completed/${total ?: "未知"}" else "候選進度未知") +
            " · 未確認 ${unknown ?: "未知"}"
        val found = ads?.let { "已辨識廣告 $it 段" } ?: "廣告段數待確認"
        val seconds = elapsed / 1000
        val duration = if (seconds >= 3600) "${seconds / 3600}時${seconds / 60 % 60}分${seconds % 60}秒"
            else "${seconds / 60}分${seconds % 60}秒"
        val retained = if (incomplete || (unknown ?: 0) > 0 ||
            stage == "complete" && (total == null || unknown == null)) " · 保留未確認片段" else ""
        val time = if (elapsedKnown) "累計分析 $duration" else "累計分析耗時待確認"
        return "$counts\n$found · $time$retained" + (checkpointNotice?.let { "\n$it" } ?: "")
    }

    companion object {
        const val PREFIX = "HLS_AD_PROGRESS "
        private val STAGES = setOf("source", "scan", "probe", "ocr", "restoring", "remux", "validate", "complete")
    }
}
