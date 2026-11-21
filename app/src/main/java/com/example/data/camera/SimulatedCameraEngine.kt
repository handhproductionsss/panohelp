package com.example.data.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.exifinterface.media.ExifInterface
import com.example.domain.math.CameraPose
import com.example.domain.math.SphereMath
import com.example.domain.math.Vec3
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates both a live 3D virtual room preview and real JPEG photos (with EXIF metadata)
 * for Simulated Mode, allowing full end-to-end testing of aiming, capture, retake,
 * and export on an emulator or computer without physical motion sensors or camera hardware.
 */
object SimulatedCameraEngine {

    private data class RoomLandmark(
        val dir: Vec3,
        val label: String,
        val color: Int,
        val radiusDeg: Float
    )

    private val landmarks = listOf(
        RoomLandmark(SphereMath.directionFromYawPitchDeg(0f, 5f), "NORTH WINDOW", Color.rgb(56, 189, 248), 16f),
        RoomLandmark(SphereMath.directionFromYawPitchDeg(90f, 0f), "EAST BOOKSHELF", Color.rgb(251, 191, 36), 14f),
        RoomLandmark(SphereMath.directionFromYawPitchDeg(180f, 3f), "SOUTH DOORWAY", Color.rgb(52, 211, 153), 15f),
        RoomLandmark(SphereMath.directionFromYawPitchDeg(270f, 4f), "WEST GALLERY", Color.rgb(244, 114, 182), 14f),
        RoomLandmark(SphereMath.directionFromYawPitchDeg(45f, 48f), "CORNER SCONCE", Color.rgb(253, 224, 71), 10f),
        RoomLandmark(SphereMath.directionFromYawPitchDeg(225f, -45f), "SIDE TABLE", Color.rgb(167, 139, 250), 11f),
        RoomLandmark(Vec3(0f, 0f, 1f), "CEILING LAMP", Color.rgb(254, 240, 138), 14f)
    )

    fun renderRoomToCanvas(
        canvas: Canvas,
        width: Int,
        height: Int,
        pose: CameraPose,
        hfovDeg: Float,
        vfovDeg: Float
    ) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Base room gradient background
        canvas.drawColor(Color.rgb(18, 25, 38))

