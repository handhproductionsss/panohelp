package com.example.data.camera

import kotlin.math.atan
import kotlin.math.tan

data class CameraLensCandidate(
    val cameraId: String,
    val isBackFacing: Boolean,
    val baseHfovDeg: Float,
    val baseVfovDeg: Float,
    val focalLengthMm: Float,
    val equivalent35mmFocalLength: Int?,
    val maxMegapixels: Float,
    val minZoomRatio: Float = 1.0f
) {
    /** Effective horizontal FOV when engaging [minZoomRatio] (< 1.0 engages logical ultrawide). */
    val effectiveHfovDeg: Float
        get() {
            val z = minZoomRatio.coerceIn(0.35f, 1.0f)
            return if (z < 0.98f) {
                val halfRad = Math.toRadians((baseHfovDeg * 0.5f).toDouble())
                Math.toDegrees(2.0 * atan(tan(halfRad) / z)).toFloat().coerceIn(30f, 130f)
            } else {
                baseHfovDeg
            }
        }

    val effectiveVfovDeg: Float
        get() {
            val z = minZoomRatio.coerceIn(0.35f, 1.0f)
            return if (z < 0.98f) {
                val halfRad = Math.toRadians((baseVfovDeg * 0.5f).toDouble())
                Math.toDegrees(2.0 * atan(tan(halfRad) / z)).toFloat().coerceIn(35f, 140f)
            } else {
                baseVfovDeg
            }
        }
}

data class SelectedLensResult(
    val candidate: CameraLensCandidate,
    val targetZoomRatio: Float,
    val effectiveHfovDeg: Float,
    val effectiveVfovDeg: Float,
    val isUltrawide: Boolean
)

/**
 * Selects the widest usable back camera lens so fewer photos cover the sphere with stronger
 * overlap and more visual features for stitching.
 *
 * Handles both OEM architectures:
 * 1. Standalone physical ultrawide CameraInfo exposed directly in CameraX.
 * 2. Logical multi-camera exposing ultrawide via ZoomState.minZoomRatio < 1.0 (e.g. 0.5x / 0.6x).
 */
object WideLensSelector {

    fun selectWidestBackLens(candidates: List<CameraLensCandidate>): SelectedLensResult? {
        val backCandidates = candidates.filter { it.isBackFacing }
        if (backCandidates.isEmpty()) return null

        // Avoid tiny auxiliary depth/macro sensors (< 1.9 MP) when normal cameras exist
        val usable = backCandidates.filter { it.maxMegapixels >= 1.9f }.ifEmpty { backCandidates }

        // Prefer a dedicated physical wide/ultrawide camera if one already has >= 70° portrait HFOV,
        // otherwise pick whichever candidate achieves the widest effective HFOV (including minZoomRatio).
        val standaloneUltrawide = usable
            .filter { it.baseHfovDeg >= 70f }
            .maxByOrNull { it.baseHfovDeg }

        val widestOverall = usable.maxByOrNull { it.effectiveHfovDeg } ?: return null

        val chosen = if (standaloneUltrawide != null &&
            standaloneUltrawide.baseHfovDeg >= widestOverall.effectiveHfovDeg - 4f
        ) {
            standaloneUltrawide
        } else {
            widestOverall
        }

        val useMinZoom = chosen.minZoomRatio < 0.96f && chosen.baseHfovDeg < 70f
        val zoom = if (useMinZoom) chosen.minZoomRatio else 1.0f
        val effH = if (useMinZoom) chosen.effectiveHfovDeg else chosen.baseHfovDeg
        val effV = if (useMinZoom) chosen.effectiveVfovDeg else chosen.baseVfovDeg

        return SelectedLensResult(
            candidate = chosen,
            targetZoomRatio = zoom,
            effectiveHfovDeg = effH,
            effectiveVfovDeg = effV,
            isUltrawide = effH >= 68f
        )
    }
}
