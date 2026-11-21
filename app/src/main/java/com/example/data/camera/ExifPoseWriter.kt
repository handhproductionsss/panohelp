package com.example.data.camera

import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.example.domain.math.CameraPose
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ensures captured JPEG files contain standard EXIF metadata (focal length, orientation,
 * capture timestamp with millisecond subseconds, image dimensions, and pose comment)
 * so external stitchers that rely solely on EXIF tags still work reliably.
 */
object ExifPoseWriter {

    fun writeMetadata(
        jpegFile: File,
        pose: CameraPose,
        focalLengthMm: Float,
        equivalent35mmFocalLength: Int?,
        imageWidth: Int,
        imageHeight: Int,
        capturedAtMs: Long,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL
    ) {
        runCatching {
            val exif = ExifInterface(jpegFile)

            // Only overwrite orientation if not already set by CameraX, or explicitly provided
            val existingOrientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
            if (existingOrientation == ExifInterface.ORIENTATION_UNDEFINED) {
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            }

            if (exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH).isNullOrBlank() && focalLengthMm > 0f) {
                val num = (focalLengthMm * 100f).roundToInt()
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "$num/100")
            }

            if (equivalent35mmFocalLength != null &&
                equivalent35mmFocalLength > 0 &&
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM).isNullOrBlank()
            ) {
                exif.setAttribute(
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                    equivalent35mmFocalLength.toString()
                )
            }

            if (exif.getAttribute(ExifInterface.TAG_MAKE).isNullOrBlank()) {
                exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER ?: "Android")
            }
            if (exif.getAttribute(ExifInterface.TAG_MODEL).isNullOrBlank()) {
                exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL ?: "PanoGuide")
            }

            val dtFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val dateStr = dtFormat.format(Date(capturedAtMs))
            val subsec = String.format(Locale.US, "%03d", capturedAtMs % 1000L)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subsec)

            if (imageWidth > 0 && imageHeight > 0) {
                exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, imageWidth.toString())
                exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, imageHeight.toString())
            }

            exif.setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                String.format(
                    Locale.US,
                    "PanoGuide yaw=%.2f pitch=%.2f roll=%.2f quat=[%.5f,%.5f,%.5f,%.5f]",
                    pose.yawDeg,
                    pose.pitchDeg,
                    pose.rollDeg,
                    pose.quaternion.w,
                    pose.quaternion.x,
                    pose.quaternion.y,
                    pose.quaternion.z
                )
            )
            exif.saveAttributes()
        }
    }
}
