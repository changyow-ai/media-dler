package com.changyow.mediadler.data.ytdlp

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.MediaResource
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fixture-retained download and publication gate only; no classification, queue or publication. */
@RunWith(AndroidJUnit4::class)
class IncrementalHlsValidationDeviceTest {
    @Test fun downloadAndValidatePinnedRetainedPlaylist() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires qaIncrementalValidation=true", args.getString("qaIncrementalValidation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.changyow.mediadler.qa") { "Only the isolated QA target is allowed" }
        val session = UUID.fromString(requireNotNull(args.getString("qaSession"))).toString()
        val external = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        val export = File(external, "qa_incremental_export_$session").apply { check(mkdir()) }
        val work = File(context.noBackupFilesDir, "qa-incremental/$session")
        val state = JSONObject().put("schema", 1).put("session", session).put("status", "STARTED")
            .put("scope", "fixture-retained download; no ad classification or publication claim")
            .put("no_queue_history_or_settings_mutation", true).put("native_execution_joined", true)
            .put("own_uuid_private_work_removed", false)
        val preserved = linkedMapOf<File, JSONObject>()
        val started = SystemClock.elapsedRealtime()
        var failure: Throwable? = null
        var logBytes = 0
        var workOwned = false
        fun failed(error: Throwable) { failure?.addSuppressed(error) ?: run { failure = error } }
        fun attempt(block: () -> Unit) { try { block() } catch (error: Throwable) { failed(error) } }
        fun marker(value: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
        }
        fun snapshot(file: File) = JSONObject().put("bytes", file.length()).put("mtime_ms", file.lastModified())
            .put("sha256", sha256(file))
        fun log(line: String) = synchronized(state) {
            val text = "${System.currentTimeMillis()} +${SystemClock.elapsedRealtime() - started}ms ${line.take(4096)}\n"
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (logBytes + bytes.size <= 256 * 1024) {
                File(export, "native.log").appendBytes(bytes)
                logBytes += bytes.size
            } else state.put("native_log_truncated", true)
            Unit
        }
        marker("QA_INCREMENTAL_EXPORT=${export.absolutePath}")
        try {
            val timeout = args.getString("qaTimeoutSeconds")?.toLong() ?: 180L
            require(timeout in 120..180)
            val fixture = File(requireNotNull(args.getString("qaFixtureDir")))
            check(!Files.isSymbolicLink(fixture.toPath()) && fixture.canonicalFile.parentFile == external &&
                fixture.name == "qa_incremental_fixture_$session" && fixture.isDirectory)
            val fixtureFile = File(fixture, "fixture.json")
            val pin = requireNotNull(args.getString("qaFixtureManifestSha256"))
            require(pin.matches(Regex("[0-9a-f]{64}")))
            check(!Files.isSymbolicLink(fixtureFile.toPath()) && fixtureFile.length() in 1..1_048_576 && sha256(fixtureFile) == pin)
            fixture.walkTopDown().forEach { file ->
                check(!Files.isSymbolicLink(file.toPath())) { "Fixture symlink: ${file.name}" }
                if (file.isFile) preserved[file] = snapshot(file)
            }
            val spec = JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(fixtureFile.readBytes())).toString())
            fixtureFile.copyTo(File(export, "fixture.json"))
            state.put("fixture_manifest_sha256", pin)
            check(spec.getInt("schema") == 1)
            val url = URI(requireNotNull(args.getString("qaIncrementalUrl")))
            fun local(uri: URI) = uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
                uri.userInfo == null && uri.fragment == null
            require(local(url)) { "Fixture URL must use an explicit loopback HTTP port" }
            val manifest = spec.getString("manifest")
            val durations = Regex("(?m)^#EXTINF:([0-9.]+),").findAll(manifest).map { it.groupValues[1].toDouble() }.toList()
            val expected = spec.getDouble("expected_duration_seconds")
            require(expected.isFinite() && expected > 0 && durations.isNotEmpty() &&
                kotlin.math.abs(durations.sum() - expected) < 0.000001)
            manifest.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.forEach {
                val segment = url.resolve(it)
                require(local(segment) && segment.port == url.port && segment.path.endsWith(".ts"))
            }
            val method = spec.getString("expected_decode_method")
            require(method in setOf("during_download", "after_download"))
            val cancelAfter = if (spec.has("cancel_after_fragments")) {
                val value = spec.get("cancel_after_fragments")
                require(value is Int && value in 1 until durations.size)
                value
            } else null
            val rateLimit = if (spec.has("rate_limit_bytes_per_second")) {
                val value = spec.get("rate_limit_bytes_per_second")
                require(value is Int && value in 1024..8 * 1024 * 1024)
                value
            } else null
            state.put("fixture_url", url.toString()).put("expected_decode_method", method)
            val apkHash = sha256(File(context.applicationInfo.sourceDir))
            check(apkHash == spec.getString("target_apk_sha256")) { "Installed QA APK is not pinned" }
            state.put("installed_target_apk_sha256", apkHash)
            check(work.parentFile!!.let { !Files.isSymbolicLink(it.toPath()) && (it.isDirectory || it.mkdir()) })
            check(work.mkdir())
            workOwned = true
            val resume = File(work, ".resume").apply { check(mkdir()) }
            val assetCopies = File(work, "assets").apply { check(mkdir()) }
            val assets = JSONObject()
            WeightedHlsAds.ASSETS.forEach { name ->
                val target = File(assetCopies, name)
                context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
                val hash = sha256(target)
                check(hash == spec.getJSONObject("packaged_assets_sha256").getString(name)) { "Asset mismatch: $name" }
                assets.put(name, hash)
                preserved[target] = snapshot(target)
                target.copyTo(File(resume, name))
            }
            state.put("packaged_assets_sha256", assets)
            val native = JSONObject()
            for (name in listOf("libpython.so", "libffmpeg.so", "libffprobe.so", "libqjs.so")) {
                val hash = sha256(File(context.applicationInfo.nativeLibraryDir, name))
                spec.optJSONObject("native_libraries_sha256")?.let { pins ->
                    if (pins.has(name)) check(hash == pins.getString(name)) { "Native mismatch: $name" }
                }
                native.put(name, hash)
            }
            state.put("native_libraries_sha256", native)
            val hls = PreparedHlsResume(MediaResource(url.toString()), manifest,
                digest(manifest.toByteArray(Charsets.UTF_8)), skippedAds = 1)
            // skippedAds enables the validation protocol for a preselected fixture, not a classifier result.
            val sessionFile = HlsResumeFiles.writeSession(work, hls, skipAdAnalysis = false, audioOnly = false)
            check(!JSONObject(sessionFile.readText()).has("ad_plan"))
            val request = YoutubeDLRequest(url.toString()).apply {
                addOption("-o", File(work, "media.%(ext)s").absolutePath)
                addOption("-f", "best[ext=mp4]/best")
                listOf("--no-mtime", "--no-warnings", "--no-cache-dir", "--hls-prefer-native",
                    "--abort-on-unavailable-fragments", "--newline", "--no-colors").forEach { addOption(it) }
                addOption("--fixup", "force")
                addOption("--remux-video", "mp4")
                addOption("--retries", "0")
                addOption("--fragment-retries", "0")
                addOption("--concurrent-fragments", "1")
                addOption("--socket-timeout", "15")
                rateLimit?.let { addOption("--limit-rate", it.toString()) }
            }
            val engine = context.appContainer.engine
            withTimeout(timeout * 1000) {
                check(engine.ensureInit() is EngineState.Ready)
                val downloadStart = SystemClock.elapsedRealtime()
                state.put("native_execution_joined", false)
                try {
                    if (cancelAfter != null) {
                        val firstStart = SystemClock.elapsedRealtime()
                        coroutineScope {
                            val first = async { engine.executeDownload(request, sessionFile, ::log) }
                            try {
                                val checkpoint = File(resume, "checkpoint.json")
                                while (!checkpoint.isFile || JSONObject(checkpoint.readText()).getInt("index") < cancelAfter) {
                                    if (first.isCompleted) { first.await(); error("Download finished before cancellation threshold") }
                                    delay(50)
                                }
                                check(first.isActive) { "Download already finished at cancellation threshold" }
                                log("QA_CANCEL_AFTER_FRAGMENTS $cancelAfter")
                                first.cancelAndJoin()
                                check(first.isCancelled)
                            } finally {
                                withContext(NonCancellable) { first.cancelAndJoin() }
                                state.put("first_attempt_cancelled_and_joined", first.isCancelled)
                                    .put("first_attempt_elapsed_ms", SystemClock.elapsedRealtime() - firstStart)
                            }
                        }
                        val receipt = File(resume, "download-validation.json")
                        check(!receipt.isFile || JSONObject(receipt.readText()).getString("status") != "valid") {
                            "Cancelled download retained a successful validation receipt"
                        }
                        state.put("cancelled_attempt_successful_receipt_absent", true)
                        val checkpoint = File(resume, "checkpoint.json")
                        val interrupted = JSONObject(checkpoint.readText())
                        check(interrupted.getInt("index") in cancelAfter until durations.size)
                        checkpoint.copyTo(File(export, "interrupted-checkpoint.json"))
                        val partial = File(work, interrupted.getString("filename") + ".part")
                        check(partial.canonicalFile.parentFile == work.canonicalFile && partial.isFile)
                        val partialState = snapshot(partial).put("committed_end_offset", interrupted.getLong("end_offset"))
                        check(partial.length() >= interrupted.getLong("end_offset"))
                        state.put("interrupted_partial", partialState).put("cancel_after_fragments", cancelAfter)
                        File(export, "interrupted-partial.json").writeText(partialState.toString(2))
                        if (receipt.isFile) receipt.copyTo(File(export, "interrupted-download-validation.json"))
                        val oldToken = JSONObject(sessionFile.readText()).getJSONObject("media_validation").getString("token")
                        HlsResumeFiles.writeSession(work, hls, skipAdAnalysis = false, audioOnly = false)
                        val fresh = JSONObject(sessionFile.readText())
                        check(!fresh.has("ad_plan") && fresh.getJSONObject("media_validation").getString("token") != oldToken)
                        state.put("resume_session_token_refreshed", true)
                        log("QA_RESUME_DOWNLOAD")
                    }
                    engine.executeDownload(request, sessionFile, ::log)
                }
                finally {
                    // Production executeNative returns only after process-group/readers cleanup, including cancellation.
                    state.put("native_execution_joined", true)
                    state.put("download_elapsed_ms", SystemClock.elapsedRealtime() - downloadStart)
                }
                val output = File(work, "media.mp4")
                check(output.isFile && output.length() > 0) { "Expected final MP4 missing" }
                preserved[output] = snapshot(output)
                state.put("output_before_validation", preserved.getValue(output))
                val validationStart = SystemClock.elapsedRealtime()
                state.put("native_execution_joined", false)
                try { engine.validateHlsMedia(output, hls.copy(adPlan = File(resume, "fixture-anchor.json")), false) }
                finally {
                    state.put("native_execution_joined", true)
                    state.put("final_validation_elapsed_ms", SystemClock.elapsedRealtime() - validationStart)
                }
                state.put("output_after_validation", snapshot(output))
                WeightedHlsAds.ASSETS.forEach { name ->
                    val executedName = if (name == "hls_resume.py") "runner.py" else name
                    check(sha256(File(resume, executedName)) == assets.getString(name)) { "Executed asset changed: $name" }
                }
                state.put("executed_assets_match_packaged_pins", true)
                val final = JSONObject(File(resume, "media-validation.json").readText())
                check(final.getString("status") == "valid" && final.getString("decode_method") == method &&
                    final.getBoolean("full_av_decode") && final.getBoolean("packet_dts_monotonic") &&
                    final.getBoolean("final_full_av_decode") == (method == "after_download"))
                if (method == "during_download") {
                    val receipt = JSONObject(File(resume, "download-validation.json").readText())
                    val checkpoint = JSONObject(File(resume, "checkpoint.json").readText())
                    check(receipt.getString("status") == "valid" && receipt.getBoolean("eof") &&
                        receipt.getBoolean("full_decode") && receipt.getInt("segments") == durations.size &&
                        checkpoint.getInt("index") == durations.size &&
                        receipt.getInt("first_batch_segments") > 1 && receipt.getInt("batch_count") > 0 &&
                        receipt.getLong("source_bytes") == checkpoint.getLong("end_offset"))
                }
                if (spec.optBoolean("compare_final_decode", false)) {
                    val validationFile = File(resume, "media-validation.json")
                    validationFile.copyTo(File(export, "initial-media-validation.json"))
                    val receipt = File(resume, "download-validation.json")
                    val saved = File(resume, "download-validation.baseline-held.json")
                    val receiptBefore = snapshot(receipt)
                    check(receipt.isFile && !saved.exists() && receipt.renameTo(saved))
                    val baselineStart = SystemClock.elapsedRealtime()
                    state.put("native_execution_joined", false)
                    try {
                        log("QA_BASELINE_FINAL_DECODE")
                        engine.validateHlsMedia(output, hls.copy(adPlan = File(resume, "fixture-anchor.json")), false)
                        val baseline = JSONObject(validationFile.readText())
                        check(baseline.getString("status") == "valid" && baseline.getString("decode_method") == "after_download" &&
                            baseline.getBoolean("full_av_decode") && baseline.getBoolean("final_full_av_decode") &&
                            baseline.getBoolean("packet_dts_monotonic"))
                    } finally {
                        state.put("native_execution_joined", true)
                            .put("baseline_final_decode_elapsed_ms", SystemClock.elapsedRealtime() - baselineStart)
                        check(saved.renameTo(receipt)) { "Failed to restore this UUID's download receipt" }
                        check(snapshot(receipt).toString() == receiptBefore.toString()) { "Baseline changed download receipt" }
                        state.put("baseline_download_receipt_restored", true)
                        validationFile.takeIf { it.isFile }?.copyTo(File(export, "baseline-media-validation.json"))
                        File(export, "initial-media-validation.json").copyTo(validationFile, overwrite = true)
                        check(snapshot(output).toString() == preserved.getValue(output).toString()) { "Baseline changed final MP4" }
                    }
                }
            }
            state.put("status", "PASS")
        } catch (error: Throwable) { failed(error) }
        finally {
            val checks = JSONArray()
            preserved.forEach { (file, before) -> attempt {
                val after = snapshot(file)
                checks.put(JSONObject().put("path", file.absolutePath).put("before", before).put("after", after))
                check(before.toString() == after.toString()) { "Preserved input/output changed: ${file.name}" }
            } }
            state.put("preservation", checks).put("native_log_bytes", logBytes)
            for (name in listOf("download-validation.json", "media-validation.json", "checkpoint.json", "session.json")) {
                attempt {
                    File(work, ".resume/$name").takeIf { workOwned && it.isFile }?.copyTo(File(export, name))
                }
            }
            attempt { File(work, "media.mp4").takeIf { workOwned && it.isFile }?.copyTo(File(export, "media.mp4")) }
            attempt {
                check(state.getBoolean("native_execution_joined")) { "Native execution still owns private work" }
                if (workOwned) check(work.deleteRecursively()) { "Own private UUID cleanup failed" }
                state.put("own_uuid_private_work_removed", workOwned)
            }
            state.put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
                .put("total_elapsed_ms", SystemClock.elapsedRealtime() - started)
            if (failure != null) state.put("status", "FAIL")
            File(export, "test-state.json").writeText(state.toString(2))
            marker("QA_INCREMENTAL_OUTCOME=${state.getString("status")}")
        }
        failure?.let { throw it }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
