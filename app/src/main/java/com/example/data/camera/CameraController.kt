package com.example.data.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.SizeF
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.example.data.sensor.PoseHistoryBuffer
import com.example.domain.capture.TargetPlanner
import com.example.domain.math.CameraPose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CameraHardwareInfo(
    val lensId: String = "0",
    val focalLengthMm: Float = 4.2f,
    val equivalent35mmFocalLength: Int? = 26,
    val hfovDeg: Float = TargetPlanner.DEFAULT_PORTRAIT_HFOV_DEG,
    val vfovDeg: Float = TargetPlanner.DEFAULT_PORTRAIT_VFOV_DEG,
    val aeLockSupported: Boolean = true,
    val awbLockSupported: Boolean = true,
    val aeLocked: Boolean = false,
    val awbLocked: Boolean = false,
    val timestampSourceIsRealtime: Boolean = true,
    val isReady: Boolean = false,
    val errorMessage: String? = null
)

data class CapturedImageResult(
    val photoFile: File,
    val thumbFile: File,
    val width: Int,
    val height: Int,
    val capturedAtEpochMs: Long,
    val poseAtExposure: CameraPose
)

/**
 * Manages a single back-facing CameraX session locked to portrait orientation with 4:3 framing,
 * optical FOV inspection via Camera2Interop, and AE/AWB locking for consistent panorama brightness/color.
 */
class CameraController(private val context: Context) {

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var boundCamera: Camera? = null
    private var fixedLensId: String? = null

    private val _hardwareInfo = MutableStateFlow(CameraHardwareInfo())
    val hardwareInfo: StateFlow<CameraHardwareInfo> = _hardwareInfo.asStateFlow()

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        preferredLensId: String? = null,
        shouldLockExposureImmediately: Boolean = false
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                cameraProvider = provider

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()

                // Stick to one consistent back lens across the entire session
                val selector = buildSingleBackLensSelector(provider, preferredLensId)

                provider.unbindAll()
                val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                boundCamera = camera
                imageCapture = capture

                val info = inspectHardwareInfo(camera)
                _hardwareInfo.value = info

