package com.example.domain.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 3D vector in right-handed coordinates.
 *
 * World reference frame:
 * - +X: East / Right (at yaw = 0)
 * - +Y: Forward / Reference North (at yaw = 0)
 * - +Z: Up / Zenith (opposite gravity)
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vec3(x * scalar, y * scalar, z * scalar)

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun norm(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val n = norm()
        return if (n > 1e-6f) Vec3(x / n, y / n, z / n) else Vec3(0f, 1f, 0f)
    }

    fun angleRadiansTo(other: Vec3): Float {
        val d = (this.normalized().dot(other.normalized())).coerceIn(-1f, 1f)
        return acos(d)
    }

    fun angleDegreesTo(other: Vec3): Float = Math.toDegrees(angleRadiansTo(other).toDouble()).toFloat()

    companion object {
        val WORLD_UP = Vec3(0f, 0f, 1f)
        val WORLD_FORWARD = Vec3(0f, 1f, 0f)
        val WORLD_RIGHT = Vec3(1f, 0f, 0f)
    }
}

/**
 * Unit quaternion (w, x, y, z) representing rotation from portrait camera/device frame
 * (+X right, +Y top of phone, +Z out of screen; back camera looks along -Z)
 * into world frame (+X East/Right, +Y Forward/North, +Z Up/Zenith).
 *
 * Quaternions remain singularity-free at zenith (+90° pitch) and nadir (-90° pitch).
 */
data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {

    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n > 1e-6f) Quaternion(w / n, x / n, y / n, z / n) else IDENTITY
    }

    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    operator fun times(q: Quaternion): Quaternion = Quaternion(
        w * q.w - x * q.x - y * q.y - z * q.z,
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w
    )

    /** Rotates a vector from camera/device frame to world frame. */
    fun rotateVector(v: Vec3): Vec3 {
        val qv = Quaternion(0f, v.x, v.y, v.z)
        val res = this * qv * this.conjugate()
        return Vec3(res.x, res.y, res.z)
    }

    /** Rotates a vector from world frame into camera/device frame. */
    fun inverseRotateVector(v: Vec3): Vec3 = conjugate().rotateVector(v)

    fun dot(other: Quaternion): Float = w * other.w + x * other.x + y * other.y + z * other.z

    fun angularDistanceDeg(other: Quaternion): Float {
        val d = abs(this.normalized().dot(other.normalized())).coerceIn(0f, 1f)
        return Math.toDegrees(2.0 * acos(d.toDouble())).toFloat()
    }

    /** Spherical linear interpolation handling shortest-arc hemisphere alignment. */
    fun slerp(target: Quaternion, t: Float): Quaternion {
        val clampedT = t.coerceIn(0f, 1f)
        var a = this.normalized()
        var b = target.normalized()
        var cosHalfTheta = a.dot(b)
        if (cosHalfTheta < 0f) {
            b = Quaternion(-b.w, -b.x, -b.y, -b.z)
            cosHalfTheta = -cosHalfTheta
        }
        if (cosHalfTheta > 0.9995f) {
            return Quaternion(
                a.w + clampedT * (b.w - a.w),
                a.x + clampedT * (b.x - a.x),
                a.y + clampedT * (b.y - a.y),
                a.z + clampedT * (b.z - a.z)
            ).normalized()
        }
        val halfTheta = acos(cosHalfTheta.coerceIn(-1f, 1f))
        val sinHalfTheta = sqrt((1f - cosHalfTheta * cosHalfTheta).coerceAtLeast(1e-6f))
        val ratioA = sin((1f - clampedT) * halfTheta) / sinHalfTheta
        val ratioB = sin(clampedT * halfTheta) / sinHalfTheta
        return Quaternion(
            a.w * ratioA + b.w * ratioB,
            a.x * ratioA + b.x * ratioB,
            a.y * ratioA + b.y * ratioB,
            a.z * ratioA + b.z * ratioB
        ).normalized()
    }

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        /**
         * Builds a unit quaternion from a 3x3 row-major rotation matrix (as returned by
         * [android.hardware.SensorManager.getRotationMatrixFromVector]).
         */
        fun fromRotationMatrix(m: FloatArray): Quaternion {
            require(m.size >= 9) { "Rotation matrix must have at least 9 elements" }
            val m00 = m[0]; val m01 = m[1]; val m02 = m[2]
            val m10 = m[3]; val m11 = m[4]; val m12 = m[5]
            val m20 = m[6]; val m21 = m[7]; val m22 = m[8]
            val trace = m00 + m11 + m22
            return if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                Quaternion(
                    w = 0.25f * s,
                    x = (m21 - m12) / s,
                    y = (m02 - m20) / s,
                    z = (m10 - m01) / s
                ).normalized()
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                Quaternion(
                    w = (m21 - m12) / s,
                    x = 0.25f * s,
                    y = (m01 + m10) / s,
                    z = (m02 + m20) / s
                ).normalized()
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                Quaternion(
                    w = (m02 - m20) / s,
                    x = (m01 + m10) / s,
                    y = 0.25f * s,
                    z = (m12 + m21) / s
                ).normalized()
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                Quaternion(
                    w = (m10 - m01) / s,
                    x = (m02 + m20) / s,
                    y = (m12 + m21) / s,
                    z = 0.25f * s
                ).normalized()
            }
        }

        /**
         * Builds a camera-to-world quaternion from convenience Euler angles:
         * - [yawDeg]: azimuth clockwise from +Y (0° = +Y, 90° = +X)
         * - [pitchDeg]: elevation above horizon (-90° = down, 0° = level, +90° = zenith)
         * - [rollDeg]: clockwise horizon roll around optical axis (+ = right edge drops)
         */
        fun fromYawPitchRollDeg(yawDeg: Float, pitchDeg: Float, rollDeg: Float = 0f): Quaternion {
            val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()
            val pitchRad = Math.toRadians(pitchDeg.toDouble()).toFloat()
            val rollRad = Math.toRadians(rollDeg.toDouble()).toFloat()

            // Base portrait orientation pointing at +Y (yaw=0, pitch=0, roll=0):
            // Camera right (+Xc) = +X, Camera up (+Yc) = +Z, Camera back (+Zc) = -Y
            val baseRight = Vec3(1f, 0f, 0f)
            val baseUp = Vec3(0f, 0f, 1f)
            val baseBack = Vec3(0f, -1f, 0f)
            val baseQ = fromBasis(baseRight, baseUp, baseBack)

            // Apply intrinsic order: World Z-yaw (-yawRad around +Z for clockwise azimuth),
            // then local right pitch (+pitchRad around camera +Xc),
            // then local optical roll (-rollRad around camera -Zc, i.e., +rollRad around +Zc? Let's verify!).
            // Let's verify sign: looking along -Zc, clockwise tilt moves +Yc toward +Xc.
            // Rotation around -Zc by +rollRad (right-hand rule thumb along -Zc) rotates +Xc toward -Yc, wait:
            // Right-hand thumb along +Zc (toward user): fingers curl from +Xc toward +Yc (CCW on screen).
            // So clockwise tilt on screen is NEGATIVE rotation around +Zc!
            val qYaw = axisAngle(Vec3(0f, 0f, 1f), -yawRad)
            val qPitch = axisAngle(Vec3(1f, 0f, 0f), pitchRad)
            val qRoll = axisAngle(Vec3(0f, 0f, 1f), -rollRad)

            return (qYaw * baseQ * qPitch * qRoll).normalized()
        }

        fun axisAngle(axis: Vec3, angleRad: Float): Quaternion {
            val u = axis.normalized()
            val half = angleRad * 0.5f
            val s = sin(half)
            return Quaternion(cos(half), u.x * s, u.y * s, u.z * s).normalized()
        }

        fun fromBasis(right: Vec3, up: Vec3, back: Vec3): Quaternion {
            val m = floatArrayOf(
                right.x, up.x, back.x,
                right.y, up.y, back.y,
                right.z, up.z, back.z
            )
            return fromRotationMatrix(m)
        }
    }
}

