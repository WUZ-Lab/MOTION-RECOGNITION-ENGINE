package io.motionengine.core

import kotlin.math.*

data class PoseFeatures(val timestampMs: Long, val values: FloatArray) {
    val knee: Float get() = (values[0] + values[1]) * 90f
    val elbow: Float get() = (values[4] + values[5]) * 90f
    val torsoTilt: Float get() = values[6] * 180f
    val hipY: Float get() = values[10]
    val footY: Float get() = values[11]
    val imageScale: Float get() = values[12]
}

/** Feature v1: 16 base measurements followed by their 16 per-second derivatives. */
class FeatureExtractor {
    private var previous: PoseFeatures? = null

    fun reset() { previous = null }

    /** null means visible joints/geometry are insufficient. Invalid input throws before state changes. */
    fun extract(frame: PoseFrame): PoseFeatures? {
        frame.validate()
        val base = base(frame)
        if (base == null) { reset(); return null }
        val values = FloatArray(FEATURE_COUNT)
        base.copyInto(values)
        val prev = previous
        val dt = prev?.let { (frame.timestampMs - it.timestampMs) / 1000f } ?: 0f
        if (prev != null && dt > 0f && dt <= 0.5f) {
            for (i in 0 until BASE_COUNT) values[BASE_COUNT + i] = ((base[i] - prev.values[i]) / dt).coerceIn(-20f, 20f)
        }
        return PoseFeatures(frame.timestampMs, values).also { previous = it }
    }

    companion object {
        const val VERSION = 1
        const val BASE_COUNT = 16
        const val FEATURE_COUNT = 32
        val requiredJoints = listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

        private fun midpoint(a: Landmark, b: Landmark) = Landmark((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2)
        private fun distance(a: Landmark, b: Landmark): Float = sqrt((a.x-b.x).pow(2) + (a.y-b.y).pow(2) + (a.z-b.z).pow(2))
        private fun angle(a: Landmark, b: Landmark, c: Landmark): Float? {
            val ab = distance(a, b); val cb = distance(c, b)
            if (ab < 1e-5f || cb < 1e-5f) return null
            val dot = (a.x-b.x)*(c.x-b.x) + (a.y-b.y)*(c.y-b.y) + (a.z-b.z)*(c.z-b.z)
            return acos((dot / (ab * cb)).coerceIn(-1f, 1f)) / PI.toFloat()
        }

        fun base(frame: PoseFrame): FloatArray? {
            val w = frame.worldLandmarks; val n = frame.landmarks
            if (requiredJoints.any { min(w[it].visibility, n[it].visibility) < 0.5f }) return null
            val hip = midpoint(w[23], w[24]); val shoulder = midpoint(w[11], w[12])
            val torso = distance(hip, shoulder)
            if (torso < 1e-4f) return null
            val nh = midpoint(n[23], n[24]); val ns = midpoint(n[11], n[12])
            val aspect = frame.imageWidth.toFloat() / frame.imageHeight
            val scale = sqrt(((ns.x-nh.x)*aspect).pow(2) + (ns.y-nh.y).pow(2))
            if (scale < 1e-4f) return null
            val angles = listOf(Triple(23,25,27), Triple(24,26,28), Triple(11,23,25), Triple(12,24,26), Triple(11,13,15), Triple(12,14,16))
                .map { (a,b,c) -> angle(w[a],w[b],w[c]) ?: return null }
            val result = FloatArray(BASE_COUNT)
            angles.forEachIndexed { i, value -> result[i] = value }
            result[6] = acos(((hip.y - shoulder.y) / torso).coerceIn(-1f, 1f)) / PI.toFloat()
            result[7] = distance(w[15], hip) / torso
            result[8] = distance(w[16], hip) / torso
            result[9] = distance(w[27], w[28]) / torso
            result[10] = nh.y
            result[11] = (n[27].y + n[28].y) / 2
            result[12] = scale
            result[13] = ((w[15].y + w[16].y) / 2 - hip.y) / torso
            result[14] = ((w[27].y + w[28].y) / 2 - hip.y) / torso
            result[15] = distance(hip, midpoint(w[27], w[28])) / torso
            return result.takeIf { it.all(Float::isFinite) }
        }
    }
}

/** Causal sample-and-hold: never interpolates from a future frame. Holds at most 100 ms. */
class TemporalWindow(private val manifest: ModelManifest) {
    private val rows = ArrayDeque<FloatArray>()
    private var origin: Long? = null
    private var tick = 0L
    private var previousTime = 0L
    private var previous: PoseFeatures? = null

    fun reset() { rows.clear(); origin = null; tick = 0; previous = null }

    fun add(timestampMs: Long, features: PoseFeatures?) {
        if (origin == null) origin = timestampMs
        val start = origin!!
        // A long gap must not allocate/process an unbounded number of empty samples.
        val latestTick = (timestampMs - start) * manifest.sampleRateHz / 1000
        if (latestTick - tick > manifest.windowSize) {
            rows.clear(); tick = latestTick - manifest.windowSize + 1
        }
        while (start + tick * 1000 / manifest.sampleRateHz <= timestampMs) {
            val at = start + tick * 1000 / manifest.sampleRateHz
            val source = if (at == timestampMs) features else previous?.takeIf { at - previousTime <= 100 }
            val row = FloatArray(manifest.featureCount + 1)
            if (source != null) {
                for (i in 0 until manifest.featureCount) row[i] = (source.values[i] - manifest.mean[i]) / manifest.std[i]
                row[manifest.featureCount] = 1f
            }
            rows.addLast(row)
            if (rows.size > manifest.windowSize) rows.removeFirst()
            tick++
        }
        previous = features
        previousTime = timestampMs
    }

    fun snapshot(): FeatureWindow {
        val channels = manifest.featureCount + 1
        val output = FloatArray(manifest.windowSize * channels)
        var offset = (manifest.windowSize - rows.size) * channels
        var count = 0
        rows.forEach { row ->
            row.copyInto(output, offset); offset += channels
            if (row.last() == 1f) count++
        }
        return FeatureWindow(output, manifest.windowSize, channels, count)
    }
}
