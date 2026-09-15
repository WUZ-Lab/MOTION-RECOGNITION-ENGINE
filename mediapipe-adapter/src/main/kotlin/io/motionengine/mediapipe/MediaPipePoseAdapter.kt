package io.motionengine.mediapipe

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import io.motionengine.core.Landmark
import io.motionengine.core.PoseFrame

object MediaPipePoseAdapter {
    /** Dimensions must match the upright image sent to MediaPipe, before preview mirroring. */
    fun fromResult(result: PoseLandmarkerResult, imageWidth: Int, imageHeight: Int): PoseFrame? {
        require(imageWidth > 0 && imageHeight > 0)
        if (result.landmarks().isEmpty()) return null
        require(result.landmarks().size == 1 && result.worldLandmarks().size == 1) { "Expected a single tracked person" }
        return PoseFrame(
            result.timestampMs(), imageWidth, imageHeight,
            result.landmarks().single().map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) },
            result.worldLandmarks().single().map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) },
        ).also { it.validate() }
    }
}
