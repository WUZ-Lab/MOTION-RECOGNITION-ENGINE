package io.motionengine.core

/** Stateful, single-caller engine. All calls (including close/reset) must be serialized by the caller. */
class MotionEngine(
    private val classifier: MotionClassifier,
    definitions: List<ExerciseDefinition> = BuiltInExercises.definitions,
) {
    private val manifest = classifier.manifest.also { it.validate() }
    private val extractor = FeatureExtractor()
    private val window = TemporalWindow(manifest)
    private val trackers = definitions.associate { it.id to it.createTracker(manifest.progress) }
    private val updates = mutableMapOf<String, ProgressUpdate>()
    private data class Completion(val start: Long, val end: Long)
    private val pending = mutableMapOf<String, Completion>()
    private var lastTimestamp: Long? = null
    private var lastGoodTimestamp: Long? = null
    private var candidate: String? = null
    private var candidateSince = 0L
    private var total = 0
    private var lastCountedEnd = -1L
    private var closed = false

    init {
        require(definitions.map { it.id }.distinct().size == definitions.size) { "Duplicate exercise definition" }
        require(manifest.classes.filter { it != Exercises.OTHER }.all { it in trackers }) { "Missing progress definition for model class" }
    }

    fun process(frame: PoseFrame): RecognitionResult {
        check(!closed) { "Engine is closed" }
        frame.validate()
        validateTimestamp(frame.timestampMs)
        prepare(frame.timestampMs)
        val features = extractor.extract(frame)
        return accept(frame.timestampMs, features)
    }

    /** Call when no pose was detected, using the same clock as PoseFrame timestamps. */
    fun processMissing(timestampMs: Long): RecognitionResult {
        check(!closed) { "Engine is closed" }
        validateTimestamp(timestampMs)
        prepare(timestampMs)
        extractor.reset()
        return accept(timestampMs, null)
    }

    private fun validateTimestamp(timestampMs: Long) {
        require(timestampMs >= 0 && (lastTimestamp == null || timestampMs > lastTimestamp!!)) { "Timestamps must be strictly increasing" }
    }

    private fun prepare(timestampMs: Long) {
        if (lastGoodTimestamp != null && timestampMs - lastGoodTimestamp!! > 500) clearTracking()
        lastTimestamp = timestampMs
    }

    private fun accept(timestampMs: Long, features: PoseFeatures?): RecognitionResult {
        window.add(timestampMs, features)
        if (features == null) {
            candidate = null
            trackers.values.forEach { it.pause() }
            // Never award a completion across an unobserved interval.
            pending.clear()
            return result(timestampMs, RecognitionStatus.POOR_POSE)
        }
        lastGoodTimestamp = timestampMs
        trackers.forEach { (id, tracker) ->
            val update = tracker.update(features)
            updates[id] = update
            if (update.completed && update.startedAtMs != null) pending[id] = Completion(update.startedAtMs, timestampMs)
        }
        if (updates[Exercises.JUMP_SQUAT]?.airborne == true || updates[Exercises.BURPEE]?.airborne == true) pending.remove(Exercises.SQUAT)
        pending.entries.removeAll { timestampMs - it.value.end > 1500 || it.value.start <= lastCountedEnd }
        val snapshot = window.snapshot()
        if (snapshot.validSteps < manifest.minValidSteps) return result(timestampMs, RecognitionStatus.WARMING_UP)
        val scores = classifier.classify(snapshot)
        require(scores.size == manifest.classes.size && scores.all { it.isFinite() && it in 0f..1f } &&
            kotlin.math.abs(scores.sum() - 1f) <= 0.02f) { "Classifier must return finite softmax scores in class order" }
        val ranking = scores.indices.sortedByDescending { scores[it] }
        val index = ranking.first()
        val confidence = scores[index]
        val label = manifest.classes[index]
        if (confidence < manifest.confidenceThreshold || confidence - scores[ranking[1]] < manifest.marginThreshold) {
            candidate = null
            return result(timestampMs, RecognitionStatus.UNCERTAIN, confidence = confidence)
        }
        if (candidate != label) { candidate = label; candidateSince = timestampMs }
        if (timestampMs - candidateSince < manifest.stableDurationMs) return result(timestampMs, RecognitionStatus.UNCERTAIN, confidence = confidence)
        if (label == Exercises.OTHER) {
            // Keep tentative phase history: classification may lag the start of a movement.
            // No unclassified completion is awarded later after a confident other result.
            pending.clear()
            return result(timestampMs, RecognitionStatus.OTHER, confidence = confidence)
        }
        val completion = pending[label]
        val completed = completion != null && timestampMs - completion.end >= manifest.progress.completionDelayMs
        if (completed) {
            total++
            lastCountedEnd = completion!!.end
            pending.entries.removeAll { it.value.start <= lastCountedEnd }
        }
        val update = updates[label]
        // A tracker may finish before the delayed, classified completion is confirmed.
        val progress = if (completed) 1f else update?.progress?.coerceAtMost(0.99f)
        return RecognitionResult(timestampMs, label, confidence, progress,
            if (completed) "complete" else update?.phase, RecognitionStatus.TRACKING, total, completed)
    }

    private fun result(timestamp: Long, status: RecognitionStatus, confidence: Float = 0f) =
        RecognitionResult(timestamp, confidence = confidence, status = status, completedReps = total)

    private fun clearTracking() {
        extractor.reset(); window.reset(); trackers.values.forEach { it.reset() }
        updates.clear(); pending.clear(); candidate = null; lastGoodTimestamp = null
    }

    fun reset() {
        check(!closed) { "Engine is closed" }
        clearTracking(); lastTimestamp = null; total = 0; lastCountedEnd = -1
    }

    fun close() {
        if (!closed) {
            closed = true
            clearTracking()
            classifier.close()
        }
    }
}
