package io.motionengine.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** MediaPipe landmark order. Image x/y are normalized to the upright image dimensions. */
@Serializable
data class Landmark(val x: Float, val y: Float, val z: Float, val visibility: Float = 1f)

@Serializable
data class PoseFrame(
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarks: List<Landmark>,
    val worldLandmarks: List<Landmark>,
) {
    fun validate() {
        require(timestampMs >= 0) { "timestampMs must be non-negative" }
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive" }
        require(landmarks.size == 33 && worldLandmarks.size == 33) { "Expected 33 image and world landmarks" }
        require((landmarks + worldLandmarks).all {
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite() &&
                it.visibility.isFinite() && it.visibility in 0f..1f
        }) { "Coordinates must be finite and visibility must be in [0, 1]" }
    }
}

enum class RecognitionStatus { WARMING_UP, UNCERTAIN, TRACKING, OTHER, POOR_POSE }

sealed interface RecognitionMode {
    data object Auto : RecognitionMode
    /** Tracks the selected exercise geometrically; does not claim automatic exercise recognition. */
    data class Guided(val exerciseId: String) : RecognitionMode
}

enum class RecognitionSource { MODEL, GUIDED }
enum class PoseIssue { NO_POSE, LOW_VISIBILITY, DEGENERATE_GEOMETRY }

@Serializable
data class PoseQuality(
    val usableForTracking: Boolean = false,
    val usableForClassification: Boolean = false,
    val missingJoints: List<Int> = emptyList(),
    val issue: PoseIssue? = null,
)

/** The current frame, independent of delayed completion events. */
@Serializable
data class TrackingState(
    val exerciseId: String? = null,
    val confidence: Float = 0f,
    val progress: Float? = null,
    val phase: String? = null,
    val status: RecognitionStatus,
    val source: RecognitionSource = RecognitionSource.MODEL,
)

/** IDs increase within a session. reset() starts a new session and resets IDs and counts. */
@Serializable
data class RepEvent(
    val repId: Long,
    val exerciseId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val emittedAtMs: Long,
    val confidence: Float? = null,
    val source: RecognitionSource = RecognitionSource.MODEL,
)

data class TrackingConfig(
    val progress: ProgressConfig = ProgressConfig(),
    val exerciseProgress: Map<String, ProgressConfig> = emptyMap(),
    val maximumPoseGapMs: Long = 500,
    val maximumObservationGapMs: Long = 200,
    val completionTimeoutMs: Long = 3000,
) {
    fun forExercise(id: String): ProgressConfig = exerciseProgress[id] ?: progress
    fun validate() {
        progress.validate()
        require(exerciseProgress.keys.all { it.isNotBlank() && it != Exercises.OTHER })
        exerciseProgress.values.forEach { it.validate() }
        require(maximumObservationGapMs in 1..maximumPoseGapMs && maximumPoseGapMs in 200..2000)
        require(completionTimeoutMs in 1..10000)
        require((exerciseProgress.values + progress).all { completionTimeoutMs >= it.completionDelayMs + maximumPoseGapMs })
    }
}

@Serializable
data class RecognitionResult(
    val timestampMs: Long,
    val exerciseId: String? = null,
    val confidence: Float = 0f,
    val progress: Float? = null,
    val phase: String? = null,
    val status: RecognitionStatus,
    val completedReps: Int = 0,
    val repCompleted: Boolean = false,
    /** Legacy top-level fields show a completion when one is emitted; use current for live UI. */
    val current: TrackingState = TrackingState(exerciseId, confidence, progress, phase, status),
    val events: List<RepEvent> = emptyList(),
    val repsByExercise: Map<String, Int> = emptyMap(),
    val quality: PoseQuality = PoseQuality(),
)

object Exercises {
    const val SQUAT = "squat"
    const val BURPEE = "burpee"
    const val JUMP_SQUAT = "jump_squat"
    const val PUSH_UP = "push_up"
    const val OTHER = "other"
    val builtIn = listOf(SQUAT, BURPEE, JUMP_SQUAT, PUSH_UP, OTHER)
}

/** The mask is the last channel of each timestep. Data is row-major [1, steps, channels]. */
data class FeatureWindow(val data: FloatArray, val steps: Int, val channels: Int, val validSteps: Int)

interface MotionClassifier {
    val manifest: ModelManifest
    /** Returns softmax scores in manifest.classes order. Called on the caller's thread. */
    fun classify(window: FeatureWindow): FloatArray
    fun close() {}
}

@Serializable
data class ModelManifest(
    val schemaVersion: Int = 1,
    val featureVersion: Int = 1,
    val modelFile: String = "motion.tflite",
    val sha256: String,
    val classes: List<String> = Exercises.builtIn,
    val sampleRateHz: Int = 30,
    val windowSize: Int = 180,
    val featureCount: Int = 32,
    val mean: List<Float> = List(32) { 0f },
    val std: List<Float> = List(32) { 1f },
    val confidenceThreshold: Float = 0.7f,
    val marginThreshold: Float = 0.15f,
    val stableDurationMs: Long = 200,
    val minValidSteps: Int = 15,
    val progress: ProgressConfig = ProgressConfig(),
    val trainingData: String = "real",
) {
    fun validate() {
        require(schemaVersion == 1 && featureVersion == FeatureExtractor.VERSION) { "Incompatible model/feature version" }
        require(sampleRateHz == 30 && windowSize == 180 && featureCount == FeatureExtractor.FEATURE_COUNT) { "Incompatible input shape" }
        require(classes.size >= 2 && classes.distinct().size == classes.size && classes.all { it.isNotBlank() })
        require(Exercises.OTHER in classes) { "Model must include other class" }
        require(mean.size == featureCount && std.size == featureCount)
        require(mean.all { it.isFinite() } && std.all { it.isFinite() && it > 0f })
        require(confidenceThreshold in 0f..1f && marginThreshold in 0f..1f)
        require(stableDurationMs in 0..2000 && minValidSteps in 1..windowSize)
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "Expected lowercase SHA-256" }
        require(modelFile.isNotBlank() && '/' !in modelFile && '\\' !in modelFile && modelFile != "..")
        progress.validate()
    }
}

/** JSONL consists of one session header followed by frame records, including missing detections. */
@Serializable
data class SessionHeader(
    val type: String = "session",
    val schemaVersion: Int = 1,
    val sessionId: String,
    val participantId: String,
    val exerciseLabel: String,
    val cameraView: String = "fixed_oblique",
    val poseModel: String = "pose_landmarker_lite.task",
    val poseSettings: Map<String, String> = mapOf("numPoses" to "1", "minConfidence" to "0.5"),
)

@Serializable
data class FrameRecord(val timestampMs: Long, val pose: PoseFrame? = null, val type: String = "frame")

@Serializable
data class Annotation(
    val startMs: Long,
    val endMs: Long,
    val exerciseId: String,
    val valid: Boolean = true,
    /** Include start/end and phase boundaries; names are documented in docs/data-format.md. */
    val phases: Map<String, Long> = emptyMap(),
)

@Serializable
data class SessionAnnotations(val schemaVersion: Int = 1, val sessionId: String, val repetitions: List<Annotation>)

object MotionJson {
    val format = Json { encodeDefaults = true; prettyPrint = false; ignoreUnknownKeys = false }
}
