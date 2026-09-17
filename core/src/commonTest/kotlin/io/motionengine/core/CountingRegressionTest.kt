package io.motionengine.core

import kotlin.test.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private class ControlledClassifier : MotionClassifier {
    var label = Exercises.SQUAT
    override val manifest = ModelManifest(sha256 = "0".repeat(64))
    override fun classify(window: FeatureWindow) = FloatArray(manifest.classes.size) {
        if (manifest.classes[it] == label) .96f else .01f
    }
}

class CountingRegressionTest {
    private val cycle = listOf(180f,180f,180f,140f,100f,100f,130f,175f,175f,175f)
    private fun frame(i: Int) = pose(i * 100L, cycle.getOrElse(i) { 180f })

    @Test fun completedRepSurvivesCorrectReturnToOther() {
        val classifier = ControlledClassifier()
        val engine = MotionEngine(classifier)
        val results = (0..25).map { i ->
            if (i >= 10) classifier.label = Exercises.OTHER
            engine.process(frame(i))
        }
        val emitted = results.single { it.repCompleted }
        assertEquals(1, emitted.completedReps)
        assertEquals(RecognitionStatus.OTHER, emitted.current.status)
        assertNull(emitted.current.exerciseId)
        assertEquals(Exercises.SQUAT, emitted.exerciseId) // legacy completion projection
        val event = emitted.events.single()
        assertEquals(300L, event.startedAtMs)
        assertEquals(900L, event.endedAtMs)
        assertEquals(1400L, event.emittedAtMs)
        assertEquals(RecognitionSource.MODEL, event.source)
        assertEquals(mapOf(Exercises.SQUAT to 1), emitted.repsByExercise)
        assertEquals(emitted, MotionJson.format.decodeFromString<RecognitionResult>(MotionJson.format.encodeToString(emitted)))
    }

    @Test fun shortMissingPoseDefersAlreadyObservedCompletion() {
        val engine = MotionEngine(ControlledClassifier())
        val results = (0..25).map { if (it == 10) engine.processMissing(1000) else engine.process(frame(it)) }
        assertFalse(results[10].repCompleted)
        assertEquals(PoseIssue.NO_POSE, results[10].quality.issue)
        assertEquals(1600L, results.flatMap { it.events }.single().emittedAtMs)
    }

    @Test fun longLossDiscardsCompletionAndUnobservedBottomIsNotInvented() {
        for (missing in listOf(10..16, 4..5)) {
            val engine = MotionEngine(ControlledClassifier())
            val results = (0..30).map { if (it in missing) engine.processMissing(it * 100L) else engine.process(frame(it)) }
            assertEquals(0, results.last().completedReps)
        }
    }

    @Test fun geometricCycleWithoutClassificationEvidenceDoesNotCountInAuto() {
        val classifier = ControlledClassifier().apply { label = Exercises.OTHER }
        val engine = MotionEngine(classifier)
        assertTrue((0..30).map { engine.process(frame(it)) }.all { it.events.isEmpty() })
    }

    @Test fun consecutiveRepsNeedNoSecondTopHoldAndHaveIndependentEvents() {
        val engine = MotionEngine(mode = RecognitionMode.Guided(Exercises.SQUAT))
        val angles = cycle + cycle.drop(3) + List(10) { 180f }
        val results = angles.mapIndexed { i, angle -> engine.process(pose(i * 100L, angle)) }
        val events = results.flatMap { it.events }
        assertEquals(2, events.size)
        assertEquals(listOf(1L, 2L), events.map { it.repId })
        assertEquals(listOf(900L, 1600L), events.map { it.endedAtMs })
        assertTrue(events.all { it.confidence == null && it.source == RecognitionSource.GUIDED })
        val first = results.first { it.repCompleted }
        assertEquals("rising", first.current.phase)
        assertTrue(requireNotNull(first.current.progress) < 1f)
        assertEquals(1f, first.progress)
        engine.reset()
        assertEquals(0, engine.process(pose(0)).completedReps)
        assertTrue(engine.process(pose(100)).repsByExercise.isEmpty())
    }

