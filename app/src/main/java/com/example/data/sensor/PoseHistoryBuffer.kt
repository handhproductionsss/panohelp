package com.example.data.sensor

import com.example.domain.math.CameraPose
import com.example.domain.math.Quaternion
import kotlin.math.abs

/**
 * Thread-safe ring buffer of timestamped [CameraPose] readings.
 * Allows looking up and slerp-interpolating the exact orientation at the camera's
 * exposure timestamp (`elapsedRealtimeNanos`) rather than when capture was requested.
 */
class PoseHistoryBuffer(private val capacity: Int = 180) {
    private val samples = ArrayDeque<CameraPose>(capacity)
    private val lock = Any()

    fun add(pose: CameraPose) {
        synchronized(lock) {
            if (samples.size >= capacity) {
                samples.removeFirst()
            }
            samples.addLast(pose)
        }
    }

    fun clear() {
        synchronized(lock) {
            samples.clear()
        }
    }

    fun latest(): CameraPose? = synchronized(lock) { samples.lastOrNull() }

    /**
     * Returns an interpolated [CameraPose] at [targetTimestampNanos] if bracketed by samples,
     * or the nearest recorded sample if outside the recorded window.
     */
    fun poseAtOrNearest(targetTimestampNanos: Long): CameraPose? {
        synchronized(lock) {
            if (samples.isEmpty()) return null
            if (targetTimestampNanos <= 0L) return samples.last()

            var before: CameraPose? = null
            var after: CameraPose? = null

            for (sample in samples) {
                if (sample.timestampNanos <= targetTimestampNanos) {
                    before = sample
                } else {
                    after = sample
                    break
                }
            }

            if (before != null && after != null) {
                val span = (after.timestampNanos - before.timestampNanos).toDouble()
                if (span > 1_000.0) {
                    val t = ((targetTimestampNanos - before.timestampNanos) / span)
                        .toFloat()
                        .coerceIn(0f, 1f)
                    val qInterp = before.quaternion.slerp(after.quaternion, t)
                    val vInterp = before.angularVelocityDegPerSec +
                        t * (after.angularVelocityDegPerSec - before.angularVelocityDegPerSec)
                    return CameraPose.fromQuaternion(
                        quaternion = qInterp,
                        timestampNanos = targetTimestampNanos,
                        angularVelocityDegPerSec = vInterp
                    )
                }
                return before
            }

            return when {
                before != null -> before
                after != null -> after
                else -> samples.minByOrNull { abs(it.timestampNanos - targetTimestampNanos) }
            }
        }
    }
}
