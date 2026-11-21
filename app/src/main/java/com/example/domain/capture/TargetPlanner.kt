package com.example.domain.capture

import com.example.domain.math.SphereMath
import com.example.domain.math.Vec3
import kotlin.math.ceil
import kotlin.math.cos

enum class RingType(val exportCode: String, val displayLabel: String) {
    EYE_LEVEL("eye", "Eye Level"),
    UPPER("upper", "Upper Ring"),
    LOWER("lower", "Lower Ring"),
    CEILING("ceiling", "Ceiling")
}

data class PlannedTarget(
    val id: String,
    val orderIndex: Int,
    val ringType: RingType,
    val targetYawDeg: Float,
    val targetPitchDeg: Float,
    val targetDir: Vec3
)

/**
 * Computes sphere target placements directly from the active camera's portrait field of view
 * so neighbouring photos overlap by ~25-30% without hardcoding target angles.
 */
object TargetPlanner {

    const val DEFAULT_TARGET_OVERLAP = 0.275f // 27.5% midpoint of 25-30%
    const val DEFAULT_PORTRAIT_HFOV_DEG = 54.0f
    const val DEFAULT_PORTRAIT_VFOV_DEG = 68.0f

    fun computeCoveragePlan(
        hfovDeg: Float,
        vfovDeg: Float,
        targetOverlap: Float = DEFAULT_TARGET_OVERLAP,
        includeCeiling: Boolean = true
    ): List<PlannedTarget> {
        val safeHfov = hfovDeg.coerceIn(25f, 120f)
        val safeVfov = vfovDeg.coerceIn(30f, 130f)
        val safeOverlap = targetOverlap.coerceIn(0.15f, 0.50f)

        // Vertical step between rings so adjacent rings overlap by targetOverlap
        val verticalStepDeg = (safeVfov * (1f - safeOverlap)).coerceIn(25f, 65f)
        val upperPitchDeg = verticalStepDeg.coerceAtMost(62f)
        // Lower ring keeps the area directly beneath the user's feet uncaptured
        val lowerPitchDeg = (-verticalStepDeg).coerceAtLeast(-62f)

        val targets = mutableListOf<PlannedTarget>()
        var orderIndex = 0

        // 1. Eye-level ring (pitch = 0°)
        val eyeCount = azimuthCountForRing(0f, safeHfov, safeOverlap)
        val eyeStep = 360f / eyeCount
        for (i in 0 until eyeCount) {
            val yaw = SphereMath.normalizeYawDeg(i * eyeStep)
            targets += PlannedTarget(
                id = "eye_${i}",
                orderIndex = orderIndex++,
                ringType = RingType.EYE_LEVEL,
                targetYawDeg = yaw,
                targetPitchDeg = 0f,
                targetDir = SphereMath.directionFromYawPitchDeg(yaw, 0f)
            )
        }

        // 2. Upper ring (staggered by half an azimuth step for stronger cross-ring seam overlap)
        val upperCount = azimuthCountForRing(upperPitchDeg, safeHfov, safeOverlap)
        val upperStep = 360f / upperCount
        val upperOffset = upperStep * 0.5f
        for (i in 0 until upperCount) {
            val yaw = SphereMath.normalizeYawDeg(upperOffset + i * upperStep)
            targets += PlannedTarget(
                id = "upper_${i}",
                orderIndex = orderIndex++,
                ringType = RingType.UPPER,
                targetYawDeg = yaw,
                targetPitchDeg = upperPitchDeg,
                targetDir = SphereMath.directionFromYawPitchDeg(yaw, upperPitchDeg)
            )
        }

        // 3. Ceiling target (straight up, +90° pitch) — straightforward with quaternion math
        if (includeCeiling) {
            targets += PlannedTarget(
                id = "ceiling_0",
                orderIndex = orderIndex++,
                ringType = RingType.CEILING,
                targetYawDeg = 0f,
                targetPitchDeg = 90f,
                targetDir = Vec3(0f, 0f, 1f)
            )
        }

        // 4. Lower ring (staggered by half an azimuth step, intentionally leaving nadir floor open)
        val lowerCount = azimuthCountForRing(lowerPitchDeg, safeHfov, safeOverlap)
        val lowerStep = 360f / lowerCount
        val lowerOffset = lowerStep * 0.5f
        for (i in 0 until lowerCount) {
            val yaw = SphereMath.normalizeYawDeg(lowerOffset + i * lowerStep)
            targets += PlannedTarget(
                id = "lower_${i}",
                orderIndex = orderIndex++,
                ringType = RingType.LOWER,
                targetYawDeg = yaw,
                targetPitchDeg = lowerPitchDeg,
                targetDir = SphereMath.directionFromYawPitchDeg(yaw, lowerPitchDeg)
            )
        }

        return targets
    }

    /**
     * Computes number of azimuth targets around a ring at [pitchDeg] so that horizontal
     * arc-length step on the unit sphere gives at least [targetOverlap].
     */
    fun azimuthCountForRing(
        pitchDeg: Float,
        hfovDeg: Float,
        targetOverlap: Float
    ): Int {
        val pitchRad = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val ringCircumferenceFactor = cos(pitchRad).coerceAtLeast(0.25f)
        val desiredArcStepDeg = hfovDeg * (1f - targetOverlap)
        val rawCount = ceil((360f * ringCircumferenceFactor) / desiredArcStepDeg).toInt()
        return rawCount.coerceAtLeast(4)
    }

    /**
     * Computes horizontal overlap ratio between adjacent targets in a given ring.
     * Useful for verifying 25-30%+ overlap in unit tests.
     */
    fun horizontalOverlapRatioInRing(
        ringTargets: List<PlannedTarget>,
        hfovDeg: Float
    ): Float {
        if (ringTargets.size < 2) return 1f
        val angularSep = ringTargets[0].targetDir.angleDegreesTo(ringTargets[1].targetDir)
        return 1f - (angularSep / hfovDeg)
    }
}
