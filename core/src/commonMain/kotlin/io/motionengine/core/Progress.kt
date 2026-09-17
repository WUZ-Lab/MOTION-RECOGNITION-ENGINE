package io.motionengine.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/** Initial geometry thresholds, NOT validated exercise-quality standards. Tune with real data. */
@Serializable
data class ProgressConfig(
    val straightAngle: Float = 160f,
    val bentKneeAngle: Float = 105f,
    val bentElbowAngle: Float = 100f,
    val uprightTilt: Float = 45f,
    val plankTilt: Float = 65f,
    val jumpHeightInTorso: Float = 0.12f,
    val stablePoseMs: Long = 150,
    val minimumRepMs: Long = 600,
    val maximumRepMs: Long = 15000,
    val completionDelayMs: Long = 500,
) {
    fun validate() {
        require(straightAngle in 140f..180f && bentKneeAngle in 60f..130f && bentElbowAngle in 60f..130f)
        require(uprightTilt in 10f..60f && plankTilt > uprightTilt && plankTilt < 100)
        require(jumpHeightInTorso.isFinite() && jumpHeightInTorso in 0.03f..1f)
        require(stablePoseMs in 0..1000 && minimumRepMs in 100..3000)
        require(maximumRepMs > minimumRepMs && maximumRepMs <= 60000 && completionDelayMs in 0..1500)
    }
}

data class ProgressUpdate(
    val progress: Float? = null,
    val phase: String? = null,
    val startedAtMs: Long? = null,
    val completed: Boolean = false,
    val airborne: Boolean = false,
)

/** Implement this interface and register an ExerciseDefinition to add an exercise's phases. */
interface ProgressTracker {
    fun update(features: PoseFeatures): ProgressUpdate
    fun reset()
    /** Brief quality loss must break pose-stability timers without advancing a cycle. */
    fun pause() { reset() }
}

data class ExerciseDefinition(val id: String, val createTracker: (ProgressConfig) -> ProgressTracker)

object BuiltInExercises {
    val definitions: List<ExerciseDefinition> = listOf(
        ExerciseDefinition(Exercises.SQUAT) { SquatTracker(it) },
        ExerciseDefinition(Exercises.JUMP_SQUAT) { JumpSquatTracker(it) },
        ExerciseDefinition(Exercises.PUSH_UP) { PushUpTracker(it) },
        ExerciseDefinition(Exercises.BURPEE) { BurpeeTracker(it) },
    )
}

private abstract class CycleTracker(protected val exercise: String, protected val c: ProgressConfig) : ProgressTracker {
    protected var state = "seeking_start"
    protected var start: Long? = null
    private var stableSince: Long? = null
    private var lastProgress = 0f
    private var baseFoot = 0f
    private var baseHip = 0f
    private var baseScale = 1f

    override fun pause() { stableSince = null }

    override fun reset() {
        state = "seeking_start"; start = null; stableSince = null; lastProgress = 0f
        baseFoot = 0f; baseHip = 0f; baseScale = 1f
    }

    protected fun stable(condition: Boolean, now: Long): Boolean {
        if (!condition) { stableSince = null; return false }
        if (stableSince == null) stableSince = now
        return now - stableSince!! >= c.stablePoseMs
    }

    protected fun transition(next: String) { state = next; stableSince = null }
    protected fun output(value: Float? = null, airborne: Boolean = false): ProgressUpdate {
        if (value != null) lastProgress = max(lastProgress, value.coerceIn(0f, 1f))
        return ProgressUpdate(if (start == null) null else lastProgress, state, start, airborne = airborne)
    }

    override fun update(features: PoseFeatures): ProgressUpdate {
        val now = features.timestampMs
        if (start != null && now - start!! > c.maximumRepMs) reset()
        val push = exercise == Exercises.PUSH_UP
        val angle = if (push) features.elbow else features.knee
        val bottom = if (push) c.bentElbowAngle else c.bentKneeAngle
        val plank = features.torsoTilt >= c.plankTilt && features.knee >= c.straightAngle - 10
        val upright = features.torsoTilt < c.uprightTilt
        val ready = if (push) plank && angle >= c.straightAngle else upright && angle >= c.straightAngle
        val airborne = (baseFoot - features.footY) / baseScale > c.jumpHeightInTorso &&
            (baseHip - features.hipY) / baseScale > c.jumpHeightInTorso
        val grounded = abs(features.footY - baseFoot) / baseScale < c.jumpHeightInTorso * 0.75f
        val depth = ((c.straightAngle - angle) / (c.straightAngle - bottom)).coerceIn(0f, 1f)
        val up = 1f - depth
        if (state == "seeking_start") {
            if (stable(ready, now)) {
                transition("ready"); start = null; lastProgress = 0f
                baseFoot = features.footY; baseHip = features.hipY; baseScale = features.imageScale
            }
            return output(airborne = airborne)
        }
        if (state == "ready") {
            if (ready) {
                baseFoot = features.footY; baseHip = features.hipY; baseScale = features.imageScale
            }
            val begins = angle < c.straightAngle - 8 || (exercise == Exercises.BURPEE && !upright)
            if (begins) { start = now; transition("lowering") }
            return output(if (start != null) 0f else null, airborne)
        }
        advance(CyclePose(features, angle, bottom, plank, upright, ready, airborne, depth, up))?.let { return it }
        if (state == "airborne") {
            if (grounded) transition("landing")
            return output(if (exercise == Exercises.BURPEE) 0.8f else 0.7f, airborne)
        }
        if (state == "landing") {
            if (stable(ready && grounded, now)) return complete(now)
            return output(if (exercise == Exercises.BURPEE) 0.8f + 0.19f * up else 0.7f + 0.29f * up)
        }
        return output(airborne = airborne)
    }