        val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(70, 148, 163, 184)
        }
        val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(130, 56, 189, 248)
        }

        // Draw latitude bands (floor, horizon, upper wall, ceiling) as projected polygon strips
        drawRoomBands(canvas, w, h, pose, hfovDeg, vfovDeg, wallPaint)

        // Draw azimuth meridians every 30° and elevation rings every 30°
        for (az in 0 until 360 step 30) {
            drawProjectedPolyline(canvas, w, h, pose, hfovDeg, vfovDeg, gridPaint) { step ->
                val pitch = -75f + step * 15f // 11 steps from -75 to +75
                SphereMath.directionFromYawPitchDeg(az.toFloat(), pitch)
            }
        }
        for (pitch in listOf(-60f, -30f, 0f, 30f, 60f)) {
            val paint = if (pitch == 0f) horizonPaint else gridPaint
            drawProjectedLoop(canvas, w, h, pose, hfovDeg, vfovDeg, paint, steps = 36) { i ->
                val az = i * (360f / 36f)
                SphereMath.directionFromYawPitchDeg(az, pitch)
            }
        }

        // Draw landmarks so every direction in the simulated room looks visually distinct
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (w * 0.032f).coerceAtLeast(18f)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        for (lm in landmarks) {
            val proj = SphereMath.projectDirectionToScreen(lm.dir, pose, hfovDeg, vfovDeg, w, h)
            if (proj.isInFront && proj.x in -w * 0.3f..w * 1.3f && proj.y in -h * 0.3f..h * 1.3f) {
                val radiusPx = (w * (lm.radiusDeg / hfovDeg) * 0.6f).coerceIn(28f, w * 0.25f)
                badgePaint.color = Color.argb(95, Color.red(lm.color), Color.green(lm.color), Color.blue(lm.color))
                canvas.drawCircle(proj.x, proj.y, radiusPx, badgePaint)

                badgePaint.style = Paint.Style.STROKE
                badgePaint.strokeWidth = 3f
                badgePaint.color = lm.color
                canvas.drawCircle(proj.x, proj.y, radiusPx, badgePaint)
                badgePaint.style = Paint.Style.FILL

                canvas.drawText(lm.label, proj.x, proj.y + labelPaint.textSize * 0.35f, labelPaint)
            }
        }
    }

    private fun drawRoomBands(
        canvas: Canvas,
        w: Float,
        h: Float,
        pose: CameraPose,
        hfovDeg: Float,
        vfovDeg: Float,
        paint: Paint
    ) {
        // Draw coarse sky/ceiling vs wall vs floor hemisphere quads along horizon
        val segments = 24
        for (i in 0 until segments) {
            val az0 = i * (360f / segments)
            val az1 = (i + 1) * (360f / segments)

            // Wall strip (-25° to +25°)
            val wallHue = if ((i / 6) % 2 == 0) Color.rgb(30, 41, 59) else Color.rgb(36, 49, 71)
            drawSphereQuad(canvas, w, h, pose, hfovDeg, vfovDeg, az0, az1, -25f, 35f, wallHue, paint)

            // Upper wall / ceiling strip (+35° to +89°)
            drawSphereQuad(canvas, w, h, pose, hfovDeg, vfovDeg, az0, az1, 35f, 89f, Color.rgb(22, 32, 48), paint)

            // Floor strip (-80° to -25°)
            val floorCol = if (i % 2 == 0) Color.rgb(42, 34, 28) else Color.rgb(50, 40, 32)
            drawSphereQuad(canvas, w, h, pose, hfovDeg, vfovDeg, az0, az1, -80f, -25f, floorCol, paint)
        }
    }

    private fun drawSphereQuad(
        canvas: Canvas,
        w: Float,
        h: Float,
        pose: CameraPose,
        hfovDeg: Float,
        vfovDeg: Float,
        az0: Float,
        az1: Float,
        p0: Float,
        p1: Float,
        color: Int,
        paint: Paint
    ) {
        val pts = listOf(
            SphereMath.projectDirectionToScreen(SphereMath.directionFromYawPitchDeg(az0, p0), pose, hfovDeg, vfovDeg, w, h),
            SphereMath.projectDirectionToScreen(SphereMath.directionFromYawPitchDeg(az1, p0), pose, hfovDeg, vfovDeg, w, h),
            SphereMath.projectDirectionToScreen(SphereMath.directionFromYawPitchDeg(az1, p1), pose, hfovDeg, vfovDeg, w, h),
            SphereMath.projectDirectionToScreen(SphereMath.directionFromYawPitchDeg(az0, p1), pose, hfovDeg, vfovDeg, w, h)
        )
        if (pts.count { it.isInFront } < 3) return
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            lineTo(pts[1].x, pts[1].y)
            lineTo(pts[2].x, pts[2].y)
            lineTo(pts[3].x, pts[3].y)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private fun drawProjectedPolyline(
        canvas: Canvas,
        w: Float,
        h: Float,
        pose: CameraPose,
        hfovDeg: Float,
        vfovDeg: Float,
        paint: Paint,
        pointAt: (Int) -> Vec3
    ) {
        var prev: com.example.domain.math.ProjectedPoint? = null
        for (s in 0..10) {
            val curr = SphereMath.projectDirectionToScreen(pointAt(s), pose, hfovDeg, vfovDeg, w, h)
            val p = prev
            if (p != null && p.isInFront && curr.isInFront) {
                canvas.drawLine(p.x, p.y, curr.x, curr.y, paint)
            }
            prev = curr
        }
    }

    private fun drawProjectedLoop(
        canvas: Canvas,
        w: Float,
        h: Float,
        pose: CameraPose,
        hfovDeg: Float,
        vfovDeg: Float,
        paint: Paint,
        steps: Int,
        pointAt: (Int) -> Vec3
    ) {
        var prev = SphereMath.projectDirectionToScreen(pointAt(0), pose, hfovDeg, vfovDeg, w, h)
        for (i in 1..steps) {
            val curr = SphereMath.projectDirectionToScreen(pointAt(i % steps), pose, hfovDeg, vfovDeg, w, h)
            if (prev.isInFront && curr.isInFront) {
                canvas.drawLine(prev.x, prev.y, curr.x, curr.y, paint)
            }
            prev = curr
        }
    }

    /**
     * Generates a real 1200x1600 portrait JPEG photo of the simulated room at [pose],
     * saves a thumbnail, and writes standard EXIF tags so the export pipeline produces
     * genuine stitchable/inspectable JPEG files.
     */
    fun captureSimulatedPhoto(
        photoFile: File,
        thumbFile: File,
        pose: CameraPose,
        hfovDeg: Float,
        vfovDeg: Float,
        focalLengthMm: Float = 4.2f
    ): CapturedImageResult {
        val width = 1200
        val height = 1600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        renderRoomToCanvas(canvas, width, height, pose, hfovDeg, vfovDeg)

        // Subtle watermark stamp in corner for debug provenance
        val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 226, 232, 240)
            textSize = 26f
        }
        canvas.drawText(
            String.format(
                java.util.Locale.US,
                "SIMULATED  yaw %.1f°  pitch %.1f°  roll %.1f°",
                pose.yawDeg, pose.pitchDeg, pose.rollDeg
            ),
            36f,
            height - 40f,
            stampPaint
        )

        photoFile.parentFile?.mkdirs()
        FileOutputStream(photoFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        val thumb = Bitmap.createScaledBitmap(bitmap, 180, 240, true)
        thumbFile.parentFile?.mkdirs()
        FileOutputStream(thumbFile).use { out ->
            thumb.compress(Bitmap.CompressFormat.JPEG, 78, out)
        }
        thumb.recycle()
        bitmap.recycle()

        val capturedAtMs = System.currentTimeMillis()
        ExifPoseWriter.writeMetadata(
            jpegFile = photoFile,
            pose = pose,
            focalLengthMm = focalLengthMm,
            equivalent35mmFocalLength = 26,
            imageWidth = width,
            imageHeight = height,
            capturedAtMs = capturedAtMs,
            orientation = ExifInterface.ORIENTATION_NORMAL
        )

        return CapturedImageResult(
            photoFile = photoFile,
            thumbFile = thumbFile,
            width = width,
            height = height,
            capturedAtEpochMs = capturedAtMs,
            poseAtExposure = pose
        )
    }
}
