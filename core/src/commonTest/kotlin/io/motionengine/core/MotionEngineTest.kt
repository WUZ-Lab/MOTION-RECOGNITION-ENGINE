package io.motionengine.core

import kotlin.math.*
import kotlin.test.*

private class TestClassifier(var label: String = Exercises.SQUAT) : MotionClassifier {
    override val manifest = ModelManifest(sha256 = "0".repeat(64), minValidSteps = 1, stableDurationMs = 0)
    var closedCount = 0
    override fun classify(window: FeatureWindow) = FloatArray(manifest.classes.size) { if (manifest.classes[it] == label) 0.96f else 0.01f }
    override fun close() { closedCount++ }
}

internal fun pose(time: Long, kneeAngle: Float = 180f, screenShift: Float = 0f): PoseFrame {
    val bend = (180f - kneeAngle) * PI.toFloat() / 180
    val w = MutableList(33) { Landmark(0f, -0.7f, 0f) }
    for ((offset, side) in listOf(0 to -1f, 1 to 1f)) {
        w[11+offset] = Landmark(side*.18f, -.5f, 0f)
        w[13+offset] = Landmark(side*.22f, -.25f, .1f)
        w[15+offset] = Landmark(side*.25f, 0f, .05f)
        w[23+offset] = Landmark(side*.15f, 0f, 0f)
        w[25+offset] = Landmark(side*.15f, .4f, 0f)
        w[27+offset] = Landmark(side*.15f, .4f + .4f*cos(bend), .4f*sin(bend))
    }
    val foot = w[27].y
    val n = w.map { Landmark(.5f + it.x*.5f, .9f - (foot-it.y)*.5f + screenShift, it.z*.5f) }
    return PoseFrame(time, 480, 640, n, w)
}

internal fun measured(time: Long, knee: Float = 175f, elbow: Float = 175f, tilt: Float = 10f,
                      hipY: Float = .5f, footY: Float = .9f): PoseFeatures {
    val v = FloatArray(32)
    v[0] = knee/180; v[1] = knee/180; v[4] = elbow/180; v[5] = elbow/180
    v[6] = tilt/180; v[10] = hipY; v[11] = footY; v[12] = .25f
    return PoseFeatures(time, v)
}

class MotionEngineTest {
    @Test fun jumpCancelsPendingSquatAndCountsOnlyJump() {
        val classifier = TestClassifier()
        val engine = MotionEngine(classifier)
        val angles = listOf(180f,180f,180f,140f,100f,100f,130f,175f,175f,175f,180f,180f,145f) + List(15) { 180f }
        val results = angles.mapIndexed { i, angle ->
            if (i == 10) classifier.label = Exercises.JUMP_SQUAT
            engine.process(pose(i*100L,angle,if (i in 10..11) -.12f else 0f))
        }
        assertEquals(1, results.count { it.repCompleted })
        assertEquals(Exercises.JUMP_SQUAT, results.single { it.repCompleted }.exerciseId)
    }

    @Test fun classConfirmationUsesMovementObservedBeforeLabelSwitch() {
        val classifier = TestClassifier(Exercises.OTHER)
        val engine = MotionEngine(classifier)
        val angles = listOf(180f,180f,180f,140f,100f,100f,130f,175f,175f,175f) + List(10) { 180f }
        val results = angles.mapIndexed { i, angle ->
            if (i == 5) classifier.label = Exercises.SQUAT
            engine.process(pose(i*100L,angle))
        }
        assertEquals(1, results.count { it.repCompleted })
        engine.reset()
        assertEquals(0, engine.process(pose(0)).completedReps)
    }

    @Test fun confidenceAndProgressAreIndependentAndCompletionIsOnce() {
        val engine = MotionEngine(TestClassifier())
        val angles = listOf(180f,180f,180f,140f,100f,100f,130f,175f,175f,175f) + List(10) { 180f }
        val results = angles.mapIndexed { i, angle -> engine.process(pose(i*100L, angle)) }
        assertEquals(1, results.count { it.repCompleted })
        assertEquals(1, results.last().completedReps)
        assertTrue(results.any { (it.progress ?: -1f) in .45f..0.55f && it.confidence > .9f })
        assertEquals(1f, results.single { it.repCompleted }.progress)
        assertEquals(1, engine.process(pose(2100)).completedReps)
    }

    @Test fun holdingAndIncompleteReversalNeverCount() {
        val engine = MotionEngine(TestClassifier())
        val angles = List(4) { 180f } + listOf(140f,135f,150f,175f) + List(20) { 180f }
        assertTrue(angles.mapIndexed { i, angle -> engine.process(pose(i*100L, angle)) }.all { it.completedReps == 0 })
    }

