package com.example.domain

import com.example.data.sensor.PoseHistoryBuffer
import com.example.domain.capture.AimingConfig
import com.example.domain.capture.AimingGuidanceEngine
import com.example.domain.capture.CaptureTargetState
import com.example.domain.capture.RingType
import com.example.domain.capture.TargetPlanner
import com.example.domain.capture.TiltStatus
import com.example.domain.math.CameraPose
import com.example.domain.math.Quaternion
import com.example.domain.math.SphereMath
import com.example.domain.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereMathAndPlannerTest {

    @Test
    fun `quaternion fromYawPitchRollDeg matches forward vector and remains valid at zenith`() {
        // Pointing North/Forward (+Y) at horizon
        val qForward = Quaternion.fromYawPitchRollDeg(yawDeg = 0f, pitchDeg = 0f, rollDeg = 0f)
        val poseForward = CameraPose.fromQuaternion(qForward)
        assertEquals(0f, poseForward.forward.x, 1e-3f)
        assertEquals(1f, poseForward.forward.y, 1e-3f)
        assertEquals(0f, poseForward.forward.z, 1e-3f)
        assertEquals(0f, poseForward.yawDeg, 0.5f)
        assertEquals(0f, poseForward.pitchDeg, 0.5f)
        assertEquals(0f, poseForward.rollDeg, 0.5f)

        // Pointing East (+X) at 90 deg yaw
        val qEast = Quaternion.fromYawPitchRollDeg(yawDeg = 90f, pitchDeg = 0f, rollDeg = 0f)
        val poseEast = CameraPose.fromQuaternion(qEast)
        assertEquals(1f, poseEast.forward.x, 1e-3f)
        assertEquals(0f, poseEast.forward.y, 1e-3f)
        assertEquals(90f, poseEast.yawDeg, 0.5f)

        // Pointing straight up at ceiling (+90 deg pitch) — singularity-free quaternion
        val qZenith = Quaternion.fromYawPitchRollDeg(yawDeg = 45f, pitchDeg = 90f, rollDeg = 0f)
        val poseZenith = CameraPose.fromQuaternion(qZenith)
        assertEquals(0f, poseZenith.forward.x, 1e-3f)
        assertEquals(0f, poseZenith.forward.y, 1e-3f)
        assertEquals(1f, poseZenith.forward.z, 1e-3f)
        assertEquals(90f, poseZenith.pitchDeg, 0.5f)
        val qNorm = poseZenith.quaternion.w * poseZenith.quaternion.w +
            poseZenith.quaternion.x * poseZenith.quaternion.x +
            poseZenith.quaternion.y * poseZenith.quaternion.y +
            poseZenith.quaternion.z * poseZenith.quaternion.z
        assertEquals(1f, qNorm, 1e-4f)
    }

    @Test
    fun `pose history buffer interpolates exposure timestamp via slerp`() {
        val buffer = PoseHistoryBuffer()
        val p1 = CameraPose.fromQuaternion(
            Quaternion.fromYawPitchRollDeg(0f, 0f, 0f),
            timestampNanos = 1_000_000_000L
        )
        val p2 = CameraPose.fromQuaternion(
            Quaternion.fromYawPitchRollDeg(60f, 0f, 0f),
            timestampNanos = 2_000_000_000L
        )
        buffer.add(p1)
        buffer.add(p2)

        val mid = buffer.poseAtOrNearest(1_500_000_000L)
        assertNotNull(mid)
        assertEquals(30f, mid!!.yawDeg, 1.0f)
    }

    @Test
    fun `target planner computes FOV-driven rings with at least 25 percent overlap and no nadir floor`() {
        val hfov = 54f
        val vfov = 68f
        val plan = TargetPlanner.computeCoveragePlan(hfovDeg = hfov, vfovDeg = vfov, includeCeiling = true)

        val eyeRing = plan.filter { it.ringType == RingType.EYE_LEVEL }
        val upperRing = plan.filter { it.ringType == RingType.UPPER }
        val lowerRing = plan.filter { it.ringType == RingType.LOWER }
        val ceiling = plan.filter { it.ringType == RingType.CEILING }

        assertTrue(eyeRing.size >= 8)
        assertTrue(upperRing.size >= 5)
        assertTrue(lowerRing.size >= 5)
        assertEquals(1, ceiling.size)
        assertEquals(90f, ceiling.first().targetPitchDeg, 0.01f)

        // Verify no straight-down floor target exists
        assertTrue(plan.none { it.targetPitchDeg < -70f })

        // Verify horizontal overlap in each ring meets >= 25% target
        assertTrue(TargetPlanner.horizontalOverlapRatioInRing(eyeRing, hfov) >= 0.25f)
        assertTrue(TargetPlanner.horizontalOverlapRatioInRing(upperRing, hfov) >= 0.25f)
        assertTrue(TargetPlanner.horizontalOverlapRatioInRing(lowerRing, hfov) >= 0.25f)

        // Verify narrower lens automatically produces more targets than wider lens
        val narrowPlan = TargetPlanner.computeCoveragePlan(hfovDeg = 40f, vfovDeg = 52f)
        val widePlan = TargetPlanner.computeCoveragePlan(hfovDeg = 72f, vfovDeg = 88f)
        assertTrue(narrowPlan.size > widePlan.size)
    }

    @Test
    fun `aiming guidance triggers auto capture after holding steady and blocks on fast motion or tilt`() {
        val plan = TargetPlanner.computeCoveragePlan(54f, 68f)
        val targets = plan.map {
            CaptureTargetState(
                id = it.id,
                orderIndex = it.orderIndex,
                ringType = it.ringType,
                targetYawDeg = it.targetYawDeg,
                targetPitchDeg = it.targetPitchDeg,
                targetDir = it.targetDir,
                captured = false
            )
        }
        val firstTarget = targets.first()
        val engine = AimingGuidanceEngine(
            AimingConfig(
                aimToleranceDeg = 6f,
                maxAutoRollDeg = 10f,
                tiltWarningThresholdDeg = 8f,
                autoHoldMaxSpeedDegPerSec = 4f,
                fastMotionLockSpeedDegPerSec = 10f,
                autoHoldDurationMs = 600L
            )
        )

        // 1. Fast motion locks both auto and manual capture
        val fastPose = CameraPose.fromQuaternion(
            Quaternion.fromYawPitchRollDeg(firstTarget.targetYawDeg, firstTarget.targetPitchDeg, 0f),
            angularVelocityDegPerSec = 25f
        )
        val fastEval = engine.evaluate(fastPose, targets, lockedRetakeTargetId = null, nowMs = 1000L)
        assertTrue(fastEval.isMovingTooFast)
        assertFalse(fastEval.canManualCapture)
        assertFalse(fastEval.shouldTriggerAutoCapture)

        // 2. Tilted right triggers tilt warning and prevents auto-capture
        val tiltedPose = CameraPose.fromQuaternion(
            Quaternion.fromYawPitchRollDeg(firstTarget.targetYawDeg, firstTarget.targetPitchDeg, rollDeg = 15f),
            angularVelocityDegPerSec = 0.5f
        )
        val tiltEval = engine.evaluate(tiltedPose, targets, lockedRetakeTargetId = null, nowMs = 1200L)
        assertEquals(TiltStatus.TILTED_RIGHT, tiltEval.tiltStatus)
        assertFalse(tiltEval.shouldTriggerAutoCapture)

        // 3. Level and steady on target accumulates hold progress and triggers at 600ms
        val steadyPose = CameraPose.fromQuaternion(
            Quaternion.fromYawPitchRollDeg(firstTarget.targetYawDeg, firstTarget.targetPitchDeg, rollDeg = 1f),
            angularVelocityDegPerSec = 0.5f
        )
        val e0 = engine.evaluate(steadyPose, targets, lockedRetakeTargetId = null, nowMs = 1300L)
        assertEquals(TiltStatus.LEVEL, e0.tiltStatus)
        assertTrue(e0.isAimedAtTarget)
        assertTrue(e0.canManualCapture)
        assertFalse(e0.shouldTriggerAutoCapture)

        val eMid = engine.evaluate(steadyPose, targets, lockedRetakeTargetId = null, nowMs = 1600L)
        assertTrue(eMid.autoHoldProgress in 0.4f..0.7f)
        assertFalse(eMid.shouldTriggerAutoCapture)

        val eDone = engine.evaluate(steadyPose, targets, lockedRetakeTargetId = null, nowMs = 1950L)
        assertEquals(1f, eDone.autoHoldProgress, 0.01f)
        assertTrue(eDone.shouldTriggerAutoCapture)
    }

    @Test
    fun `aiming guidance supports retake on already captured target and ignores roll at ceiling`() {
        val ceilingTarget = CaptureTargetState(
            id = "ceiling_0",
            orderIndex = 0,
            ringType = RingType.CEILING,
            targetYawDeg = 0f,
            targetPitchDeg = 90f,
            targetDir = Vec3(0f, 0f, 1f),
            captured = true
        )
        val eyeTarget = CaptureTargetState(
            id = "eye_0",
            orderIndex = 1,
            ringType = RingType.EYE_LEVEL,
            targetYawDeg = 0f,
            targetPitchDeg = 0f,
            targetDir = SphereMath.directionFromYawPitchDeg(0f, 0f),
            captured = false
        )

        val engine = AimingGuidanceEngine(AimingConfig(autoHoldDurationMs = 300L))
        val zenithPose = CameraPose.fromQuaternion(
            Quaternion.fromYawPitchRollDeg(yawDeg = 120f, pitchDeg = 90f, rollDeg = 25f),
            angularVelocityDegPerSec = 0.2f
        )

        // Lock retake onto the already captured ceiling target with realistic frame ticks
        val e1 = engine.evaluate(zenithPose, listOf(ceilingTarget, eyeTarget), lockedRetakeTargetId = "ceiling_0", nowMs = 100L)
        assertTrue(e1.isRetakeMode)
        assertEquals("ceiling_0", e1.activeTarget?.id)
        assertEquals(TiltStatus.LEVEL, e1.tiltStatus)
        assertFalse(e1.shouldTriggerAutoCapture)

        val e2 = engine.evaluate(zenithPose, listOf(ceilingTarget, eyeTarget), lockedRetakeTargetId = "ceiling_0", nowMs = 260L)
        assertFalse(e2.shouldTriggerAutoCapture)

        val e3 = engine.evaluate(zenithPose, listOf(ceilingTarget, eyeTarget), lockedRetakeTargetId = "ceiling_0", nowMs = 420L)
        assertTrue(e3.shouldTriggerAutoCapture)
    }

    @Test
    fun `simulated orientation sensor source drives aiming guidance end to end`() {
        var fakeTimeNanos = 1_000_000L
        val sim = com.example.data.sensor.SimulatedOrientationSensorSource(clockNanos = { fakeTimeNanos })
        sim.start()
        val plan = TargetPlanner.computeCoveragePlan(54f, 68f)
        val targets = plan.map {
            CaptureTargetState(
                id = it.id,
                orderIndex = it.orderIndex,
                ringType = it.ringType,
                targetYawDeg = it.targetYawDeg,
                targetPitchDeg = it.targetPitchDeg,
                targetDir = it.targetDir,
                captured = false
            )
        }

        // Point simulated sensor directly at upper_0
        val upper0 = targets.first { it.ringType == RingType.UPPER }
        sim.setOrientation(yaw = upper0.targetYawDeg, pitch = upper0.targetPitchDeg, roll = 0f, speedDegPerSec = 0.2f)

        val engine = AimingGuidanceEngine(AimingConfig(autoHoldDurationMs = 400L))
        var triggered = false
        for (t in 0L..500L step 100L) {
            val eval = engine.evaluate(sim.poseFlow.value, targets, lockedRetakeTargetId = null, nowMs = t)
            if (eval.shouldTriggerAutoCapture) {
                triggered = true
                assertEquals(upper0.id, eval.activeTarget?.id)
            }
        }
        assertTrue(triggered)
    }
}
