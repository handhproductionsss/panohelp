package com.example.data.stitch

import com.example.domain.math.Quaternion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class StitchFrameInput(
    val id: String,
    val quaternion: Quaternion,
    val hfovDeg: Float,
    val vfovDeg: Float,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    var gain: Float = 1.0f,
    var yawOffsetDeg: Float = 0f,
    var pitchOffsetDeg: Float = 0f
) {
    val halfTanH: Float = tan(Math.toRadians((hfovDeg * 0.5f).toDouble())).toFloat().coerceAtLeast(0.05f)
    val halfTanV: Float = tan(Math.toRadians((vfovDeg * 0.5f).toDouble())).toFloat().coerceAtLeast(0.05f)

    /**
     * Precomputed world-to-camera 3x3 row-major rotation matrix including optional
     * small-angle alignment offsets.
     */
    fun buildWorldToCamMatrix(): FloatArray {
        val qYaw = Quaternion.axisAngle(
            com.example.domain.math.Vec3(0f, 0f, 1f),
            Math.toRadians((-yawOffsetDeg).toDouble()).toFloat()
        )
        val qPitch = Quaternion.axisAngle(
            com.example.domain.math.Vec3(1f, 0f, 0f),
            Math.toRadians(pitchOffsetDeg.toDouble()).toFloat()
        )
        val adjusted = (qYaw * quaternion * qPitch).normalized()
        // World-to-camera is conjugate(adjusted).toRotationMatrix(); compute via 3 basis columns
        val inv = adjusted.conjugate()
        val c0 = inv.rotateVector(com.example.domain.math.Vec3(1f, 0f, 0f))
        val c1 = inv.rotateVector(com.example.domain.math.Vec3(0f, 1f, 0f))
        val c2 = inv.rotateVector(com.example.domain.math.Vec3(0f, 0f, 1f))
        return floatArrayOf(
            c0.x, c1.x, c2.x,
            c0.y, c1.y, c2.y,
            c0.z, c1.z, c2.z
        )
    }
}

/**
 * Pure-Kotlin spherical projection, small-angle overlap refinement, gain compensation,
 * and bilinear feathered equirectangular blending kernel.
 */
object EquirectangularMath {

    /**
     * Converts equirectangular pixel `(u, v)` in an image of size `(outW, outH)`
     * to a world unit direction vector `(wx, wy, wz)`.
     * Center column (`u = outW / 2`) corresponds to `yaw = 0°` (`+Y` world forward).
     */
    fun equirectPixelToWorldDir(u: Int, v: Int, outW: Int, outH: Int): FloatArray {
        val yaw = ((u + 0.5f) / outW - 0.5f) * (2.0 * PI)
        val pitch = (0.5f - (v + 0.5f) / outH) * PI
        val cosP = cos(pitch).toFloat()
        return floatArrayOf(
            (cosP * sin(yaw)).toFloat(),
            (cosP * cos(yaw)).toFloat(),
            sin(pitch).toFloat()
        )
    }