    @Test fun invalidInputDoesNotConsumeTimestampOrState() {
        val engine = MotionEngine(TestClassifier())
        engine.process(pose(100))
        assertFailsWith<IllegalArgumentException> { engine.process(pose(200).copy(landmarks = emptyList())) }
        assertFailsWith<IllegalArgumentException> { engine.process(pose(100)) }
        val bad = pose(200).landmarks.toMutableList().apply { this[0] = this[0].copy(x = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { engine.process(pose(200).copy(landmarks = bad)) }
        assertEquals(200L, engine.process(pose(200)).timestampMs)
    }

    @Test fun trackingLossDiscardsPartialRepAndResetClearsCount() {
        val engine = MotionEngine(TestClassifier())
        listOf(180f,180f,180f,140f,100f).forEachIndexed { i, a -> engine.process(pose(i*100L,a)) }
        val missing = engine.processMissing(1000)
        assertEquals(RecognitionStatus.POOR_POSE, missing.status); assertNull(missing.progress)
        for (i in 11..30) assertEquals(0, engine.process(pose(i*100L)).completedReps)
        engine.reset()
        assertEquals(0, engine.process(pose(0)).completedReps)
    }

    @Test fun lifecycleAndModelCompatibility() {
        val classifier = TestClassifier(); val engine = MotionEngine(classifier)
        engine.close(); engine.close()
        assertEquals(1, classifier.closedCount)
        assertFailsWith<IllegalStateException> { engine.process(pose(0)) }
        assertFailsWith<IllegalStateException> { engine.reset() }
        assertFailsWith<IllegalArgumentException> { classifier.manifest.copy(featureVersion = 9).validate() }
        assertFailsWith<IllegalArgumentException> { classifier.manifest.copy(std = List(32) { 0f }).validate() }
    }

    @Test fun otherAndUncertainHaveNoProgress() {
        val engine = MotionEngine(TestClassifier(Exercises.OTHER))
        val result = engine.process(pose(0))
        assertEquals(RecognitionStatus.OTHER, result.status); assertNull(result.exerciseId); assertNull(result.progress)
        val classifier = object : MotionClassifier {
            override val manifest = ModelManifest(sha256 = "0".repeat(64), minValidSteps = 1)
            override fun classify(window: FeatureWindow) = FloatArray(5) { .2f }
        }
        assertEquals(RecognitionStatus.UNCERTAIN, MotionEngine(classifier).process(pose(0)).status)
    }
}

class ProgressTrackerTest {
    private fun tracker(id: String) = BuiltInExercises.definitions.single { it.id == id }.createTracker(ProgressConfig())

    @Test fun allFourExercisesCompleteTheirOrderedCycle() {
        val squat = tracker(Exercises.SQUAT)
        val squatFrames = listOf(175f,175f,175f,140f,100f,100f,130f,175f,175f,175f)
            .mapIndexed { i, a -> measured(i*100L,knee=a) }
        assertEquals(1, squatFrames.map(squat::update).count { it.completed })
        val push = tracker(Exercises.PUSH_UP)
        assertEquals(1, squatFrames.map { f -> push.update(measured(f.timestampMs, elbow=f.knee, tilt=90f)) }.count { it.completed })
        for (id in listOf(Exercises.JUMP_SQUAT, Exercises.BURPEE)) {
            val t = tracker(id)
            val frames = mutableListOf(measured(0), measured(100), measured(200), measured(300, knee=140f), measured(400,knee=100f, tilt=50f))
            if (id == Exercises.BURPEE) frames.addAll(listOf(measured(500,tilt=90f), measured(600,tilt=90f), measured(700,tilt=90f),
                measured(800,knee=90f,tilt=60f), measured(900,knee=100f,tilt=20f)))
            val at = frames.last().timestampMs
            frames.addAll(listOf(measured(at+100,knee=130f), measured(at+200,hipY=.4f,footY=.8f), measured(at+300,hipY=.4f,footY=.8f),
                measured(at+400,knee=145f), measured(at+500), measured(at+600), measured(at+700)))
            assertEquals(1, frames.map(t::update).count { it.completed }, id)
        }
    }

    @Test fun burpeePlankAloneAndJumpWithoutCrouchDoNotComplete() {
        val burpee = tracker(Exercises.BURPEE)
        assertTrue((0..30).map { burpee.update(measured(it*100L,tilt=90f)) }.none { it.completed })
        val jump = tracker(Exercises.JUMP_SQUAT)
        val frames = (0..20).map { measured(it*100L, hipY=if (it in 5..8) .4f else .5f, footY=if (it in 5..8) .8f else .9f) }
        assertTrue(frames.map(jump::update).none { it.completed })
    }

    @Test fun positionHoldDoesNotAdvanceProgress() {
        val t = tracker(Exercises.SQUAT)
        listOf(175f,175f,175f,140f,100f).forEachIndexed { i,a -> t.update(measured(i*100L,knee=a)) }
        val held = (5..20).map { t.update(measured(it*100L,knee=100f)) }
        assertTrue(held.all { it.progress == .5f && !it.completed })
    }
}

class FeaturesTest {
    @Test fun normalizedGeometryIgnoresWorldTranslationAndScale() {
        val source = pose(0, 110f)
        val scaled = source.copy(worldLandmarks = source.worldLandmarks.map { it.copy(x=it.x*2+3,y=it.y*2-5,z=it.z*2+7) })
        val a = FeatureExtractor().extract(source)!!; val b = FeatureExtractor().extract(scaled)!!
        a.values.zip(b.values).forEach { (x,y) -> assertEquals(x,y,0.001f) }
        assertEquals(110f,a.knee,0.01f)
    }

    @Test fun qualityLossResetsDerivative() {
        val extractor = FeatureExtractor()
        extractor.extract(pose(0))
        val low = pose(100).landmarks.mapIndexed { i,l -> if (i==25) l.copy(visibility=.1f) else l }
        assertNull(extractor.extract(pose(100).copy(landmarks=low)))
        assertTrue(extractor.extract(pose(200,100f))!!.values.drop(16).all { it==0f })
    }

    @Test fun causalWindowMasksGapsAndPadsLeft() {
        val window = TemporalWindow(ModelManifest(sha256="0".repeat(64)))
        window.add(0, measured(0))
        assertEquals(1,window.snapshot().validSteps)
        assertTrue(window.snapshot().data.take(179*33).all { it==0f })
        window.add(200, measured(200,knee=100f))
        val data = window.snapshot().data
        assertEquals(5,window.snapshot().validSteps) // 0,33,66,100,200
        assertEquals(0f,data[178*33+32]) // 166 ms is too old to hold
        assertEquals(100f/180,data[179*33],0.0001f)
        window.add(1_000_000,measured(1_000_000))
        assertEquals(180*33,window.snapshot().data.size)
    }
}