    @Test fun guidedSquatWorksWithOccludedArmsAndOneVisibleLeg() {
        val engine = MotionEngine(mode = RecognitionMode.Guided(Exercises.SQUAT))
        val results = (0..25).map { i ->
            val f = frame(i)
            val hidden = setOf(12, 13, 14, 15, 16, 24, 26, 28)
            engine.process(f.copy(landmarks = f.landmarks.mapIndexed { joint, l -> if (joint in hidden) l.copy(visibility = .1f) else l }))
        }
        assertEquals(1, results.last().completedReps)
        assertTrue(results.all { it.quality.usableForTracking })
    }

    @Test fun autoCanFinishSupportedSquatWithOccludedWristWithoutChangingModelFeatures() {
        val engine = MotionEngine(ControlledClassifier())
        val results = (0..25).map { i ->
            val f = frame(i)
            engine.process(if (i >= 8) f.copy(landmarks = f.landmarks.mapIndexed { joint, l -> if (joint == 15) l.copy(visibility = .1f) else l }) else f)
        }
        assertEquals(1, results.last().completedReps)
        assertFalse(results.last().quality.usableForClassification)
        assertTrue(results.last().quality.usableForTracking)
    }

    @Test fun guidedSquatStillRejectsJumpAndPartialCycles() {
        for (jump in listOf(false, true)) {
            val engine = MotionEngine(mode = RecognitionMode.Guided(Exercises.SQUAT))
            val angles = if (jump) cycle else listOf(180f,180f,180f,140f,150f,155f,162f) + List(3) { 180f }
            val results = (0..30).map { i -> engine.process(pose(i*100L, angles.getOrElse(i) { 180f }, if (jump && i in 10..11) -.12f else 0f)) }
            assertEquals(0, results.last().completedReps)
        }
    }

    @Test fun perExerciseConfigurationAndModeValidation() {
        assertFailsWith<IllegalArgumentException> { MotionEngine() }
        assertFailsWith<IllegalArgumentException> { MotionEngine(mode = RecognitionMode.Guided(Exercises.OTHER)) }
        assertFailsWith<IllegalArgumentException> { MotionEngine(ControlledClassifier(), mode = RecognitionMode.Guided(Exercises.SQUAT)) }
        val engine = MotionEngine(mode = RecognitionMode.Guided(Exercises.SQUAT), config = TrackingConfig(
            exerciseProgress = mapOf(Exercises.SQUAT to ProgressConfig(completionDelayMs = 0))))
        val results = (0..25).map { engine.process(frame(it)) }
        assertEquals(900L, results.flatMap { it.events }.single().emittedAtMs)
    }

    @Test fun jumpInNextRepDoesNotCancelPreviousDelayedSquat() {
        for (guided in listOf(false, true)) {
            val classifier = ControlledClassifier()
            val engine = MotionEngine(if (guided) null else classifier,
                mode = if (guided) RecognitionMode.Guided(Exercises.SQUAT) else RecognitionMode.Auto,
                config = TrackingConfig(exerciseProgress = mapOf(Exercises.SQUAT to ProgressConfig(completionDelayMs = 1500))))
            val angles = cycle + cycle.drop(3) + listOf(180f,180f,145f) + List(20) { 180f }
            val events = angles.flatMapIndexed { i, angle ->
                if (i >= 17) classifier.label = Exercises.JUMP_SQUAT
                engine.process(pose(i*100L, angle, if (i in 17..18) -.12f else 0f)).events
            }
            assertEquals(1, events.count { it.exerciseId == Exercises.SQUAT })
            assertEquals(900L, events.first { it.exerciseId == Exercises.SQUAT }.endedAtMs)
            assertEquals(if (guided) 0 else 1, events.count { it.exerciseId == Exercises.JUMP_SQUAT })
        }
    }

    @Test fun queuedCompletionsCanBeEmittedTogetherAfterShortLoss() {
        val engine = MotionEngine(mode = RecognitionMode.Guided(Exercises.SQUAT),
            config = TrackingConfig(progress = ProgressConfig(completionDelayMs = 1500)))
        val angles = cycle + cycle.drop(3) + List(25) { 180f }
        val results = angles.mapIndexed { i, angle ->
            if (i in 17..19) engine.processMissing(i*100L) else engine.process(pose(i*100L, angle))
        }
        val emitted = results.single { it.events.isNotEmpty() }
        assertEquals(2, emitted.events.size)
        assertEquals(2, emitted.repsByExercise[Exercises.SQUAT])
        assertEquals(2, results.last().completedReps)
    }
}