    /**
     * Fast low-res alignment and exposure gain balancing across overlapping frames.
     * Refines each frame's `yawOffsetDeg`, `pitchOffsetDeg` within ±2.4° and `gain` within [0.75, 1.35].
     */
    fun refineOverlapsAndExposure(frames: List<StitchFrameInput>) {
        if (frames.size < 2) return

        val refW = 240
        val refH = 120
        val total = refW * refH

        // Precompute direction vectors for tiny reference grid
        val dirsX = FloatArray(total)
        val dirsY = FloatArray(total)
        val dirsZ = FloatArray(total)
        for (v in 0 until refH) {
            val pitch = (0.5f - (v + 0.5f) / refH) * PI
            val cosP = cos(pitch).toFloat()
            val sinP = sin(pitch).toFloat()
            val rowOff = v * refW
            for (u in 0 until refW) {
                val yaw = ((u + 0.5f) / refW - 0.5f) * (2.0 * PI)
                dirsX[rowOff + u] = (cosP * sin(yaw)).toFloat()
                dirsY[rowOff + u] = (cosP * cos(yaw)).toFloat()
                dirsZ[rowOff + u] = sinP
            }
        }

        // 1. Estimate per-frame brightness in overlap regions for smooth gain compensation
        val frameLumaAccum = FloatArray(total)
        val frameWeightAccum = FloatArray(total)
        val frameSamples = Array(frames.size) { FloatArray(total) { -1f } }

        for (i in frames.indices) {
            val f = frames[i]
            val m = f.buildWorldToCamMatrix()
            val samples = frameSamples[i]
            for (idx in 0 until total) {
                val dx = dirsX[idx]; val dy = dirsY[idx]; val dz = dirsZ[idx]
                val cz = m[6] * dx + m[7] * dy + m[8] * dz
                val forwardDepth = -cz
                if (forwardDepth <= 0.08f) continue
                val cx = m[0] * dx + m[1] * dy + m[2] * dz
                val cy = m[3] * dx + m[4] * dy + m[5] * dz
                val nx = (cx / forwardDepth) / f.halfTanH
                val ny = -(cy / forwardDepth) / f.halfTanV
                if (abs(nx) <= 0.96f && abs(ny) <= 0.96f) {
                    val sx = (((nx + 1f) * 0.5f) * (f.width - 1)).toInt().coerceIn(0, f.width - 1)
                    val sy = (((ny + 1f) * 0.5f) * (f.height - 1)).toInt().coerceIn(0, f.height - 1)
                    val argb = f.pixels[sy * f.width + sx]
                    val luma = (0.299f * ((argb ushr 16) and 0xFF) +
                        0.587f * ((argb ushr 8) and 0xFF) +
                        0.114f * (argb and 0xFF)).coerceAtLeast(1f)
                    samples[idx] = luma
                    val w = (1f - abs(nx)) * (1f - abs(ny))
                    frameLumaAccum[idx] += luma * w
                    frameWeightAccum[idx] += w
                }
            }
        }

        // Solve gentle gain ratios relative to mean overlap luma
        val rawGains = FloatArray(frames.size) { 1.0f }
        for (i in frames.indices) {
            val samples = frameSamples[i]
            var ratioSum = 0f
            var weightSum = 0f
            for (idx in 0 until total) {
                val lumaI = samples[idx]
                val wTot = frameWeightAccum[idx]
                if (lumaI > 8f && wTot > 0.05f) {
                    val avgLuma = frameLumaAccum[idx] / wTot
                    if (avgLuma > 8f) {
                        ratioSum += (avgLuma / lumaI).coerceIn(0.65f, 1.5f)
                        weightSum += 1f
                    }
                }
            }
            if (weightSum >= 20f) {
                rawGains[i] = (ratioSum / weightSum).coerceIn(0.78f, 1.30f)
            }
        }
        val meanGain = rawGains.average().toFloat().coerceAtLeast(0.5f)
        for (i in frames.indices) {
            frames[i].gain = (rawGains[i] / meanGain).coerceIn(0.78f, 1.30f)
        }

        // 2. Small-angle pose refinement against already-placed reference canvas
        val refCanvas = FloatArray(total) { -1f }
        val refWeights = FloatArray(total)

        // Seed reference canvas with frame 0
        splatLuma(frames[0], dirsX, dirsY, dirsZ, refCanvas, refWeights)

        val offsets = floatArrayOf(-2.0f, -1.0f, 0f, 1.0f, 2.0f)
        for (i in 1 until frames.size) {
            val f = frames[i]
            var bestYaw = 0f
            var bestPitch = 0f
            var bestScore = Float.MAX_VALUE

            // Measure baseline error at (0, 0) first
            val baseErr = evaluateOverlapSad(f, 0f, 0f, dirsX, dirsY, dirsZ, refCanvas)
            if (baseErr != null) {
                bestScore = baseErr
                for (dyaw in offsets) {
                    for (dpitch in offsets) {
                        if (dyaw == 0f && dpitch == 0f) continue
                        val err = evaluateOverlapSad(f, dyaw, dpitch, dirsX, dirsY, dirsZ, refCanvas)
                        if (err != null && err < bestScore * 0.94f) {
                            bestScore = err
                            bestYaw = dyaw
                            bestPitch = dpitch
                        }
                    }
                }
            }
            f.yawOffsetDeg = bestYaw
            f.pitchOffsetDeg = bestPitch
            splatLuma(f, dirsX, dirsY, dirsZ, refCanvas, refWeights)
        }
    }

