package io.motionengine.runtime

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.motionengine.core.FeatureWindow
import io.motionengine.core.FrameRecord
import io.motionengine.core.MotionEngine
import io.motionengine.core.MotionJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LiteRtParityTest {
    @Test fun exportedModelMatchesTensorFlowOnAndroidAndRejectsTampering() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        if (InstrumentationRegistry.getArguments().getString("requireModelBundle") == "true") {
            assertTrue("Requested model bundle was not packaged", assets.list("bundle")?.contains("parity.json") == true)
        }
        assumeTrue("Pass -PmodelBundle=artifacts/smoke-model after running the training smoke test",
            assets.list("bundle")?.contains("parity.json") == true)
        val directory = File(instrumentation.targetContext.cacheDir, "parity-model").apply { mkdirs() }
        try {
            for (name in listOf("manifest.json", "motion.tflite", "parity.json")) {
                assets.open("bundle/$name").use { input -> File(directory,name).outputStream().use(input::copyTo) }
            }
            val vector = JSONObject(File(directory,"parity.json").readText())
            val inputs = vector.getJSONArray("input")
            val window = FeatureWindow(FloatArray(inputs.length()) { inputs.getDouble(it).toFloat() }, 180, 33, 180)
            val expected = vector.getJSONArray("scores")
            val bundled = LiteRtClassifier.fromAssets(instrumentation.context, "bundle")
            try {
                val scores = bundled.classify(window)
                assertEquals(expected.length(), scores.size)
                for (i in scores.indices) assertEquals(expected.getDouble(i).toFloat(), scores[i], 1e-4f)
            } finally { bundled.close() }
            val classifier = LiteRtClassifier.fromDirectory(directory)
            try {
                val scores = classifier.classify(window)
                for (i in scores.indices) assertEquals(expected.getDouble(i).toFloat(), scores[i], 1e-4f)
                repeat(5) { classifier.classify(window) }
                val timings = (0 until 30).map {
                    val start = SystemClock.elapsedRealtimeNanos()
                    classifier.classify(window)
                    (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                }.sorted()
                val report = JSONObject().put("device", android.os.Build.MODEL).put("medianMs", timings[15]).put("p95Ms", timings[28])
                File(instrumentation.targetContext.filesDir,"runtime-benchmark.json").writeText(report.toString())
                Log.i("MotionEngineBenchmark", report.toString())
                if (assets.list("bundle")?.contains("session.jsonl") == true) {
                    val engine = MotionEngine(classifier)
                    File(instrumentation.targetContext.filesDir, "replay-predictions.jsonl").bufferedWriter().use { writer ->
                        assets.open("bundle/session.jsonl").bufferedReader().use { reader ->
                            reader.readLine() // session header contains collection labels; not passed to engine
                            for (line in reader.lineSequence()) {
                                val frame = MotionJson.format.decodeFromString<FrameRecord>(line)
                                val result = frame.pose?.let(engine::process) ?: engine.processMissing(frame.timestampMs)
                                writer.write(MotionJson.format.encodeToString(result)); writer.newLine()
                            }
                        }
                    }
                    engine.close()
                }
            } finally { classifier.close() }
            classifier.close()
            try { classifier.classify(window); fail("Closed classifier accepted input") } catch (_: IllegalStateException) { }
            File(directory,"motion.tflite").appendBytes(byteArrayOf(1))
            try { LiteRtClassifier.fromDirectory(directory); fail("Tampered model accepted") } catch (_: IllegalArgumentException) { }
        } finally { directory.deleteRecursively() }
    }
}
