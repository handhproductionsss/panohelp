package com.example.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.example.domain.math.CameraPose
import com.example.domain.math.Quaternion
import com.example.domain.math.SphereMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

enum class SensorSourceType(val label: String) {
    GAME_ROTATION_VECTOR("GAME_ROTATION_VECTOR"),
    ROTATION_VECTOR("ROTATION_VECTOR"),
    ACCEL_MAG_FALLBACK("ACCEL_MAG_FALLBACK"),
    SIMULATED("SIMULATED"),
    UNAVAILABLE("UNAVAILABLE")
}

data class SensorStatus(
    val sourceType: SensorSourceType,
    val isCompassFallback: Boolean = false,
    val isAvailable: Boolean = true
)

interface OrientationSensorSource {
    val poseFlow: StateFlow<CameraPose>
    val statusFlow: StateFlow<SensorStatus>
    val poseHistory: PoseHistoryBuffer
    fun start()
    fun stop()
}

/**
 * Hardware orientation source.
 *
 * Trade-off decision: Prefers [Sensor.TYPE_GAME_ROTATION_VECTOR] (gyro + accelerometer fusion)
 * over [Sensor.TYPE_ROTATION_VECTOR] (which includes magnetometer). Indoors, steel frames,
 * wiring, and speakers cause severe local magnetic distortion that warps azimuth spacing.
 * Game Rotation Vector avoids magnetic jumps over a 1-2 minute session at the cost of
 * an arbitrary initial azimuth (yaw = 0° at start) and minor long-term gyro drift.
 */