    private fun evaluateOverlapSad(
        f: StitchFrameInput,
        dYaw: Float,
        dPitch: Float,
        dirsX: FloatArray,
        dirsY: FloatArray,
        dirsZ: FloatArray,
        refCanvas: FloatArray
    ): Float? {
        val oldYaw = f.yawOffsetDeg
        val oldPitch = f.pitchOffsetDeg
        f.yawOffsetDeg = dYaw
        f.pitchOffsetDeg = dPitch
        val m = f.buildWorldToCamMatrix()
        f.yawOffsetDeg = oldYaw
        f.pitchOffsetDeg = oldPitch

        var sad = 0f
        var count = 0
        for (idx in dirsX.indices) {
            val refL = refCanvas[idx]
            if (refL < 0f) continue
            val dx = dirsX[idx]; val dy = dirsY[idx]; val dz = dirsZ[idx]
            val cz = m[6] * dx + m[7] * dy + m[8] * dz
            val depth = -cz
            if (depth <= 0.08f) continue
            val cx = m[0] * dx + m[1] * dy + m[2] * dz
            val cy = m[3] * dx + m[4] * dy + m[5] * dz
            val nx = (cx / depth) / f.halfTanH
            val ny = -(cy / depth) / f.halfTanV
            if (abs(nx) <= 0.94f && abs(ny) <= 0.94f) {
                val sx = (((nx + 1f) * 0.5f) * (f.width - 1)).toInt().coerceIn(0, f.width - 1)
                val sy = (((ny + 1f) * 0.5f) * (f.height - 1)).toInt().coerceIn(0, f.height - 1)
                val argb = f.pixels[sy * f.width + sx]
                val luma = (0.299f * ((argb ushr 16) and 0xFF) +
                    0.587f * ((argb ushr 8) and 0xFF) +
                    0.114f * (argb and 0xFF)) * f.gain
                sad += abs(luma - refL)
                count++
            }
        }
        return if (count >= 24) sad / count else null
    }

    private fun splatLuma(
        f: StitchFrameInput,
        dirsX: FloatArray,
        dirsY: FloatArray,
        dirsZ: FloatArray,
        refCanvas: FloatArray,
        refWeights: FloatArray
    ) {
        val m = f.buildWorldToCamMatrix()
        for (idx in dirsX.indices) {
            val dx = dirsX[idx]; val dy = dirsY[idx]; val dz = dirsZ[idx]
            val cz = m[6] * dx + m[7] * dy + m[8] * dz
            val depth = -cz
            if (depth <= 0.08f) continue
            val cx = m[0] * dx + m[1] * dy + m[2] * dz
            val cy = m[3] * dx + m[4] * dy + m[5] * dz
            val nx = (cx / depth) / f.halfTanH
            val ny = -(cy / depth) / f.halfTanV
            if (abs(nx) <= 0.98f && abs(ny) <= 0.98f) {
                val sx = (((nx + 1f) * 0.5f) * (f.width - 1)).toInt().coerceIn(0, f.width - 1)
                val sy = (((ny + 1f) * 0.5f) * (f.height - 1)).toInt().coerceIn(0, f.height - 1)
                val argb = f.pixels[sy * f.width + sx]
                val luma = (0.299f * ((argb ushr 16) and 0xFF) +
                    0.587f * ((argb ushr 8) and 0xFF) +
                    0.114f * (argb and 0xFF)) * f.gain
                val w = ((1f - abs(nx)) * (1f - abs(ny))).coerceAtLeast(1e-4f)
                val prevW = refWeights[idx]
                val newW = prevW + w
                val prevVal = if (refCanvas[idx] < 0f) 0f else refCanvas[idx]
                refCanvas[idx] = (prevVal * prevW + luma * w) / newW
                refWeights[idx] = newW
            }
        }
    }

