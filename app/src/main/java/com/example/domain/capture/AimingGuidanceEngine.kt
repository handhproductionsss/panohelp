package com.example.domain.capture

import com.example.domain.math.CameraPose
import com.example.domain.math.Vec3
import kotlin.math.abs

enum class TiltStatus {
    LEVEL,
    TILTED_LEFT,
    TILTED_RIGHT
}

data class CaptureTargetState(
    val id: String,
    val orderIndex: Int,
    val ringType: RingType,
    val targetYawDeg: Float,
    val targetPitchDeg: Float,
    val targetDir: Vec3,
    val captured: Boolean,
    val photoPath: String? = null,
    val captureOrder: Int? = null
)

data class AimingConfig(
    val aimToleranceDeg: Float = 6.0f,
    val maxAutoRollDeg: Float = 10.0f,
    val tiltWarningThresholdDeg: Float = 8.0f,
    val zenithIgnoreRollPitchDeg: Float = 75.0f,
    val autoHoldMaxSpeedDegPerSec: Float = 4.0f,
    val fastMotionLockSpeedDegPerSec: Float = 10.0f,
    val autoHoldDurationMs: Long = 650L
)

data class AimingEvaluation(
    val activeTarget: CaptureTargetState?,
    val angularDistanceToTargetDeg: Float?,
    val isAimedAtTarget: Boolean,
    val tiltStatus: TiltStatus,
    val isMovingTooFast: Boolean,
    val canManualCapture: Boolean,
    val autoHoldProgress: Float,
    val shouldTriggerAutoCapture: Boolean,
    val allTargetsCaptured: Boolean,
    val isRetakeMode: Boolean
)

/**
 * Pure-Kotlin aiming state machine evaluating live sensor poses against the session's targets.
 * Handles nearest-target selection, retake targeting, tilt warnings, fast-motion blur blocking,
 * and auto-capture hold-steady timing.
 */
class AimingGuidanceEngine(
    private val config: AimingConfig = AimingConfig()
) {
    private var holdTargetId: String? = null
    private var holdAccumulatedMs: Long = 0L
    private var lastUpdateTimestampMs: Long? = null
    private var triggeredForCurrentHold: Boolean = false

    fun resetHold() {
        holdTargetId = null
        holdAccumulatedMs = 0L
        lastUpdateTimestampMs = null
        triggeredForCurrentHold = false
    }

    fun evaluate(
        pose: CameraPose,
        targets: List<CaptureTargetState>,
        lockedRetakeTargetId: String?,
        nowMs: Long,
        isCaptureInFlight: Boolean = false
    ): AimingEvaluation {
        val dtMs = lastUpdateTimestampMs?.let { (nowMs - it).coerceIn(0L, 250L) } ?: 0L
        lastUpdateTimestampMs = nowMs

        val allCaptured = targets.isNotEmpty() && targets.all { it.captured }
        val isRetake = lockedRetakeTargetId != null

        val activeTarget = if (lockedRetakeTargetId != null) {
            targets.firstOrNull { it.id == lockedRetakeTargetId }
        } else {
            // Find nearest uncaptured target by 3D angular distance
            targets
                .asSequence()
                .filter { !it.captured }
                .minByOrNull { pose.forward.angleDegreesTo(it.targetDir) }
        }

        val angularDistance = activeTarget?.let { pose.forward.angleDegreesTo(it.targetDir) }

        val ignoreRoll = abs(pose.pitchDeg) > config.zenithIgnoreRollPitchDeg
        val tiltStatus = when {
            ignoreRoll -> TiltStatus.LEVEL
            pose.rollDeg < -config.tiltWarningThresholdDeg -> TiltStatus.TILTED_LEFT
            pose.rollDeg > config.tiltWarningThresholdDeg -> TiltStatus.TILTED_RIGHT
            else -> TiltStatus.LEVEL
        }

        val isRollAcceptable = ignoreRoll || abs(pose.rollDeg) <= config.maxAutoRollDeg
        val isMovingTooFast = pose.angularVelocityDegPerSec > config.fastMotionLockSpeedDegPerSec
        val isSteadyForAuto = pose.angularVelocityDegPerSec <= config.autoHoldMaxSpeedDegPerSec

        val isAimed = activeTarget != null &&
            angularDistance != null &&
            angularDistance <= config.aimToleranceDeg

        // Manual capture is allowed as long as we have targets, aren't mid-save, and aren't moving fast
        val canManualCapture = targets.isNotEmpty() && !isCaptureInFlight && !isMovingTooFast

        val eligibleForHold = activeTarget != null &&
            (!activeTarget.captured || isRetake) &&
            !isCaptureInFlight &&
            isAimed &&
            isRollAcceptable &&
            isSteadyForAuto

        var shouldTriggerAuto = false

        if (eligibleForHold && activeTarget != null) {
            if (holdTargetId != activeTarget.id) {
                holdTargetId = activeTarget.id
                holdAccumulatedMs = 0L
                triggeredForCurrentHold = false
            }
            if (!triggeredForCurrentHold) {
                holdAccumulatedMs = (holdAccumulatedMs + dtMs).coerceAtMost(config.autoHoldDurationMs)
                if (holdAccumulatedMs >= config.autoHoldDurationMs) {
                    triggeredForCurrentHold = true
                    shouldTriggerAuto = true
                }
            }
        } else {
            // Decay or reset hold when off-target, tilted, or moving
            if (holdTargetId != activeTarget?.id || isMovingTooFast || !isAimed) {
                holdTargetId = activeTarget?.id
                holdAccumulatedMs = 0L
                triggeredForCurrentHold = false
            } else {
                // Slightly tilted or minor jitter: decay gently so brief micro-tremors don't feel harsh
                holdAccumulatedMs = (holdAccumulatedMs - dtMs * 2).coerceAtLeast(0L)
            }
        }

        val progress = if (config.autoHoldDurationMs > 0L) {
            (holdAccumulatedMs.toFloat() / config.autoHoldDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        return AimingEvaluation(
            activeTarget = activeTarget,
            angularDistanceToTargetDeg = angularDistance,
            isAimedAtTarget = isAimed,
            tiltStatus = tiltStatus,
            isMovingTooFast = isMovingTooFast,
            canManualCapture = canManualCapture,
            autoHoldProgress = progress,
            shouldTriggerAutoCapture = shouldTriggerAuto,
            allTargetsCaptured = allCaptured,
            isRetakeMode = isRetake
        )
    }
}
