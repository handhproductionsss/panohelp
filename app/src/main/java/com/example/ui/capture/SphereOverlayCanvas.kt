package com.example.ui.capture

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.domain.capture.AimingEvaluation
import com.example.domain.capture.CaptureTargetState
import com.example.domain.capture.TiltStatus
import com.example.domain.math.CameraPose
import com.example.domain.math.SphereMath
import com.example.ui.theme.AmberCaution
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.MintEmerald
import com.example.ui.theme.OffWhiteText
import com.example.ui.theme.SignalCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders the 3D sphere overlay on top of the live camera preview:
 * - Subtle sphere elevation rings & meridians
 * - Captured photo thumbnails pinned at their 3D target directions
 * - Empty target markers + active/retake target highlight
 * - Central aiming reticle with auto-capture hold progress ring
 * - Horizon spirit-level indicator
 * - Directional chevron pointing toward the active target when off-center
 */
@Composable
fun SphereOverlayCanvas(
    pose: CameraPose,
    targets: List<CaptureTargetState>,
    evaluation: AimingEvaluation,
    hfovDeg: Float,
    vfovDeg: Float,
    onTargetTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val thumbCache = remember { mutableStateMapOf<String, ImageBitmap>() }
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 40.dp.toPx() }

    // Decode/refresh low-res thumbnails off the main thread when targets are captured or retaken
    val cacheSignature = targets.map { "${it.id}:${it.captured}:${it.captureOrder}:${it.photoPath}" }
    LaunchedEffect(cacheSignature) {
        withContext(Dispatchers.IO) {
            for (t in targets) {
                val path = t.photoPath
                if (t.captured && !path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.exists()) {
                        val key = "${t.id}:${file.lastModified()}"
                        if (!thumbCache.containsKey(key)) {
                            // Clear stale version of this target if retaken
                            val staleKeys = thumbCache.keys.filter { it.startsWith("${t.id}:") }
                            val bmp = BitmapFactory.decodeFile(file.absolutePath)
                            if (bmp != null) {
                                withContext(Dispatchers.Main) {
                                    staleKeys.forEach { thumbCache.remove(it) }
                                    thumbCache[key] = bmp.asImageBitmap()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(targets, pose, hfovDeg, vfovDeg) {
                detectTapGestures { tapOffset ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val tapped = targets
                        .mapNotNull { target ->
                            val proj = SphereMath.projectDirectionToScreen(
                                worldDir = target.targetDir,
                                cameraPose = pose,
                                hfovDeg = hfovDeg,
                                vfovDeg = vfovDeg,
                                width = w,
                                height = h
                            )
                            if (proj.isInFront) {
                                val dist = hypot(proj.x - tapOffset.x, proj.y - tapOffset.y)
                                if (dist <= hitRadiusPx) target to dist else null
                            } else null
                        }
                        .minByOrNull { it.second }
                        ?.first

                    if (tapped != null) {
                        onTargetTap(tapped.id)
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f

        // 1. Subtle 3D horizon guide curve on the sphere
        val horizonColor = Color.White.copy(alpha = 0.20f)
        var prevH: com.example.domain.math.ProjectedPoint? = null
        val steps = 48
        for (i in 0..steps) {
            val az = (i * 360f) / steps
            val pt = SphereMath.projectDirectionToScreen(
                SphereMath.directionFromYawPitchDeg(az, 0f),
                pose, hfovDeg, vfovDeg, w, h
            )
            val p = prevH
            if (p != null && p.isInFront && pt.isInFront &&
                hypot(pt.x - p.x, pt.y - p.y) < w * 0.6f
            ) {
                drawLine(color = horizonColor, start = Offset(p.x, p.y), end = Offset(pt.x, pt.y), strokeWidth = 1.5f)
            }
            prevH = pt
        }

        val activeId = evaluation.activeTarget?.id
        val thumbW = (w * 0.22f).coerceIn(110f, 200f)
        val thumbH = thumbW * 1.333f

        // 2. Render captured target thumbnails pinned in 3D sphere space
        for (target in targets) {
            if (!target.captured) continue
            val proj = SphereMath.projectDirectionToScreen(target.targetDir, pose, hfovDeg, vfovDeg, w, h)
            if (!proj.isInFront) continue
            if (proj.x < -thumbW || proj.x > w + thumbW || proj.y < -thumbH || proj.y > h + thumbH) continue

            val fileMod = target.photoPath?.let { File(it).lastModified() } ?: 0L
            val bitmap = thumbCache["${target.id}:$fileMod"]
                ?: thumbCache.entries.firstOrNull { it.key.startsWith("${target.id}:") }?.value

            val isActiveRetake = target.id == activeId
            val rollRelDeg = SphereMath.computeScreenRollRelativeToTarget(target.targetDir, pose)

            rotate(degrees = rollRelDeg, pivot = Offset(proj.x, proj.y)) {
                val topLeft = Offset(proj.x - thumbW * 0.5f, proj.y - thumbH * 0.5f)
                if (bitmap != null) {
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                        dstSize = IntSize(thumbW.roundToInt(), thumbH.roundToInt()),
                        alpha = if (isActiveRetake) 0.55f else 0.86f
                    )
                } else {
                    drawRoundRect(
                        color = MintEmerald.copy(alpha = 0.35f),
                        topLeft = topLeft,
                        size = Size(thumbW, thumbH),
                        cornerRadius = CornerRadius(12f, 12f)
                    )
                }

                drawRoundRect(
                    color = if (isActiveRetake) AmberCaution else MintEmerald.copy(alpha = 0.85f),
                    topLeft = topLeft,
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = if (isActiveRetake) 4f else 2.5f)
                )
            }
        }

        // 3. Render uncaptured target markers (hollow rings)
        for (target in targets) {
            if (target.captured) continue
            val proj = SphereMath.projectDirectionToScreen(target.targetDir, pose, hfovDeg, vfovDeg, w, h)
            if (!proj.isInFront) continue
            if (proj.x < -60f || proj.x > w + 60f || proj.y < -60f || proj.y > h + 60f) continue

            val isActive = target.id == activeId
            val markerRadius = if (isActive) 28f else 18f
            val markerColor = if (isActive) {
                if (evaluation.isAimedAtTarget) MintEmerald else SignalCyan
            } else {
                OffWhiteText.copy(alpha = 0.65f)
            }

            drawCircle(
                color = markerColor.copy(alpha = if (isActive) 0.22f else 0.10f),
                radius = markerRadius,
                center = Offset(proj.x, proj.y)
            )
            drawCircle(
                color = markerColor,
                radius = markerRadius,
                center = Offset(proj.x, proj.y),
                style = Stroke(width = if (isActive) 4f else 2.5f)
            )
            drawCircle(
                color = markerColor,
                radius = if (isActive) 5.5f else 3.5f,
                center = Offset(proj.x, proj.y)
            )
        }

        // 4. Central aiming reticle + auto-capture progress arc
        val reticleRadius = 42f
        val reticleColor = when {
            evaluation.isMovingTooFast -> CoralAlert
            evaluation.tiltStatus != TiltStatus.LEVEL -> AmberCaution
            evaluation.isAimedAtTarget -> MintEmerald
            else -> SignalCyan
        }

        // Outer reticle circle
        drawCircle(
            color = OffWhiteText.copy(alpha = 0.35f),
            radius = reticleRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 2.5f)
        )

        // Auto-hold sweep progress arc
        if (evaluation.autoHoldProgress > 0f) {
            drawArc(
                color = MintEmerald,
                startAngle = -90f,
                sweepAngle = 360f * evaluation.autoHoldProgress,
                useCenter = false,
                topLeft = Offset(cx - reticleRadius - 6f, cy - reticleRadius - 6f),
                size = Size((reticleRadius + 6f) * 2f, (reticleRadius + 6f) * 2f),
                style = Stroke(width = 6f, cap = StrokeCap.Round)
            )
        }

        // Center dot
        drawCircle(
            color = reticleColor,
            radius = 5f,
            center = Offset(cx, cy)
        )

        // 5. Horizon spirit-level wings around the reticle (hidden near zenith)
        if (kotlin.math.abs(pose.pitchDeg) <= 75f) {
            val tiltColor = if (evaluation.tiltStatus == TiltStatus.LEVEL) {
                OffWhiteText.copy(alpha = 0.7f)
            } else {
                AmberCaution
            }
            // Fixed horizontal reference ticks
            drawLine(
                color = OffWhiteText.copy(alpha = 0.35f),
                start = Offset(cx - 72f, cy),
                end = Offset(cx - 52f, cy),
                strokeWidth = 2f
            )
            drawLine(
                color = OffWhiteText.copy(alpha = 0.35f),
                start = Offset(cx + 52f, cy),
                end = Offset(cx + 72f, cy),
                strokeWidth = 2f
            )
            // Live tilted horizon bar
            rotate(degrees = -pose.rollDeg, pivot = Offset(cx, cy)) {
                drawLine(
                    color = tiltColor,
                    start = Offset(cx - 66f, cy),
                    end = Offset(cx - 48f, cy),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = tiltColor,
                    start = Offset(cx + 48f, cy),
                    end = Offset(cx + 66f, cy),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round
                )
            }
        }

        // 6. Directional guide arrow pointing toward active target if not yet centered
        val activeTarget = evaluation.activeTarget
        if (activeTarget != null && !evaluation.isAimedAtTarget) {
            val proj = SphereMath.projectDirectionToScreen(activeTarget.targetDir, pose, hfovDeg, vfovDeg, w, h)
            val dx = proj.x - cx
            val dy = proj.y - cy
            val dist = hypot(dx, dy)
            if (dist > 30f) {
                val angleRad = atan2(dy, dx)
                val arrowDist = reticleRadius + 34f
                val ax = cx + cos(angleRad) * arrowDist
                val ay = cy + sin(angleRad) * arrowDist
                val arrowDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

                rotate(degrees = arrowDeg, pivot = Offset(ax, ay)) {
                    val path = Path().apply {
                        moveTo(ax + 14f, ay)
                        lineTo(ax - 8f, ay - 10f)
                        lineTo(ax - 4f, ay)
                        lineTo(ax - 8f, ay + 10f)
                        close()
                    }
                    drawPath(
                        path = path,
                        color = if (evaluation.isRetakeMode) AmberCaution else SignalCyan
                    )
                }
            }
        }
    }
}