    /**
     * Renders rows `[yStart, yEnd)` of an equirectangular panorama `(outW x outH)` into [outRowPixels].
     * Uses bilinear sampling, gain compensation, and smooth center-weighted feathering.
     */
    fun renderStrip(
        frames: List<StitchFrameInput>,
        matrices: Array<FloatArray>,
        outW: Int,
        outH: Int,
        yStart: Int,
        yEnd: Int,
        outStripPixels: IntArray
    ) {
        val stripRows = yEnd - yStart
        val stripSize = outW * stripRows
        val accR = FloatArray(stripSize)
        val accG = FloatArray(stripSize)
        val accB = FloatArray(stripSize)
        val accW = FloatArray(stripSize)

        // Precompute cos/sin yaw tables across outW once per strip
        val cosYaw = FloatArray(outW)
        val sinYaw = FloatArray(outW)
        for (u in 0 until outW) {
            val yaw = ((u + 0.5f) / outW - 0.5f) * (2.0 * PI)
            cosYaw[u] = cos(yaw).toFloat()
            sinYaw[u] = sin(yaw).toFloat()
        }

        val frameCount = frames.size
        for (r in 0 until stripRows) {
            val v = yStart + r
            val pitch = (0.5f - (v + 0.5f) / outH) * PI
            val cosP = cos(pitch).toFloat()
            val sinP = sin(pitch).toFloat()
            val rowBase = r * outW

            for (u in 0 until outW) {
                val wx = cosP * sinYaw[u]
                val wy = cosP * cosYaw[u]
                val wz = sinP
                val outIdx = rowBase + u

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumW = 0f

                for (fi in 0 until frameCount) {
                    val m = matrices[fi]
                    val cz = m[6] * wx + m[7] * wy + m[8] * wz
                    val depth = -cz
                    if (depth <= 0.08f) continue

                    val frame = frames[fi]
                    val cx = m[0] * wx + m[1] * wy + m[2] * wz
                    val nx = (cx / depth) / frame.halfTanH
                    if (abs(nx) >= 1f) continue

                    val cy = m[3] * wx + m[4] * wy + m[5] * wz
                    val ny = -(cy / depth) / frame.halfTanV
                    if (abs(ny) >= 1f) continue

                    // Bilinear sample from frame pixels
                    val fx = ((nx + 1f) * 0.5f) * (frame.width - 1)
                    val fy = ((ny + 1f) * 0.5f) * (frame.height - 1)
                    val x0 = fx.toInt().coerceIn(0, frame.width - 2)
                    val y0 = fy.toInt().coerceIn(0, frame.height - 2)
                    val tx = (fx - x0).coerceIn(0f, 1f)
                    val ty = (fy - y0).coerceIn(0f, 1f)

                    val row0 = y0 * frame.width + x0
                    val row1 = row0 + frame.width
                    val p00 = frame.pixels[row0]
                    val p10 = frame.pixels[row0 + 1]
                    val p01 = frame.pixels[row1]
                    val p11 = frame.pixels[row1 + 1]

                    val w00 = (1f - tx) * (1f - ty)
                    val w10 = tx * (1f - ty)
                    val w01 = (1f - tx) * ty
                    val w11 = tx * ty

                    val gain = frame.gain
                    val rSample = (((p00 ushr 16) and 0xFF) * w00 +
                        ((p10 ushr 16) and 0xFF) * w10 +
                        ((p01 ushr 16) and 0xFF) * w01 +
                        ((p11 ushr 16) and 0xFF) * w11) * gain
                    val gSample = (((p00 ushr 8) and 0xFF) * w00 +
                        ((p10 ushr 8) and 0xFF) * w10 +
                        ((p01 ushr 8) and 0xFF) * w01 +
                        ((p11 ushr 8) and 0xFF) * w11) * gain
                    val bSample = ((p00 and 0xFF) * w00 +
                        (p10 and 0xFF) * w10 +
                        (p01 and 0xFF) * w01 +
                        (p11 and 0xFF) * w11) * gain

                    // Smooth squared edge feathering + gentle center preference
                    val edgeWeight = (1f - abs(nx)) * (1f - abs(ny))
                    val centerPref = (1.15f - 0.35f * (nx * nx + ny * ny)).coerceAtLeast(0.15f)
                    val weight = edgeWeight * edgeWeight * centerPref

                    sumR += rSample * weight
                    sumG += gSample * weight
                    sumB += bSample * weight
                    sumW += weight
                }

                accR[outIdx] = sumR
                accG[outIdx] = sumG
                accB[outIdx] = sumB
                accW[outIdx] = sumW
            }
        }

        // Fill final ARGB strip; smoothly blend any uncaptured nadir/gap pixels vertically
        for (r in 0 until stripRows) {
            val rowBase = r * outW
            for (u in 0 until outW) {
                val idx = rowBase + u
                val w = accW[idx]
                if (w > 1e-5f) {
                    val invW = 1f / w
                    val red = (accR[idx] * invW).toInt().coerceIn(0, 255)
                    val grn = (accG[idx] * invW).toInt().coerceIn(0, 255)
                    val blu = (accB[idx] * invW).toInt().coerceIn(0, 255)
                    outStripPixels[idx] = (0xFF shl 24) or (red shl 16) or (grn shl 8) or blu
                } else {
                    // Subtle neutral fill for uncaptured floor nadir so 360° viewers look clean
                    outStripPixels[idx] = 0xFF141922.toInt()
                }
            }
        }
    }
}
