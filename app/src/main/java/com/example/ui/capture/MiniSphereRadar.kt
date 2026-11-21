package com.example.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.domain.capture.CaptureTargetState
import com.example.domain.math.CameraPose
import com.example.ui.theme.AmberCaution
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.MintEmerald
import com.example.ui.theme.OffWhiteText
import com.example.ui.theme.SignalCyan
import com.example.ui.theme.SlateOutline
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Compact 360-degree mini-globe showing overall sphere fill at a glance without numeric progress.
 * Center = ceiling (+90° pitch), middle ring = eye level (0°), outer edge = lower ring.
 * Rotates with current yaw so "up" on the radar always matches where the user is facing.
 * Tapping any dot selects that target for guided capture or retake.
 */
@Composable
fun MiniSphereRadar(
    pose: CameraPose,
    targets: List<CaptureTargetState>,
    activeTargetId: String?,
    onTargetTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(CharcoalSurface.copy(alpha = 0.80f))
            .border(1.5.dp, SlateOutline, CircleShape)
            .semantics { contentDescription = "360 sphere coverage overview" }
            .testTag("mini_sphere_radar")
    ) {
        Canvas(
            modifier = Modifier
                .size(96.dp)
                .pointerInput(targets, pose.yawDeg) {
                    detectTapGestures { tap ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val cx = w * 0.5f
                        val cy = h * 0.5f
                        val maxR = kotlin.math.min(w, h) * 0.44f

                        val hit = targets
                            .map { t ->
                                val pos = projectRadarPoint(t, pose.yawDeg, cx, cy, maxR)
                                t to hypot(pos.x - tap.x, pos.y - tap.y)
                            }
                            .filter { it.second <= 26f }
                            .minByOrNull { it.second }
                            ?.first

                        if (hit != null) {
                            onTargetTap(hit.id)
                        }
                    }
                }
        ) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val maxR = size.minDimension * 0.44f

            // Ring guides (upper, eye-level, lower)
            drawCircle(color = SlateOutline.copy(alpha = 0.5f), radius = maxR * 0.58f, style = Stroke(1.2f))
            drawCircle(color = SlateOutline.copy(alpha = 0.35f), radius = maxR, style = Stroke(1.2f))

            // Draw target dots
            for (t in targets) {
                val p = projectRadarPoint(t, pose.yawDeg, cx, cy, maxR)
                val isActive = t.id == activeTargetId
                when {
                    t.captured -> {
                        drawCircle(
                            color = if (isActive) AmberCaution else MintEmerald,
                            radius = if (isActive) 5.5f else 4.2f,
                            center = p
                        )
                    }
                    isActive -> {
                        drawCircle(color = SignalCyan, radius = 5.5f, center = p)
                    }
                    else -> {
                        drawCircle(
                            color = OffWhiteText.copy(alpha = 0.5f),
                            radius = 3.2f,
                            center = p,
                            style = Stroke(1.4f)
                        )
                    }
                }
            }

            // Current camera aim dot on radar (relative yaw = 0 -> straight up on radar)
            val aimR = maxR * ((90f - pose.pitchDeg) / 155f).coerceIn(0f, 1f)
            val aimPos = Offset(cx, cy - aimR)
            drawCircle(color = SignalCyan.copy(alpha = 0.3f), radius = 10f, center = aimPos)
            drawCircle(color = Color.White, radius = 4f, center = aimPos)
        }
    }
}

private fun projectRadarPoint(
    target: CaptureTargetState,
    cameraYawDeg: Float,
    cx: Float,
    cy: Float,
    maxR: Float
): Offset {
    val relYawRad = Math.toRadians((target.targetYawDeg - cameraYawDeg).toDouble())
    val radialNorm = ((90f - target.targetPitchDeg) / 155f).coerceIn(0f, 1f)
    val r = maxR * radialNorm
    val px = cx + r * sin(relYawRad).toFloat()
    val py = cy - r * cos(relYawRad).toFloat()
    return Offset(px, py)
}
