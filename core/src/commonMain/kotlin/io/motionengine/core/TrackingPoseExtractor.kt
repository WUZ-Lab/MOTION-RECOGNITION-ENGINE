package io.motionengine.core

import kotlin.math.*

/** Geometry for trackers only. Never feed these partial measurements to a feature-v1 model. */
internal class TrackingPoseExtractor(private val exerciseId: String) {
    private var side: Int? = null
    private val full = FeatureExtractor()
    var issue: PoseIssue? = null
        private set

    fun reset() { side = null; full.reset(); issue = null }

    fun extract(frame: PoseFrame, lockSide: Boolean): PoseFeatures? {
        if (exerciseId !in Exercises.builtIn) {
            return full.extract(frame).also { features ->
                issue = if (features != null) null else if (FeatureExtractor.requiredJoints.any {
                    min(frame.landmarks[it].visibility, frame.worldLandmarks[it].visibility) < 0.5f
                }) PoseIssue.LOW_VISIBILITY else PoseIssue.DEGENERATE_GEOMETRY
            }
        }
        val needsArms = exerciseId == Exercises.PUSH_UP || exerciseId == Exercises.BURPEE
        fun joints(offset: Int) = (if (needsArms) listOf(11, 13, 15, 23, 25, 27) else listOf(11, 23, 25, 27)).map { it + offset }
        fun visibility(offset: Int) = joints(offset).minOf { min(frame.landmarks[it].visibility, frame.worldLandmarks[it].visibility) }
        val chosen = if (lockSide && side != null) side!! else {
            // Keep the previous side on ties to avoid jumping between baselines.
            val previous = side ?: 0
            if (visibility(previous) >= visibility(1 - previous)) previous else 1 - previous
        }
        if (visibility(chosen) < 0.5f) { issue = PoseIssue.LOW_VISIBILITY; return null }
        side = chosen
        val w = frame.worldLandmarks
        val n = frame.landmarks
        val shoulder = w[11 + chosen]; val hip = w[23 + chosen]
        val knee = angle(hip, w[25 + chosen], w[27 + chosen])
        val elbow = if (needsArms) angle(shoulder, w[13 + chosen], w[15 + chosen]) else 180f
        val torso = distance(shoulder, hip)
        val aspect = frame.imageWidth.toFloat() / frame.imageHeight
        val scale = hypot((n[11 + chosen].x - n[23 + chosen].x) * aspect, n[11 + chosen].y - n[23 + chosen].y)
        if (knee == null || elbow == null || torso < 1e-4f || scale < 1e-4f) {
            issue = PoseIssue.DEGENERATE_GEOMETRY
            return null
        }
        val values = FloatArray(FeatureExtractor.FEATURE_COUNT)
        values[0] = knee / 180f; values[1] = values[0]
        values[4] = elbow / 180f; values[5] = values[4]
        values[6] = acos(((hip.y - shoulder.y) / torso).coerceIn(-1f, 1f)) / PI.toFloat()
        values[10] = n[23 + chosen].y
        values[11] = n[27 + chosen].y
        values[12] = scale
        issue = null
        return PoseFeatures(frame.timestampMs, values)
    }

    private fun distance(a: Landmark, b: Landmark) = sqrt((a.x-b.x).pow(2) + (a.y-b.y).pow(2) + (a.z-b.z).pow(2))
    private fun angle(a: Landmark, b: Landmark, c: Landmark): Float? {
        val ab = distance(a, b); val cb = distance(c, b)
        if (ab < 1e-5f || cb < 1e-5f) return null
        return acos((((a.x-b.x)*(c.x-b.x) + (a.y-b.y)*(c.y-b.y) + (a.z-b.z)*(c.z-b.z)) / (ab*cb)).coerceIn(-1f, 1f)) * 180f / PI.toFloat()
    }
}
