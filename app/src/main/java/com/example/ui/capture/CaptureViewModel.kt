package com.example.ui.capture

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.camera.CameraController
import com.example.data.camera.CameraHardwareInfo
import com.example.data.camera.SimulatedCameraEngine
import com.example.data.export.ExportResult
import com.example.data.export.PackageExporter
import com.example.data.local.AppPreferences
import com.example.data.local.PanoDatabase
import com.example.data.local.SceneWithTargets
import com.example.data.repository.SceneRepository
import com.example.data.repository.SceneRepository.Companion.toCaptureState
import com.example.data.sensor.AndroidOrientationSensorSource
import com.example.data.sensor.OrientationSensorSource
import com.example.data.sensor.SensorSourceType
import com.example.data.sensor.SensorStatus
import com.example.data.sensor.SimulatedOrientationSensorSource
import com.example.domain.capture.AimingConfig
import com.example.domain.capture.AimingEvaluation
import com.example.domain.capture.AimingGuidanceEngine
import com.example.domain.capture.CaptureTargetState
import com.example.domain.capture.TiltStatus
import com.example.domain.math.CameraPose
import com.example.domain.math.Quaternion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CaptureUiState(
    val sceneName: String = "",
    val isSimulated: Boolean = false,
    val targets: List<CaptureTargetState> = emptyList(),
    val currentPose: CameraPose = CameraPose.fromQuaternion(Quaternion.IDENTITY),
    val evaluation: AimingEvaluation = AimingEvaluation(
        activeTarget = null,
        angularDistanceToTargetDeg = null,
        isAimedAtTarget = false,
        tiltStatus = TiltStatus.LEVEL,
        isMovingTooFast = false,
        canManualCapture = false,
        autoHoldProgress = 0f,
        shouldTriggerAutoCapture = false,
        allTargetsCaptured = false,
        isRetakeMode = false
    ),
    val lockedRetakeTargetId: String? = null,
    val cameraInfo: CameraHardwareInfo = CameraHardwareInfo(),
    val sensorStatus: SensorStatus = SensorStatus(SensorSourceType.GAME_ROTATION_VECTOR),
    val soundEnabled: Boolean = true,
    val isCaptureInFlight: Boolean = false,
    val flashTriggerCounter: Int = 0,
    val isLowStorage: Boolean = false,
    val showIncompleteFinishConfirm: Boolean = false,
    val isExporting: Boolean = false,
    val exportResult: ExportResult? = null,
    val errorMessage: String? = null,
    val simTiltActive: Boolean = false,
    val simShakeActive: Boolean = false
)

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val database = PanoDatabase.getInstance(application)
    private val repository = SceneRepository(application, database.panoDao())
    private val preferences = AppPreferences(application)
    private val exporter = PackageExporter(application)
    val cameraController = CameraController(application)

    private var activeSceneId: String? = null
    private var sensorSource: OrientationSensorSource? = null
    private var simulatedSensor: SimulatedOrientationSensorSource? = null
    private val aimingEngine = AimingGuidanceEngine(AimingConfig())
    private var tickerJob: Job? = null
    private var latestSceneWithTargets: SceneWithTargets? = null
    private var hasSyncedCameraSpecs = false

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private val toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_SYSTEM, 70)
    }.getOrNull()

    fun initSession(sceneId: String) {
        if (activeSceneId == sceneId) return
        activeSceneId = sceneId

        viewModelScope.launch {
            val sound = preferences.soundEnabledFlow.first()
            val initial = repository.getSceneOnce(sceneId)
            val isSim = initial?.scene?.isSimulated ?: preferences.simulatedModeFlow.first()

            setupSensorSource(isSim)
            checkStorage()

            _uiState.value = _uiState.value.copy(
                sceneName = initial?.scene?.name ?: "Scene",
                isSimulated = isSim,
                soundEnabled = sound
            )

            launch {
                repository.observeScene(sceneId).collect { swt ->
                    if (swt != null) {
                        latestSceneWithTargets = swt
                        val mappedTargets = swt.targets
                            .sortedBy { it.orderIndex }
                            .map { it.toCaptureState() }
                        _uiState.value = _uiState.value.copy(
                            sceneName = swt.scene.name,
                            targets = mappedTargets,
                            isSimulated = swt.scene.isSimulated
                        )
                    }
                }
            }

            if (!isSim) {
                launch {
                    cameraController.hardwareInfo.collect { hw ->
                        _uiState.value = _uiState.value.copy(cameraInfo = hw)
                        if (hw.isReady && !hasSyncedCameraSpecs) {
                            hasSyncedCameraSpecs = true
                            repository.updateCameraSpecsAndReplanIfUntouched(
                                sceneId = sceneId,
                                hfovDeg = hw.hfovDeg,
                                vfovDeg = hw.vfovDeg,
                                focalLengthMm = hw.focalLengthMm,
                                lensId = hw.lensId,
                                aeLockSupported = hw.aeLockSupported,
                                awbLockSupported = hw.awbLockSupported
                            )
                        }
                    }
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    cameraInfo = CameraHardwareInfo(
                        lensId = "simulated_back",
                        focalLengthMm = 4.2f,
                        equivalent35mmFocalLength = 26,
                        hfovDeg = 54f,
                        vfovDeg = 68f,
                        aeLockSupported = true,
                        awbLockSupported = true,
                        aeLocked = true,
                        awbLocked = true,
                        isReady = true
                    )
                )
            }
        }
    }

    private fun setupSensorSource(isSimulated: Boolean) {
        sensorSource?.stop()
        if (isSimulated) {
            val sim = SimulatedOrientationSensorSource()
            simulatedSensor = sim
            sensorSource = sim
        } else {
            simulatedSensor = null
            sensorSource = AndroidOrientationSensorSource(getApplication())
        }
        sensorSource?.start()
        startGuidanceTicker()
    }

    fun switchToSimulatedMode() {
        val sceneId = activeSceneId ?: return
        viewModelScope.launch {
            preferences.setSimulatedMode(true)
            val current = repository.getSceneOnce(sceneId)
            if (current != null) {
                database.panoDao().updateScene(
                    current.scene.copy(isSimulated = true, sensorSource = "SIMULATED")
                )
            }
            setupSensorSource(isSimulated = true)
            _uiState.value = _uiState.value.copy(
                isSimulated = true,
                cameraInfo = CameraHardwareInfo(
                    lensId = "simulated_back",
                    focalLengthMm = 4.2f,
                    equivalent35mmFocalLength = 26,
                    hfovDeg = 54f,
                    vfovDeg = 68f,
                    aeLockSupported = true,
                    awbLockSupported = true,
                    aeLocked = true,
                    awbLocked = true,
                    isReady = true
                )
            )
        }
    }

    fun onLifecycleResume() {
        sensorSource?.start()
        startGuidanceTicker()
        checkStorage()
    }

    fun onLifecyclePause() {
        tickerJob?.cancel()
        sensorSource?.stop()
        aimingEngine.resetHold()
    }

    private fun startGuidanceTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val src = sensorSource
                if (src != null) {
                    simulatedSensor?.let { sim ->
                        if (!_uiState.value.simShakeActive) {
                            sim.tickSettle()
                        }
                    }

                    val pose = src.poseFlow.value
                    val sensorStatus = src.statusFlow.value
                    val current = _uiState.value

                    val eval = aimingEngine.evaluate(
                        pose = pose,
                        targets = current.targets,
                        lockedRetakeTargetId = current.lockedRetakeTargetId,
                        nowMs = System.currentTimeMillis(),
                        isCaptureInFlight = current.isCaptureInFlight
                    )

                    _uiState.value = current.copy(
                        currentPose = pose,
                        sensorStatus = sensorStatus,
                        evaluation = eval
                    )

                    if (eval.shouldTriggerAutoCapture && eval.activeTarget != null && !current.isCaptureInFlight) {
                        triggerCaptureForTarget(eval.activeTarget)
                    }
                }
                delay(16L)
            }
        }
    }

    fun onTargetTapped(targetId: String) {
        aimingEngine.resetHold()
        _uiState.value = _uiState.value.copy(lockedRetakeTargetId = targetId)
    }

    fun cancelRetakeMode() {
        aimingEngine.resetHold()
        _uiState.value = _uiState.value.copy(lockedRetakeTargetId = null)
    }

    fun onManualCaptureClick() {
        val current = _uiState.value
        if (current.isCaptureInFlight || current.evaluation.isMovingTooFast) return

        val target = current.evaluation.activeTarget
            ?: current.targets.minByOrNull { current.currentPose.forward.angleDegreesTo(it.targetDir) }
            ?: return

        triggerCaptureForTarget(target)
    }

    private fun triggerCaptureForTarget(target: CaptureTargetState) {
        val sceneId = activeSceneId ?: return
        val src = sensorSource ?: return
        val current = _uiState.value
        if (current.isCaptureInFlight) return

        _uiState.value = current.copy(isCaptureInFlight = true, errorMessage = null)

        viewModelScope.launch {
            runCatching {
                val (photoFile, thumbFile) = repository.photoFilesForTarget(sceneId, target.id)
                val hw = _uiState.value.cameraInfo
                val result = if (_uiState.value.isSimulated) {
                    withContext(Dispatchers.IO) {
                        SimulatedCameraEngine.captureSimulatedPhoto(
                            photoFile = photoFile,
                            thumbFile = thumbFile,
                            pose = _uiState.value.currentPose,
                            hfovDeg = hw.hfovDeg,
                            vfovDeg = hw.vfovDeg,
                            focalLengthMm = hw.focalLengthMm
                        )
                    }
                } else {
                    cameraController.capturePhoto(
                        photoFile = photoFile,
                        thumbFile = thumbFile,
                        poseHistory = src.poseHistory,
                        fallbackPose = _uiState.value.currentPose
                    )
                }

                // Lock exposure & white balance right after first real capture so all subsequent shots match
                if (!_uiState.value.isSimulated && (!hw.aeLocked || !hw.awbLocked)) {
                    cameraController.lockExposureAndWhiteBalance()
                }

                val updatedHw = cameraController.hardwareInfo.value
                repository.recordTargetCapture(
                    sceneId = sceneId,
                    targetId = target.id,
                    photoFile = result.photoFile,
                    thumbFile = result.thumbFile,
                    width = result.width,
                    height = result.height,
                    capturedAtMs = result.capturedAtEpochMs,
                    pose = result.poseAtExposure,
                    aeLocked = if (_uiState.value.isSimulated) true else updatedHw.aeLocked,
                    awbLocked = if (_uiState.value.isSimulated) true else updatedHw.awbLocked,
                    sensorSource = _uiState.value.sensorStatus.sourceType.label
                )
            }.onSuccess {
                playCaptureFeedback()
                aimingEngine.resetHold()
                checkStorage()
                _uiState.value = _uiState.value.copy(
                    isCaptureInFlight = false,
                    lockedRetakeTargetId = null,
                    flashTriggerCounter = _uiState.value.flashTriggerCounter + 1
                )
            }.onFailure { err ->
                aimingEngine.resetHold()
                _uiState.value = _uiState.value.copy(
                    isCaptureInFlight = false,
                    errorMessage = err.message ?: "Photo capture failed"
                )
            }
        }
    }

    fun toggleSound() {
        val next = !_uiState.value.soundEnabled
        _uiState.value = _uiState.value.copy(soundEnabled = next)
        viewModelScope.launch {
            preferences.setSoundEnabled(next)
        }
    }

    fun onFinishClicked() {
        val targets = _uiState.value.targets
        if (targets.none { it.captured }) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Take at least one photo before exporting."
            )
            return
        }
        val hasUncaptured = targets.any { !it.captured }
        if (hasUncaptured) {
            _uiState.value = _uiState.value.copy(showIncompleteFinishConfirm = true)
        } else {
            performExport()
        }
    }

    fun dismissIncompleteFinishConfirm() {
        _uiState.value = _uiState.value.copy(showIncompleteFinishConfirm = false)
    }

    fun performExport() {
        val swt = latestSceneWithTargets ?: return
        _uiState.value = _uiState.value.copy(
            showIncompleteFinishConfirm = false,
            isExporting = true,
            errorMessage = null
        )

        viewModelScope.launch {
            val latest = repository.getSceneOnce(swt.scene.id) ?: swt
            runCatching {
                val res = exporter.exportScene(latest)
                repository.markExported(
                    sceneId = latest.scene.id,
                    exportUri = res.downloadsUriString,
                    exportDisplayName = res.zipFileName
                )
                res
            }.onSuccess { res ->
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    exportResult = res
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    errorMessage = err.message ?: "Export failed"
                )
            }
        }
    }

    fun buildShareIntent(result: ExportResult): Intent {
        return exporter.createShareIntent(result.shareUri, _uiState.value.sceneName)
    }

    fun dismissExportResult() {
        _uiState.value = _uiState.value.copy(exportResult = null)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    // Simulated Mode controls for testing without a physical phone
    fun onSimDrag(dxPx: Float, dyPx: Float, viewWidth: Float, viewHeight: Float) {
        val sim = simulatedSensor ?: return
        if (viewWidth <= 1f || viewHeight <= 1f) return
        val hw = _uiState.value.cameraInfo
        val dYaw = -(dxPx / viewWidth) * hw.hfovDeg
        val dPitch = (dyPx / viewHeight) * hw.vfovDeg
        sim.panBy(dYaw, dPitch)
    }

    fun onSimAimNearest() {
        val sim = simulatedSensor ?: return
        val target = _uiState.value.evaluation.activeTarget
            ?: _uiState.value.targets.firstOrNull { !it.captured }
            ?: return
        sim.setOrientation(
            yaw = target.targetYawDeg,
            pitch = target.targetPitchDeg,
            roll = 0f,
            speedDegPerSec = 0.2f
        )
        _uiState.value = _uiState.value.copy(simTiltActive = false, simShakeActive = false)
    }

    fun onSimToggleTilt() {
        val sim = simulatedSensor ?: return
        val next = !_uiState.value.simTiltActive
        sim.setRoll(if (next) 15f else 0f)
        _uiState.value = _uiState.value.copy(simTiltActive = next)
    }

    fun onSimToggleShake() {
        val sim = simulatedSensor ?: return
        val next = !_uiState.value.simShakeActive
        sim.setAngularSpeed(if (next) 28f else 0.2f)
        _uiState.value = _uiState.value.copy(simShakeActive = next)
    }

    private fun checkStorage() {
        val availMb = repository.getAvailableStorageMb()
        _uiState.value = _uiState.value.copy(isLowStorage = availMb < 100L)
    }

    private fun playCaptureFeedback() {
        val ctx = getApplication<Application>()
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Vibrator::class.java)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(45L)
            }
        }
        if (_uiState.value.soundEnabled) {
            runCatching {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
        sensorSource?.stop()
        cameraController.release()
        toneGenerator?.release()
    }
}
