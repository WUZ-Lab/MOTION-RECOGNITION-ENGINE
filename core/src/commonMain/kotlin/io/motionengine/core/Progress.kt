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
        ExerciseDefinition(Exercises.SQUAT) { CycleTracker(Exercises.SQUAT, it) },
        ExerciseDefinition(Exercises.JUMP_SQUAT) { CycleTracker(Exercises.JUMP_SQUAT, it) },
        ExerciseDefinition(Exercises.PUSH_UP) { CycleTracker(Exercises.PUSH_UP, it) },
        ExerciseDefinition(Exercises.BURPEE) { CycleTracker(Exercises.BURPEE, it) },
    )
}

private class CycleTracker(private val exercise: String, private val c: ProgressConfig) : ProgressTracker {
    private var state = "seeking_start"
    private var start: Long? = null
    private var stableSince: Long? = null
    private var lastProgress = 0f
    private var baseFoot = 0f
    private var baseHip = 0f
    private var baseScale = 1f
    private var lastAngle = 180f

    override fun pause() { stableSince = null }

    override fun reset() {
        state = "seeking_start"; start = null; stableSince = null; lastProgress = 0f
        baseFoot = 0f; baseHip = 0f; baseScale = 1f; lastAngle = 180f
    }

    private fun stable(condition: Boolean, now: Long): Boolean {
        if (!condition) { stableSince = null; return false }
        if (stableSince == null) stableSince = now
        return now - stableSince!! >= c.stablePoseMs
    }

    private fun transition(next: String) { state = next; stableSince = null }
    private fun output(value: Float? = null, airborne: Boolean = false): ProgressUpdate {
        if (value != null) lastProgress = max(lastProgress, value.coerceIn(0f, 1f))
        return ProgressUpdate(if (start == null && state != "complete") null else lastProgress, state, start, airborne = airborne)
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
        val priorAngle = lastAngle
        lastAngle = angle

        if (state == "seeking_start" || state == "complete") {
            if (stable(ready && (state != "complete" || grounded), now)) {
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
            return output(if (start != null) 0f else null)
        }
        if (exercise == Exercises.BURPEE) {
            when (state) {
                "lowering" -> {
                    if (features.torsoTilt > c.uprightTilt) transition("extending")
                    return output(0.2f * depth)
                }
                "extending" -> {
                    if (stable(plank, now)) transition("plank")
                    return output(if (plank) 0.4f else 0.2f)
                }
                "plank" -> {
                    if (!plank && features.knee < c.straightAngle - 15) transition("tucking")
                    return output(0.4f)
                }
                "tucking" -> {
                    if (upright) transition("rising")
                    return output(0.4f + 0.2f * (1 - (features.torsoTilt / 90f).coerceIn(0f,1f)))
                }
                "rising" -> {
                    if (airborne) transition("airborne")
                    return output(0.6f + 0.19f * up, airborne)
                }
            }
        } else {
            when (state) {
                "lowering" -> {
                    if (angle <= bottom + 0.001f) transition("bottom")
                    else if (ready && priorAngle < c.straightAngle - 8) { reset(); return output() }
                    return output(depth * if (exercise == Exercises.JUMP_SQUAT) 0.4f else 0.5f)
                }
                "bottom" -> {
                    if (angle > bottom + 5) transition("rising")
                    return output(if (exercise == Exercises.JUMP_SQUAT) 0.4f else 0.5f)
                }
                "rising" -> {
                    if (exercise == Exercises.JUMP_SQUAT) {
                        if (airborne) transition("airborne")
                        return output(0.4f + 0.29f * up, airborne)
                    }
                    if (!push && airborne) { reset(); return ProgressUpdate(airborne = true) }
                    if (stable(ready, now)) return complete(now)
                    return output(0.5f + 0.49f * up)
                }
            }
        }
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

    private fun complete(now: Long): ProgressUpdate {
        if (start == null || now - start!! < c.minimumRepMs) { reset(); return output() }
        transition("complete"); lastProgress = 1f
        return ProgressUpdate(1f, "complete", start, completed = true)
    }
}
