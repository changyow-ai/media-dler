package com.changyow.mediadler.data.ytdlp

import android.os.Bundle
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.MainActivity
import com.changyow.mediadler.appContainer
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exact retained-media validator only: no download job, publication, OCR or setting mutation. */
@RunWith(AndroidJUnit4::class)
class RetainedMediaValidationDeviceTest {
    @Test fun compareFrozenAndPackagedValidatorOnRealMedia() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires qaRetainedValidation=true", args.getString("qaRetainedValidation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.changyow.mediadler.qa") { "Only the isolated QA target is allowed" }
        val session = UUID.fromString(requireNotNull(args.getString("qaSession"))).toString()
        val timeoutSeconds = args.getString("qaTimeoutSeconds")?.toLong() ?: 180L
        require(timeoutSeconds in 120..180)
        val external = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        val fixture = File(requireNotNull(args.getString("qaFixtureDir")))
        check(!Files.isSymbolicLink(fixture.toPath()) && fixture.canonicalFile.parentFile == external &&
            fixture.name == "qa_retained_validation_fixture_$session" && fixture.isDirectory)
        val manifest = File(fixture, "fixture.json")
        val manifestHash = requireNotNull(args.getString("qaFixtureManifestSha256"))
        require(manifestHash.matches(Regex("[0-9a-f]{64}")))
        check(!Files.isSymbolicLink(manifest.toPath()) && sha256(manifest) == manifestHash)
        val spec = JSONObject(manifest.readText())
        check(spec.getInt("schema") == 1)
        val files = spec.getJSONArray("files")
        check(files.length() == 3 && (0 until files.length()).map {
            files.getJSONObject(it).getString("filename")
        }.toSet() == FIXTURE_HASHES.keys)
        val export = File(external, "qa_retained_validation_export_$session").apply { check(mkdir()) }
        val base = File(context.noBackupFilesDir, "qa-retained-validation").apply { check(isDirectory || mkdir()) }
        check(!Files.isSymbolicLink(base.toPath()))
        val work = File(base, session).apply { check(mkdir()) }
        val diagnostic = File(work, "diagnostic").apply { check(mkdir()) }
        val state = JSONObject().put("schema", 1).put("session", session).put("fixture_manifest_sha256", manifestHash)
            .put("status", "STARTED").put("native_execution_joined", false)
            .put("foreground_state_verified_by_test", false).put("no_queue_or_settings_mutation", true)
        val preserved = linkedMapOf<File, JSONObject>()
        fun snapshot(file: File) = JSONObject().put("bytes", file.length()).put("mtime_ms", file.lastModified())
            .put("sha256", sha256(file))
        fun marker(value: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
        }
        manifest.copyTo(File(export, "fixture.json"))
        marker("QA_RETAINED_VALIDATION_EXPORT=${export.absolutePath}")
        var scenario: ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        fun failed(error: Throwable) { failure?.addSuppressed(error) ?: run { failure = error } }
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            state.put("foreground_window_configured", true)
            val input = File(work, "input").apply { check(mkdir()) }
            preserved[manifest] = snapshot(manifest)
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                val name = item.getString("filename")
                val source = File(fixture, name)
                check(!Files.isSymbolicLink(source.toPath()) && source.isFile &&
                    source.length() == item.getLong("bytes") && sha256(source) == FIXTURE_HASHES.getValue(name) &&
                    item.getString("sha256") == FIXTURE_HASHES.getValue(name)) { "Fixture identity mismatch: $name" }
                preserved[source] = snapshot(source)
                val copy = source.copyTo(File(input, name))
                preserved[copy] = snapshot(copy)
            }
            val apkHash = sha256(File(context.applicationInfo.sourceDir))
            check(apkHash == spec.getString("target_apk_sha256")) { "Installed QA App is not pinned" }
            state.put("installed_target_apk_sha256", apkHash)
            val assets = File(work, "assets").apply { check(mkdir()) }
            val copied = JSONObject()
            WeightedHlsAds.ASSETS.forEach { name ->
                val target = File(assets, name)
                context.assets.open(name).use { source -> target.outputStream().use { source.copyTo(it) } }
                val hash = sha256(target)
                check(hash == spec.getJSONObject("packaged_assets_sha256").getString(name)) { "Asset mismatch: $name" }
                copied.put(name, hash)
                preserved[target] = snapshot(target)
            }
            state.put("packaged_assets_sha256", copied)
            val engine = context.appContainer.engine
            withTimeout(timeoutSeconds * 1000) {
                check(engine.ensureInit() is EngineState.Ready)
                val paths = engine.javaClass.getDeclaredMethod("nativePaths").apply { isAccessible = true }.invoke(engine)
                fun path(name: String) = paths.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(paths) as File
                @Suppress("UNCHECKED_CAST")
                val environment = paths.javaClass.getDeclaredField("environment").apply { isAccessible = true }
                    .get(paths) as Map<String, String>
                val runtime = engine.javaClass.getDeclaredField("runtime").apply { isAccessible = true }
                    .get(engine) as EngineRuntimeAccess
                val native = JSONObject()
                for ((field, library) in listOf("python" to "libpython.so", "ffmpeg" to "libffmpeg.so", "ffprobe" to "libffprobe.so")) {
                    val hash = sha256(path(field))
                    check(hash == spec.getJSONObject("native_libraries_sha256").getString(library)) { "Native mismatch: $library" }
                    native.put(library, hash)
                }
                state.put("native_libraries_sha256", native).put("native_runtime_identity_verified", true)
                val driver = File(work, "driver.py").apply { writeText(DRIVER) }
                state.put("driver_sha256", sha256(driver))
                val process = NativeDownloadProcess(path("python"), environment + ("PYTHONDONTWRITEBYTECODE" to "1"))
                var logBytes = 0
                try {
                    runInterruptible(Dispatchers.IO) {
                        runtime.execute {
                            process.execute(listOf(driver.absolutePath, work.absolutePath,
                                path("ffmpeg").absolutePath, path("ffprobe").absolutePath)) { line ->
                                synchronized(state) {
                                    if (logBytes < 256 * 1024) {
                                        val bounded = line.take(4096) + "\n"
                                        File(export, "native.log").appendText(bounded)
                                        logBytes += bounded.toByteArray().size
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    // execute/runInterruptible retain ownership until the group and readers have joined.
                    process.cancel()
                    state.put("native_execution_joined", true)
                }
            }
            val result = JSONObject(File(diagnostic, "result.json").readText())
            check(result.getString("status") in setOf("PASS", "REVIEW")) { result.toString() }
            state.put("status", result.getString("status"))
            marker("QA_RETAINED_VALIDATION_OUTCOME=${result.getString("status")}")
        } catch (error: Throwable) {
            failed(error)
            state.put("status", "FAIL")
        } finally {
            try {
                val checks = JSONArray()
                preserved.forEach { (file, before) ->
                    val after = snapshot(file)
                    checks.put(JSONObject().put("scope", if (file.toPath().startsWith(work.toPath())) "private_copy" else "external_fixture")
                        .put("filename", file.name).put("before", before).put("after", after))
                    check(before.toString() == after.toString()) { "Input changed: ${file.name}" }
                }
                state.put("input_preservation", checks)
            } catch (error: Throwable) { failed(error); state.put("status", "FAIL") }
            try { scenario?.close(); state.put("activity_scenario_closed", true) }
            catch (error: Throwable) { failed(error) }
            try {
                check(diagnostic.copyRecursively(File(export, "diagnostic")))
                check(work.deleteRecursively()) { "Own private UUID work cleanup failed" }
                state.put("own_uuid_private_work_removed", true)
            } catch (error: Throwable) { failed(error) }
            state.put("failure", failure?.stackTraceToString())
            if (failure != null) state.put("status", "FAIL")
            File(export, "test-state.json").writeText(state.toString(2))
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

    private companion object {
        val FIXTURE_HASHES = mapOf(
            "weighted_hls_ads_old.py" to "7a367addd71cadb1fa70230f1dcda1848cc04aeca98e09f9544bd1c22e5415cd",
            "two.mp4" to "05566989caa9d2ccd352e4aa481728d23cf9488f54fdb64525cf2ef942f22845",
            "four.mp4" to "918b94369e7c65eba0407f6c5fe53bd915e00c265beba2f050b679b91ccf0967",
        )
        val DRIVER = """
            import hashlib, json, os, struct, subprocess, sys, traceback
            from pathlib import Path
            root, ffmpeg, ffprobe = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
            inputs, assets, output = root / 'input', root / 'assets', root / 'diagnostic'
            env = dict(os.environ, PYTHONPATH=str(assets), PYTHONDONTWRITEBYTECODE='1')
            report = {'schema': 1, 'status': 'STARTED', 'cases': [], 'baseline_reproduced': False,
                      'scope': 'Exact native retained validator on frozen real MP4 bytes; no classification or speed claim.'}
            def save():
                (output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
            def run(name, argv, timeout=40):
                completed = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=timeout)
                (output / (name + '.stdout.txt')).write_bytes(completed.stdout[:262144])
                (output / (name + '.stderr.txt')).write_bytes(completed.stderr[:262144])
                value = {'exit': completed.returncode, 'stdout_bytes': len(completed.stdout),
                         'stderr_bytes': len(completed.stderr), 'stdout_truncated': len(completed.stdout) > 262144,
                         'stderr_truncated': len(completed.stderr) > 262144}
                (output / (name + '.process.json')).write_text(json.dumps(value, indent=2))
                return completed, value
            def validate(name, script, media, expected):
                result = output / (name + '.validation.json')
                completed, process = run(name, [sys.executable, str(script), '--validate-media', str(media),
                    '--expected-duration', str(expected), '--validation-result', str(result), '--ffmpeg', ffmpeg, '--ffprobe', ffprobe])
                validation = json.loads(result.read_text()) if result.is_file() else {'status': 'missing'}
                row = {'case': name, 'process': process, 'validation': validation}
                report['cases'].append(row)
                save()
                return row
            def valid(row):
                value = row['validation']
                return (row['process']['exit'] == 0 and row['process']['stderr_bytes'] == 0
                        and not row['process']['stdout_truncated'] and not row['process']['stderr_truncated']
                        and value.get('status') == 'valid'
                        and value.get('full_av_decode') is True and value.get('packet_dts_monotonic') is True)
            def identity(path):
                stat = path.stat()
                return {'bytes': stat.st_size, 'mtime_ns': stat.st_mtime_ns, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
            originals = {str(p): identity(p) for p in (inputs / 'two.mp4', inputs / 'four.mp4')}
            save()
            try:
                for name, executable in [('ffmpeg', ffmpeg), ('ffprobe', ffprobe)]:
                    completed, _ = run(name + '-version', [executable, '-version'], 10)
                    assert completed.returncode == 0
                old = validate('old-two', inputs / 'weighted_hls_ads_old.py', inputs / 'two.mp4', 5)
                reason = old['validation'].get('reason', '')
                report['baseline_reproduced'] = (old['process']['exit'] != 0 and
                    reason.startswith('retained_full_av_decode_failed:') and 'non monotonically increasing dts' in reason.lower())
                current = assets / 'weighted_hls_ads.py'
                for name, expected, frames in [('two', 5, 125), ('four', 11, 275)]:
                    assert valid(validate('fixed-' + name, current, inputs / (name + '.mp4'), expected))
                    # Separate native frame-count probe; save stderr even when it is empty.
                    completed, process = run(name + '-independent-frames', [ffprobe, '-v', 'error', '-count_frames',
                        '-show_streams', '-show_format', '-of', 'json', str(inputs / (name + '.mp4'))])
                    probe = json.loads(completed.stdout)
                    video, = [s for s in probe['streams'] if s['codec_type'] == 'video']
                    assert completed.returncode == 0 and not completed.stderr and int(video['nb_read_frames']) == frames
                    report[name + '_independent_decoded_video_frames'] = int(video['nb_read_frames'])
                wrong = validate('wrong-duration', current, inputs / 'two.mp4', 15)
                assert wrong['process']['exit'] != 0 and wrong['validation'].get('reason') == 'retained_duration_mismatch'
                # Preserve the real container/metadata; zero only its copied mdat payload, never the fixture.
                corrupt = bytearray((inputs / 'two.mp4').read_bytes())
                position, altered = 0, False
                while position + 8 <= len(corrupt):
                    size, kind = struct.unpack('>I4s', corrupt[position:position + 8])
                    header = 8
                    if size == 1:
                        size, = struct.unpack('>Q', corrupt[position + 8:position + 16]); header = 16
                    if size == 0: size = len(corrupt) - position
                    assert size >= header and position + size <= len(corrupt)
                    if kind == b'mdat':
                        corrupt[position + header:position + size] = bytes(size - header); altered = True
                    position += size
                assert altered
                broken = root / 'corrupt-copy.mp4'
                broken.write_bytes(corrupt)
                assert len(corrupt) == originals[str(inputs / 'two.mp4')]['bytes']
                assert identity(broken)['sha256'] != originals[str(inputs / 'two.mp4')]['sha256']
                rejected = validate('corrupted-copy', current, broken, 5)
                assert (rejected['process']['exit'] != 0 and rejected['validation'].get('status') == 'unknown'
                        and rejected['validation'].get('reason'))
                report['status'] = 'PASS' if report['baseline_reproduced'] else 'REVIEW'
                if not report['baseline_reproduced']:
                    report['limitation'] = 'Native frozen validator did not reproduce the precise host DTS red; fixed controls alone do not establish native red/green.'
            except BaseException:
                report['status'] = 'FAIL'; report['error'] = traceback.format_exc()
                raise
            finally:
                report['original_input_preservation'] = {name: {'before': before, 'after': identity(Path(name))}
                                                         for name, before in originals.items()}
                unchanged = all(row['before'] == row['after'] for row in report['original_input_preservation'].values())
                if not unchanged: report['status'] = 'FAIL'
                save()
                assert unchanged, 'Original media changed'
        """.trimIndent()
    }
}