data class CameraPose(
    val quaternion: Quaternion,
    val forward: Vec3,
    val up: Vec3,
    val right: Vec3,
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val timestampNanos: Long,
    val angularVelocityDegPerSec: Float = 0f
) {
    companion object {
        fun fromQuaternion(
            quaternion: Quaternion,
            timestampNanos: Long = 0L,
            angularVelocityDegPerSec: Float = 0f
        ): CameraPose {
            val q = quaternion.normalized()
            val forward = q.rotateVector(Vec3(0f, 0f, -1f)).normalized()
            val up = q.rotateVector(Vec3(0f, 1f, 0f)).normalized()
            val right = q.rotateVector(Vec3(1f, 0f, 0f)).normalized()

            val pitchRad = asin(forward.z.coerceIn(-1f, 1f))
            val pitchDeg = Math.toDegrees(pitchRad.toDouble()).toFloat()

            // Away from zenith/nadir, compute azimuth from forward; near zenith use -up projection
            val horizNorm = sqrt(forward.x * forward.x + forward.y * forward.y)
            val yawDeg = if (horizNorm > 0.05f) {
                var deg = Math.toDegrees(atan2(forward.x.toDouble(), forward.y.toDouble())).toFloat()
                if (deg < 0f) deg += 360f
                deg
            } else {
                // At zenith (+90° pitch), camera's -up points along the horizontal forward direction
                val ref = if (forward.z >= 0f) up * -1f else up
                var deg = Math.toDegrees(atan2(ref.x.toDouble(), ref.y.toDouble())).toFloat()
                if (deg < 0f) deg += 360f
                deg
            }

            // Roll is clockwise tilt around optical axis relative to world horizon; undefined near zenith
            val rollDeg = if (abs(pitchDeg) <= 80f) {
                val horizRight = forward.cross(Vec3.WORLD_UP).normalized()
                val horizUp = horizRight.cross(forward).normalized()
                val rollRad = atan2(up.dot(horizRight).toDouble(), up.dot(horizUp).toDouble())
                Math.toDegrees(rollRad).toFloat()
            } else {
                0f
            }

            return CameraPose(
                quaternion = q,
                forward = forward,
                up = up,
                right = right,
                yawDeg = yawDeg,
                pitchDeg = pitchDeg,
                rollDeg = rollDeg,
                timestampNanos = timestampNanos,
                angularVelocityDegPerSec = angularVelocityDegPerSec
            )
        }
    }
}