                if (shouldLockExposureImmediately) {
                    lockExposureAndWhiteBalance()
                }
            }.onFailure { err ->
                _hardwareInfo.value = _hardwareInfo.value.copy(
                    isReady = false,
                    errorMessage = err.message ?: "Unable to start back camera"
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildSingleBackLensSelector(
        provider: ProcessCameraProvider,
        preferredLensId: String?
    ): CameraSelector {
        val targetId = preferredLensId ?: fixedLensId
        if (!targetId.isNullOrBlank()) {
            val matchExists = provider.availableCameraInfos.any { info ->
                runCatching { Camera2CameraInfo.from(info).cameraId == targetId }.getOrDefault(false)
            }
            if (matchExists) {
                return CameraSelector.Builder()
                    .addCameraFilter { infos ->
                        infos.filter { info ->
                            runCatching { Camera2CameraInfo.from(info).cameraId == targetId }.getOrDefault(false)
                        }
                    }
                    .build()
            }
        }
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun inspectHardwareInfo(camera: Camera): CameraHardwareInfo {
        val cam2Info = Camera2CameraInfo.from(camera.cameraInfo)
        val cameraId = cam2Info.cameraId
        fixedLensId = cameraId

        val physicalSize: SizeF? = cam2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLengths: FloatArray? = cam2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorOrientation: Int = cam2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val aeLockAvail = cam2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
        val awbLockAvail = cam2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false
        val timestampSrc = cam2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN

        val focalMm = focalLengths?.firstOrNull()?.takeIf { it > 0f } ?: 4.2f

        var hfovDeg = TargetPlanner.DEFAULT_PORTRAIT_HFOV_DEG
        var vfovDeg = TargetPlanner.DEFAULT_PORTRAIT_VFOV_DEG
        var eq35mm: Int? = 26

        if (physicalSize != null && physicalSize.width > 0f && physicalSize.height > 0f) {
            val sensorLong = max(physicalSize.width, physicalSize.height)
            val sensorShort = min(physicalSize.width, physicalSize.height)
            // In portrait, if sensorOrientation is 90/270, short sensor side is horizontal & long is vertical
            val isPortraitRotated = sensorOrientation == 90 || sensorOrientation == 270
            val horizMm = if (isPortraitRotated) sensorShort else sensorLong
            val vertMm = if (isPortraitRotated) sensorLong else sensorShort

            hfovDeg = Math.toDegrees(2.0 * atan((horizMm / (2f * focalMm)).toDouble())).toFloat()
                .coerceIn(30f, 110f)
            vfovDeg = Math.toDegrees(2.0 * atan((vertMm / (2f * focalMm)).toDouble())).toFloat()
                .coerceIn(35f, 120f)

            val diagMm = sqrt(sensorLong * sensorLong + sensorShort * sensorShort)
            val fullFrameDiagMm = 43.27f
            eq35mm = (focalMm * (fullFrameDiagMm / diagMm)).roundToInt().coerceIn(10, 200)
        }

        return CameraHardwareInfo(
            lensId = cameraId,
            focalLengthMm = focalMm,
            equivalent35mmFocalLength = eq35mm,
            hfovDeg = hfovDeg,
            vfovDeg = vfovDeg,
            aeLockSupported = aeLockAvail,
            awbLockSupported = awbLockAvail,
            aeLocked = false,
            awbLocked = false,
            timestampSourceIsRealtime = timestampSrc == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            isReady = true,
            errorMessage = null
        )
    }

    /**
     * Locks auto-exposure (AE) and auto-white-balance (AWB) via Camera2Interop so subsequent
     * panorama shots share consistent brightness and color temperature.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun lockExposureAndWhiteBalance() {
        val camera = boundCamera ?: return
        val info = _hardwareInfo.value
        if (!info.aeLockSupported && !info.awbLockSupported) return

        val builder = CaptureRequestOptions.Builder()
        if (info.aeLockSupported) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (info.awbLockSupported) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }

        val cam2Control = Camera2CameraControl.from(camera.cameraControl)
        cam2Control.setCaptureRequestOptions(builder.build())
        _hardwareInfo.value = info.copy(
            aeLocked = info.aeLockSupported,
            awbLocked = info.awbLockSupported
        )
    }

    suspend fun capturePhoto(
        photoFile: File,
        thumbFile: File,
        poseHistory: PoseHistoryBuffer,
        fallbackPose: CameraPose
    ): CapturedImageResult = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            cont.resumeWithException(IllegalStateException("Camera is not ready yet"))
            return@suspendCancellableCoroutine
        }

        val info = _hardwareInfo.value
        val requestTimeNanos = SystemClock.elapsedRealtimeNanos()

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                var exposureStartNanos: Long = requestTimeNanos

                override fun onCaptureStarted() {
                    exposureStartNanos = SystemClock.elapsedRealtimeNanos()
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val imageTimestampNanos = image.imageInfo.timestamp
                        val nowNanos = SystemClock.elapsedRealtimeNanos()

                        // Use sensor image timestamp when it's on the same clock epoch (REALTIME)
                        // Otherwise fall back to exposureStartNanos captured at shutter start.
                        val lookupNanos = if (info.timestampSourceIsRealtime &&
                            kotlin.math.abs(nowNanos - imageTimestampNanos) < 5_000_000_000L
                        ) {
                            imageTimestampNanos
                        } else {
                            exposureStartNanos
                        }

                        val poseAtExposure = poseHistory.poseAtOrNearest(lookupNanos) ?: fallbackPose
                        val capturedAtMs = System.currentTimeMillis()

                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        photoFile.parentFile?.mkdirs()
                        FileOutputStream(photoFile).use { out ->
                            out.write(bytes)
                        }

                        // Ensure EXIF rotation matches portrait and read dimensions
                        val rotationDeg = image.imageInfo.rotationDegrees
                        val dims = saveThumbnailAndReadDimensions(
                            jpegFile = photoFile,
                            thumbFile = thumbFile,
                            rotationDegrees = rotationDeg
                        )

                        ExifPoseWriter.writeMetadata(
                            jpegFile = photoFile,
                            pose = poseAtExposure,
                            focalLengthMm = info.focalLengthMm,
                            equivalent35mmFocalLength = info.equivalent35mmFocalLength,
                            imageWidth = dims.first,
                            imageHeight = dims.second,
                            capturedAtMs = capturedAtMs,
                            orientation = rotationDegreesToExif(rotationDeg)
                        )

                        if (cont.isActive) {
                            cont.resume(
                                CapturedImageResult(
                                    photoFile = photoFile,
                                    thumbFile = thumbFile,
                                    width = dims.first,
                                    height = dims.second,
                                    capturedAtEpochMs = capturedAtMs,
                                    poseAtExposure = poseAtExposure
                                )
                            )
                        }
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            }
        )
    }

    private fun saveThumbnailAndReadDimensions(
        jpegFile: File,
        thumbFile: File,
        rotationDegrees: Int
    ): Pair<Int, Int> {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpegFile.absolutePath, boundsOpts)
        val rawW = boundsOpts.outWidth.coerceAtLeast(1)
        val rawH = boundsOpts.outHeight.coerceAtLeast(1)

        val sampleSize = calculateInSampleSize(rawW, rawH, reqWidth = 220, reqHeight = 294)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(jpegFile.absolutePath, decodeOpts)

        if (decoded != null) {
            val exif = runCatching { ExifInterface(jpegFile) }.getOrNull()
            val exifOrient = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            ) ?: ExifInterface.ORIENTATION_UNDEFINED

            val matrixDeg = when (exifOrient) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> rotationDegrees.toFloat()
            }

            val finalThumb = if (matrixDeg != 0f) {
                val m = Matrix().apply { postRotate(matrixDeg) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
                    if (it != decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            thumbFile.parentFile?.mkdirs()
            FileOutputStream(thumbFile).use { out ->
                finalThumb.compress(Bitmap.CompressFormat.JPEG, 78, out)
            }
            finalThumb.recycle()
        }

        val orientedSwap = rotationDegrees == 90 || rotationDegrees == 270
        return if (orientedSwap && rawW > rawH) Pair(rawH, rawW) else Pair(rawW, rawH)
    }

    private fun calculateInSampleSize(rawW: Int, rawH: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (rawH > reqHeight || rawW > reqWidth) {
            val halfH = rawH / 2
            val halfW = rawW / 2
            while (halfH / inSampleSize >= reqHeight && halfW / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun rotationDegreesToExif(deg: Int): Int = when ((deg % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    fun release() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}