class AndroidOrientationSensorSource(
    context: Context
) : OrientationSensorSource, SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gameRotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val initialStatus: SensorStatus = when {
        gameRotationSensor != null -> SensorStatus(SensorSourceType.GAME_ROTATION_VECTOR, isCompassFallback = false, isAvailable = true)
        rotationSensor != null -> SensorStatus(SensorSourceType.ROTATION_VECTOR, isCompassFallback = true, isAvailable = true)
        accelSensor != null && magSensor != null -> SensorStatus(SensorSourceType.ACCEL_MAG_FALLBACK, isCompassFallback = true, isAvailable = true)
        else -> SensorStatus(SensorSourceType.UNAVAILABLE, isCompassFallback = false, isAvailable = false)
    }

    private val _poseFlow = MutableStateFlow(
        CameraPose.fromQuaternion(
            Quaternion.fromYawPitchRollDeg(0f, 0f, 0f),
            timestampNanos = SystemClock.elapsedRealtimeNanos()
        )
    )
    override val poseFlow: StateFlow<CameraPose> = _poseFlow.asStateFlow()

    private val _statusFlow = MutableStateFlow(initialStatus)
    override val statusFlow: StateFlow<SensorStatus> = _statusFlow.asStateFlow()

    override val poseHistory = PoseHistoryBuffer()

    private val rotationMatrix = FloatArray(9)
    private val gravityReading = FloatArray(3)
    private val geomagneticReading = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    @Volatile
    private var latestAngularSpeedDegPerSec = 0f
    private var lastPoseForSpeed: CameraPose? = null
    private var active = false

    override fun start() {
        if (active) return
        active = true
        poseHistory.clear()

        when (initialStatus.sourceType) {
            SensorSourceType.GAME_ROTATION_VECTOR -> {
                sensorManager.registerListener(this, gameRotationSensor, SensorManager.SENSOR_DELAY_GAME)
            }
            SensorSourceType.ROTATION_VECTOR -> {
                sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            }
            SensorSourceType.ACCEL_MAG_FALLBACK -> {
                sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
                sensorManager.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_GAME)
            }
            SensorSourceType.SIMULATED, SensorSourceType.UNAVAILABLE -> Unit
        }

        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun stop() {
        if (!active) return
        active = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]
                val radPerSec = sqrt(wx * wx + wy * wy + wz * wz)
                val degPerSec = Math.toDegrees(radPerSec.toDouble()).toFloat()
                // Smooth slightly to reject single-sample noise spikes
                latestAngularSpeedDegPerSec = latestAngularSpeedDegPerSec * 0.45f + degPerSec * 0.55f
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                emitPoseFromRotationMatrix(rotationMatrix, event.timestamp)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravityReading, 0, 3)
                hasGravity = true
                if (hasGeomagnetic && SensorManager.getRotationMatrix(rotationMatrix, null, gravityReading, geomagneticReading)) {
                    emitPoseFromRotationMatrix(rotationMatrix, event.timestamp)
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagneticReading, 0, 3)
                hasGeomagnetic = true
                if (hasGravity && SensorManager.getRotationMatrix(rotationMatrix, null, gravityReading, geomagneticReading)) {
                    emitPoseFromRotationMatrix(rotationMatrix, event.timestamp)
                }
            }
        }
    }

    private fun emitPoseFromRotationMatrix(matrix: FloatArray, timestampNanos: Long) {
        val q = Quaternion.fromRotationMatrix(matrix)
        val effectiveSpeed = if (gyroSensor != null) {
            latestAngularSpeedDegPerSec
        } else {
            val prev = lastPoseForSpeed
            if (prev != null && timestampNanos > prev.timestampNanos) {
                val dtSec = (timestampNanos - prev.timestampNanos) / 1_000_000_000f
                if (dtSec > 0.002f) {
                    val deltaDeg = prev.quaternion.angularDistanceDeg(q)
                    latestAngularSpeedDegPerSec = latestAngularSpeedDegPerSec * 0.5f + (deltaDeg / dtSec) * 0.5f
                }
            }
            latestAngularSpeedDegPerSec
        }

        val pose = CameraPose.fromQuaternion(
            quaternion = q,
            timestampNanos = timestampNanos,
            angularVelocityDegPerSec = effectiveSpeed
        )
        lastPoseForSpeed = pose
        poseHistory.add(pose)
        _poseFlow.value = pose
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * Interactive software orientation sensor for Emulator / Desktop testing without physical hardware.
 * Supports touch panning, jumping to a target direction, simulating roll tilt, and simulating fast shake.
 */
class SimulatedOrientationSensorSource(
    private val clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() }
) : OrientationSensorSource {

    private var yawDeg = 0f
    private var pitchDeg = 0f
    private var rollDeg = 0f
    private var simulatedSpeedDegPerSec = 0f

    private val _poseFlow = MutableStateFlow(buildPose())
    override val poseFlow: StateFlow<CameraPose> = _poseFlow.asStateFlow()

    private val _statusFlow = MutableStateFlow(
        SensorStatus(
            sourceType = SensorSourceType.SIMULATED,
            isCompassFallback = false,
            isAvailable = true
        )
    )
    override val statusFlow: StateFlow<SensorStatus> = _statusFlow.asStateFlow()

    override val poseHistory = PoseHistoryBuffer()

    override fun start() {
        poseHistory.clear()
        emitCurrentPose()
    }

    override fun stop() = Unit

    fun panBy(deltaYawDeg: Float, deltaPitchDeg: Float) {
        yawDeg = SphereMath.normalizeYawDeg(yawDeg + deltaYawDeg)
        pitchDeg = (pitchDeg + deltaPitchDeg).coerceIn(-89.5f, 89.5f)
        simulatedSpeedDegPerSec = 1.5f
        emitCurrentPose()
    }

    fun setOrientation(yaw: Float, pitch: Float, roll: Float = 0f, speedDegPerSec: Float = 0.5f) {
        yawDeg = SphereMath.normalizeYawDeg(yaw)
        pitchDeg = pitch.coerceIn(-90f, 90f)
        rollDeg = roll.coerceIn(-45f, 45f)
        simulatedSpeedDegPerSec = speedDegPerSec
        emitCurrentPose()
    }

    fun setRoll(roll: Float) {
        rollDeg = roll.coerceIn(-45f, 45f)
        emitCurrentPose()
    }

    fun setAngularSpeed(speedDegPerSec: Float) {
        simulatedSpeedDegPerSec = speedDegPerSec.coerceAtLeast(0f)
        emitCurrentPose()
    }

    fun tickSettle() {
        if (simulatedSpeedDegPerSec > 0.5f) {
            simulatedSpeedDegPerSec = (simulatedSpeedDegPerSec * 0.7f).let { if (it < 0.6f) 0.2f else it }
            emitCurrentPose()
        } else {
            // Keep poseHistory fresh with current timestamp for accurate exposure matching
            emitCurrentPose()
        }
    }

    private fun buildPose(): CameraPose {
        val q = Quaternion.fromYawPitchRollDeg(yawDeg, pitchDeg, rollDeg)
        return CameraPose.fromQuaternion(
            quaternion = q,
            timestampNanos = clockNanos(),
            angularVelocityDegPerSec = simulatedSpeedDegPerSec
        )
    }

    private fun emitCurrentPose() {
        val pose = buildPose()
        poseHistory.add(pose)
        _poseFlow.value = pose
    }
}