data class ProjectedPoint(
    val x: Float,
    val y: Float,
    val angularOffsetDeg: Float,
    val isInFront: Boolean,
    val isInsideViewport: Boolean
)

object SphereMath {
    fun directionFromYawPitchDeg(yawDeg: Float, pitchDeg: Float): Vec3 {
        val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()
        val pitchRad = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val cosP = cos(pitchRad)
        return Vec3(
            x = cosP * sin(yawRad),
            y = cosP * cos(yawRad),
            z = sin(pitchRad)
        ).normalized()
    }

    /**
     * Projects a 3D world unit direction onto a 2D portrait viewfinder of size [width] x [height]
     * using a pinhole model with [hfovDeg] and [vfovDeg].
     *
     * Also returns a screen-space direction even for points behind the camera so off-screen
     * directional guide chevrons can point cleanly toward the target.
     */
    fun projectDirectionToScreen(
        worldDir: Vec3,
        cameraPose: CameraPose,
        hfovDeg: Float,
        vfovDeg: Float,
        width: Float,
        height: Float
    ): ProjectedPoint {
        val camVec = cameraPose.quaternion.inverseRotateVector(worldDir.normalized())
        // In camera space: +X = right, +Y = screen up, -Z = forward into scene
        val forwardDepth = -camVec.z
        val angularOffsetDeg = cameraPose.forward.angleDegreesTo(worldDir)

        val halfTanH = tan(Math.toRadians((hfovDeg * 0.5f).toDouble())).toFloat().coerceAtLeast(0.05f)
        val halfTanV = tan(Math.toRadians((vfovDeg * 0.5f).toDouble())).toFloat().coerceAtLeast(0.05f)

        val cx = width * 0.5f
        val cy = height * 0.5f

        if (forwardDepth > 0.05f) {
            val ndcX = (camVec.x / forwardDepth) / halfTanH
            val ndcY = (camVec.y / forwardDepth) / halfTanV
            val sx = cx + ndcX * cx
            val sy = cy - ndcY * cy // Screen Y grows downward
            val inside = sx in 0f..width && sy in 0f..height
            return ProjectedPoint(
                x = sx,
                y = sy,
                angularOffsetDeg = angularOffsetDeg,
                isInFront = true,
                isInsideViewport = inside
            )
        } else {
            // Point is behind or nearly perpendicular: push along camera-plane XY direction
            val planeLen = sqrt(camVec.x * camVec.x + camVec.y * camVec.y)
            val dirX = if (planeLen > 1e-4f) camVec.x / planeLen else 1f
            val dirY = if (planeLen > 1e-4f) camVec.y / planeLen else 0f
            val sx = cx + dirX * width * 1.5f
            val sy = cy - dirY * height * 1.5f
            return ProjectedPoint(
                x = sx,
                y = sy,
                angularOffsetDeg = angularOffsetDeg,
                isInFront = false,
                isInsideViewport = false
            )
        }
    }

    /** Computes screen-space clockwise rotation angle (degrees) for a target thumbnail quad. */
    fun computeScreenRollRelativeToTarget(
        targetDir: Vec3,
        cameraPose: CameraPose
    ): Float {
        // Approximate target's upright vector in world space
        val targetUpWorld = if (abs(targetDir.z) < 0.96f) {
            val right = targetDir.cross(Vec3.WORLD_UP).normalized()
            right.cross(targetDir).normalized()
        } else {
            Vec3.WORLD_FORWARD
        }
        val upInCam = cameraPose.quaternion.inverseRotateVector(targetUpWorld)
        // Angle between camera's +Y (screen up) and target's upright vector in XY camera plane
        return Math.toDegrees(atan2(upInCam.x.toDouble(), upInCam.y.toDouble())).toFloat()
    }

    fun normalizeYawDeg(yawDeg: Float): Float {
        var r = yawDeg % 360f
        if (r < 0f) r += 360f
        return r
    }

    fun shortestYawDiffDeg(fromDeg: Float, toDeg: Float): Float {
        var diff = (toDeg - fromDeg) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    fun clampAngleDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f
}
