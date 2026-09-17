package io.motionengine.core

/** Stateful, single-caller engine. Serialize process, processMissing, reset and close. */
class MotionEngine(
    private val classifier: MotionClassifier? = null,
    definitions: List<ExerciseDefinition> = BuiltInExercises.definitions,
    val mode: RecognitionMode = RecognitionMode.Auto,
    private val config: TrackingConfig = TrackingConfig(progress = classifier?.manifest?.progress ?: ProgressConfig()),
) {
    private val manifest = classifier?.manifest?.also { it.validate() }
    private val extractor = FeatureExtractor()
    private val window = manifest?.let(::TemporalWindow)
    private val selectedDefinitions = when (mode) {
        RecognitionMode.Auto -> definitions.filter { it.id in (manifest?.classes ?: emptyList()) }
        is RecognitionMode.Guided -> definitions.filter { it.id == mode.exerciseId }
    }
    private val trackers = selectedDefinitions.associate { it.id to it.createTracker(config.forExercise(it.id)) }
    private val geometry = trackers.keys.associateWith(::TrackingPoseExtractor)
    private val updates = mutableMapOf<String, ProgressUpdate>()
    private val lastGood = mutableMapOf<String, Long>()
    private data class RepKey(val exercise: String, val start: Long)
    private data class Evidence(val confidence: Float)
    private data class Completion(val key: RepKey, val end: Long, var observedSince: Long?)
    private val evidence = mutableMapOf<RepKey, Evidence>()
    private val pending = mutableListOf<Completion>()
    private val counts = mutableMapOf<String, Int>()
    private var lastTimestamp: Long? = null
    private var lastClassifierGood: Long? = null
    private var candidate: String? = null
    private var candidateSince = 0L
    private var total = 0
    private var nextRepId = 1L
    private var lastCountedEnd = -1L
    private var closed = false
    private val source get() = if (mode is RecognitionMode.Guided) RecognitionSource.GUIDED else RecognitionSource.MODEL

    init {
        config.validate()
        require(definitions.map { it.id }.distinct().size == definitions.size) { "Duplicate exercise definition" }
        when (mode) {
            RecognitionMode.Auto -> {
                require(manifest != null) { "AUTO requires a classifier; use RecognitionMode.Guided for model-free tracking" }
                require(manifest.classes.filter { it != Exercises.OTHER }.all { it in trackers }) { "Missing progress definition for model class" }
            }
            is RecognitionMode.Guided -> {
                require(classifier == null) { "GUIDED uses geometry, not a classifier" }
                require(mode.exerciseId != Exercises.OTHER && mode.exerciseId in trackers) { "Unknown guided exercise" }
            }
        }
        require(config.exerciseProgress.keys.all { id -> definitions.any { it.id == id } }) { "Unknown exercise configuration" }
    }

    fun process(frame: PoseFrame): RecognitionResult {
        check(!closed) { "Engine is closed" }
        frame.validate()
        validateTimestamp(frame.timestampMs)
        return accept(frame.timestampMs, frame)
    }

    /** Uses the same clock as PoseFrame; call for every observed missing detection. */
    fun processMissing(timestampMs: Long): RecognitionResult {
        check(!closed) { "Engine is closed" }
        validateTimestamp(timestampMs)
        return accept(timestampMs, null)
    }

    private fun validateTimestamp(timestampMs: Long) {
        require(timestampMs >= 0 && (lastTimestamp == null || timestampMs > lastTimestamp!!)) { "Timestamps must be strictly increasing" }
    }

    private fun accept(now: Long, frame: PoseFrame?): RecognitionResult {
        if (lastTimestamp?.let { now - it > config.maximumObservationGapMs } == true) {
            trackers.values.forEach { it.pause() }
            pending.forEach { it.observedSince = null }
            candidate = null
        }
        lastTimestamp = now
        // Feature-v1's 500ms reset is part of the training/runtime contract, independent of tracking settings.
        if (lastClassifierGood?.let { now - it > 500 } == true) {
            extractor.reset(); window?.reset(); lastClassifierGood = null; candidate = null
        }
        val classificationFeatures = if (classifier != null && frame != null) extractor.extract(frame) else null
        if (classificationFeatures != null) lastClassifierGood = now else extractor.reset()
        window?.add(now, classificationFeatures)

        val observed = mutableSetOf<String>()
        val airborneUpdates = mutableListOf<ProgressUpdate>()
        trackers.forEach { (id, tracker) ->
            if (lastGood[id]?.let { now - it > config.maximumPoseGapMs } == true) resetTracker(id)
            val locked = updates[id]?.startedAtMs != null || pending.any { it.key.exercise == id }
            val features = frame?.let { geometry.getValue(id).extract(it, locked) }
            if (features == null) {
                tracker.pause()
                pending.filter { it.key.exercise == id }.forEach { it.observedSince = null }
            } else {
                observed.add(id)
                lastGood[id] = now
                val update = tracker.update(features)
                updates[id] = update
                if (update.airborne) airborneUpdates.add(update)
                if (update.completed && update.startedAtMs != null) {
                    val key = RepKey(id, update.startedAtMs)
                    if (key.start > lastCountedEnd && pending.none { it.key == key }) pending.add(Completion(key, now, now))
                }
            }
        }
        // Only cancel the squat belonging to this jump, not an earlier completed repetition.
        val latestSquatEnd = pending.filter { it.key.exercise == Exercises.SQUAT }.maxOfOrNull { it.end }
        pending.removeAll { p -> p.key.exercise == Exercises.SQUAT && airborneUpdates.any {
            (it.startedAtMs != null && it.startedAtMs <= p.end) ||
                (it.startedAtMs == null && p.end == latestSquatEnd)
        } }
        pending.removeAll { now - it.end > config.completionTimeoutMs || it.key.start <= lastCountedEnd }

        var confidence = 0f
        var label: String? = null
        var status = RecognitionStatus.POOR_POSE
        if (mode is RecognitionMode.Guided) {
            if (mode.exerciseId in observed) { label = mode.exerciseId; status = RecognitionStatus.TRACKING }
        } else if (classificationFeatures != null) {
            val model = requireNotNull(manifest)
            val snapshot = requireNotNull(window).snapshot()
            if (snapshot.validSteps < model.minValidSteps) {
                status = RecognitionStatus.WARMING_UP
            } else {
                val scores = requireNotNull(classifier).classify(snapshot)
                require(scores.size == model.classes.size && scores.all { it.isFinite() && it in 0f..1f } &&
                    kotlin.math.abs(scores.sum() - 1f) <= 0.02f) { "Classifier must return finite softmax scores in class order" }
                val ranking = scores.indices.sortedByDescending { scores[it] }
                confidence = scores[ranking[0]]
                val best = model.classes[ranking[0]]
                status = RecognitionStatus.UNCERTAIN
                if (confidence >= model.confidenceThreshold && confidence - scores[ranking[1]] >= model.marginThreshold) {
                    if (candidate != best) { candidate = best; candidateSince = now }
                    if (now - candidateSince >= model.stableDurationMs) {
                        if (best == Exercises.OTHER) status = RecognitionStatus.OTHER
                        else { label = best; status = if (best in observed) RecognitionStatus.TRACKING else RecognitionStatus.POOR_POSE }
                    }
                } else candidate = null
            }
        } else candidate = null

        // Keep classification evidence on a repetition, even after the live label returns to OTHER.
        if (label != null && label in observed) {
            val activeStart = updates[label]?.startedAtMs
            if (activeStart != null) evidence[RepKey(label, activeStart)] = Evidence(confidence)
            pending.filter { it.key.exercise == label && (activeStart == null || activeStart == it.key.start) }
                .forEach { evidence[it.key] = Evidence(confidence) }
        }
        val events = mutableListOf<RepEvent>()
        for (completion in pending.sortedWith(compareBy<Completion> { it.end }.thenByDescending { evidence[it.key]?.confidence ?: -1f })) {
            if (completion.key.start <= lastCountedEnd || completion.key.exercise !in observed) continue
            if (completion.observedSince == null) completion.observedSince = now
            if (now - completion.observedSince!! < config.forExercise(completion.key.exercise).completionDelayMs) continue
            val support = evidence[completion.key]
            if (source == RecognitionSource.MODEL && support == null) continue
            total++
            counts[completion.key.exercise] = (counts[completion.key.exercise] ?: 0) + 1
            lastCountedEnd = completion.end
            events.add(RepEvent(nextRepId++, completion.key.exercise, completion.key.start, completion.end, now,
                if (source == RecognitionSource.MODEL) support?.confidence else null, source))
        }
        pending.removeAll { it.key.start <= lastCountedEnd }
        evidence.keys.removeAll { key -> pending.none { it.key == key } && updates[key.exercise]?.startedAtMs != key.start }
        val update = label?.takeIf { it in observed }?.let(updates::get)
        val current = TrackingState(label, confidence, update?.progress?.coerceAtMost(0.99f), update?.phase, status, source)
        val missing = frame?.let { f -> FeatureExtractor.requiredJoints.filter { minOf(f.landmarks[it].visibility, f.worldLandmarks[it].visibility) < 0.5f } } ?: emptyList()
        val quality = PoseQuality(observed.isNotEmpty(), classificationFeatures != null, missing,
            when { frame == null -> PoseIssue.NO_POSE
                observed.isEmpty() -> geometry.values.firstNotNullOfOrNull { it.issue } ?: PoseIssue.DEGENERATE_GEOMETRY
                classificationFeatures == null && classifier != null -> if (missing.isNotEmpty()) PoseIssue.LOW_VISIBILITY else PoseIssue.DEGENERATE_GEOMETRY
                else -> null })
        // Preserve the original event-frame fields for existing consumers. New consumers use current + events.
        val event = events.firstOrNull()
        return RecognitionResult(now, event?.exerciseId ?: current.exerciseId, event?.confidence ?: current.confidence,
            if (event != null) 1f else current.progress, if (event != null) "complete" else current.phase,
            if (event != null) RecognitionStatus.TRACKING else current.status, total, events.isNotEmpty(), current,
            events.toList(), counts.toMap(), quality)
    }

    private fun resetTracker(id: String) {
        trackers.getValue(id).reset(); geometry.getValue(id).reset()
        updates.remove(id); lastGood.remove(id)
        pending.removeAll { it.key.exercise == id }; evidence.keys.removeAll { it.exercise == id }
    }

    private fun clearTracking() {
        trackers.keys.forEach(::resetTracker)
        extractor.reset(); window?.reset(); candidate = null; lastClassifierGood = null
    }

    fun reset() {
        check(!closed) { "Engine is closed" }
        clearTracking(); lastTimestamp = null; total = 0; nextRepId = 1; lastCountedEnd = -1; counts.clear()
    }

    fun close() {
        if (!closed) { closed = true; clearTracking(); classifier?.close() }
    }
}