    protected abstract fun advance(p: CyclePose): ProgressUpdate?

    protected fun begin(now: Long) { start = now; lastProgress = 0f; transition("lowering") }

    protected fun complete(now: Long): ProgressUpdate {
        if (start == null || now - start!! < c.minimumRepMs) { reset(); return output() }
        val completedStart = start
        // The top/landing stability was already observed. Reuse it for the next cycle.
        transition("ready"); start = null; lastProgress = 0f
        return ProgressUpdate(1f, "complete", completedStart, completed = true)
    }
}

private data class CyclePose(
    val features: PoseFeatures, val angle: Float, val bottom: Float, val plank: Boolean,
    val upright: Boolean, val ready: Boolean, val airborne: Boolean, val depth: Float, val up: Float,
)

/** Squat, jump squat and push-up share a bend/extend cycle, with explicit variants. */
private open class BendTracker(exercise: String, c: ProgressConfig) : CycleTracker(exercise, c) {
    private var returnedToTop = false
    override fun reset() { super.reset(); returnedToTop = false }

    override fun advance(p: CyclePose): ProgressUpdate? {
        val jump = exercise == Exercises.JUMP_SQUAT
        return when (state) {
            "lowering" -> {
                returnedToTop = false
                if (p.angle <= p.bottom + 0.001f) transition("bottom")
                else if (p.ready) { reset(); return output() }
                output(p.depth * if (jump) 0.4f else 0.5f)
            }
            "bottom" -> {
                if (p.angle > p.bottom + 5) transition("rising")
                output(if (jump) 0.4f else 0.5f)
            }
            "rising" -> {
                if (jump) {
                    if (p.airborne) transition("airborne")
                    else if (returnedToTop && p.angle < c.straightAngle - 8) {
                        // A non-jumping cycle ended; associate a later jump with its own descent.
                        begin(p.features.timestampMs); returnedToTop = false
                        return output(0f)
                    } else if (p.ready) returnedToTop = true
                    output(0.4f + 0.29f * p.up, p.airborne)
                } else {
                    if (exercise == Exercises.SQUAT && p.airborne) {
                        val rejectedStart = start
                        reset()
                        return ProgressUpdate(startedAtMs = rejectedStart, airborne = true)
                    }
                    if (stable(p.ready, p.features.timestampMs)) complete(p.features.timestampMs)
                    else output(0.5f + 0.49f * p.up)
                }
            }
            else -> null
        }
    }
}

private class SquatTracker(c: ProgressConfig) : BendTracker(Exercises.SQUAT, c)
private class JumpSquatTracker(c: ProgressConfig) : BendTracker(Exercises.JUMP_SQUAT, c)
private class PushUpTracker(c: ProgressConfig) : BendTracker(Exercises.PUSH_UP, c)

private class BurpeeTracker(c: ProgressConfig) : CycleTracker(Exercises.BURPEE, c) {
    override fun advance(p: CyclePose): ProgressUpdate? = when (state) {
        "lowering" -> {
            if (p.ready) reset()
            else if (!p.upright) transition("extending")
            output(0.2f * p.depth)
        }
        "extending" -> {
            if (stable(p.plank, p.features.timestampMs)) transition("plank")
            output(if (p.plank) 0.4f else 0.2f)
        }
        "plank" -> {
            if (!p.plank && p.features.knee < c.straightAngle - 15) transition("tucking")
            output(0.4f)
        }
        "tucking" -> {
            if (p.upright) transition("rising")
            output(0.4f + 0.2f * (1 - (p.features.torsoTilt / 90f).coerceIn(0f, 1f)))
        }
        "rising" -> {
            if (p.airborne) transition("airborne")
            output(0.6f + 0.19f * p.up, p.airborne)
        }
        else -> null
    }
}
