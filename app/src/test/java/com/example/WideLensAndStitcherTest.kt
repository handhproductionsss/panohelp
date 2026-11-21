package com.example

import com.example.data.camera.CameraLensCandidate
import com.example.data.camera.WideLensSelector
import com.example.data.stitch.EquirectangularMath
import com.example.data.stitch.GPanoXmpWriter
import com.example.data.stitch.StitchFrameInput
import com.example.domain.math.Quaternion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile

class WideLensAndStitcherTest {

    @Test
    fun `wide lens selector picks standalone physical ultrawide when available`() {
        val mainCam = CameraLensCandidate(
            cameraId = "0",
            isBackFacing = true,
            baseHfovDeg = 54f,
            baseVfovDeg = 68f,
            focalLengthMm = 4.2f,
            equivalent35mmFocalLength = 26,
            maxMegapixels = 50f,
            minZoomRatio = 1.0f
        )
        val ultrawideCam = CameraLensCandidate(
            cameraId = "2",
            isBackFacing = true,
            baseHfovDeg = 82f,
            baseVfovDeg = 100f,
            focalLengthMm = 1.8f,
            equivalent35mmFocalLength = 13,
            maxMegapixels = 12f,
            minZoomRatio = 1.0f
        )
        val macroTinyCam = CameraLensCandidate(
            cameraId = "5",
            isBackFacing = true,
            baseHfovDeg = 110f,
            baseVfovDeg = 120f,
            focalLengthMm = 1.1f,
            equivalent35mmFocalLength = 11,
            maxMegapixels = 1.2f, // < 1.9 MP auxiliary sensor must be ignored
            minZoomRatio = 1.0f
        )
        val frontCam = CameraLensCandidate(
            cameraId = "1",
            isBackFacing = false,
            baseHfovDeg = 90f,
            baseVfovDeg = 105f,
            focalLengthMm = 2.0f,
            equivalent35mmFocalLength = 15,
            maxMegapixels = 16f,
            minZoomRatio = 1.0f
        )

        val selected = WideLensSelector.selectWidestBackLens(
            listOf(mainCam, ultrawideCam, macroTinyCam, frontCam)
        )
        assertNotNull(selected)
        assertEquals("2", selected!!.candidate.cameraId)
        assertEquals(1.0f, selected.targetZoomRatio, 0.001f)
        assertTrue(selected.isUltrawide)
        assertEquals(82f, selected.effectiveHfovDeg, 0.1f)
    }

    @Test
    fun `wide lens selector engages logical minZoomRatio below 1x when no standalone ultrawide exists`() {
        val logicalBack = CameraLensCandidate(
            cameraId = "0",
            isBackFacing = true,
            baseHfovDeg = 55f,
            baseVfovDeg = 69f,
            focalLengthMm = 4.3f,
            equivalent35mmFocalLength = 25,
            maxMegapixels = 48f,
            minZoomRatio = 0.55f
        )
        val selected = WideLensSelector.selectWidestBackLens(listOf(logicalBack))
        assertNotNull(selected)
        assertEquals("0", selected!!.candidate.cameraId)
        assertEquals(0.55f, selected.targetZoomRatio, 0.001f)
        assertTrue(selected.isUltrawide)
        assertTrue(selected.effectiveHfovDeg > 80f)
        assertTrue(selected.effectiveVfovDeg > 90f)
    }

    @Test
    fun `wide lens selector marks standard lens as non-ultrawide when minZoom is 1x`() {
        val standardOnly = CameraLensCandidate(
            cameraId = "0",
            isBackFacing = true,
            baseHfovDeg = 54f,
            baseVfovDeg = 68f,
            focalLengthMm = 4.2f,
            equivalent35mmFocalLength = 26,
            maxMegapixels = 12f,
            minZoomRatio = 1.0f
        )
        val selected = WideLensSelector.selectWidestBackLens(listOf(standardOnly))
        assertNotNull(selected)
        assertFalse(selected!!.isUltrawide)
        assertEquals(1.0f, selected.targetZoomRatio, 0.001f)
    }

    @Test
    fun `equirectangular math balances brightness and blends overlapping frames`() {
        val w = 96
        val h = 128
        fun argb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
        // Frame 0 at yaw=0, dimmer blue-tinted brightness
        val px0 = IntArray(w * h) { argb(60, 100, 140) }
        // Frame 1 at yaw=35 deg, brighter overlapping shot
        val px1 = IntArray(w * h) { argb(100, 150, 210) }

        val f0 = StitchFrameInput(
            id = "eye_0",
            quaternion = Quaternion.fromYawPitchRollDeg(0f, 0f, 0f),
            hfovDeg = 74f,
            vfovDeg = 92f,
            width = w,
            height = h,
            pixels = px0
        )
        val f1 = StitchFrameInput(
            id = "eye_1",
            quaternion = Quaternion.fromYawPitchRollDeg(35f, 0f, 0f),
            hfovDeg = 74f,
            vfovDeg = 92f,
            width = w,
            height = h,
            pixels = px1
        )

        val frames = listOf(f0, f1)
        EquirectangularMath.refineOverlapsAndExposure(frames)

        // Dimmer frame gets slightly boosted gain, brighter frame slightly reduced
        assertTrue(f0.gain > 1.0f)
        assertTrue(f1.gain < 1.0f)

        val matrices = Array(frames.size) { i -> frames[i].buildWorldToCamMatrix() }
        val outW = 240
        val outH = 120
        val strip = IntArray(outW * 20)
        EquirectangularMath.renderStrip(
            frames = frames,
            matrices = matrices,
            outW = outW,
            outH = outH,
            yStart = 50, // horizon rows
            yEnd = 70,
            outStripPixels = strip
        )

        // Center horizon pixel (yaw=0 -> u = outW/2) must be filled with blended bright cyan/blue, not empty dark fill
        val centerArgb = strip[10 * outW + (outW / 2)]
        val blue = centerArgb and 0xFF
        val red = (centerArgb ushr 16) and 0xFF
        assertTrue("Expected bright blended blue channel, got $blue", blue > 120)
        assertTrue("Expected balanced red channel, got $red", red in 60..140)
    }

    @Test
    fun `gpano xmp writer emits valid equirectangular xmp and injects app1 into jpeg`() {
        val payload = GPanoXmpWriter.buildGPanoXmpPayload(width = 3072, height = 1536)
        val text = payload.decodeToString()
        assertTrue(text.contains("http://ns.adobe.com/xap/1.0/"))
        assertTrue(text.contains("GPano:UsePanoramaViewer=\"True\""))
        assertTrue(text.contains("GPano:ProjectionType=\"equirectangular\""))
        assertTrue(text.contains("GPano:FullPanoWidthPixels=\"3072\""))
        assertTrue(text.contains("GPano:FullPanoHeightPixels=\"1536\""))

        val tempJpg: File = createTempFile(prefix = "test_pano_", suffix = ".jpg").toFile()
        try {
            // Minimal valid JPEG SOI + EOI
            tempJpg.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            GPanoXmpWriter.injectIntoJpeg(tempJpg, 3072, 1536)
            val result = tempJpg.readBytes()
            assertEquals(0xFF.toByte(), result[0])
            assertEquals(0xD8.toByte(), result[1])
            assertEquals(0xFF.toByte(), result[2])
            assertEquals(0xE1.toByte(), result[3])
            val injectedStr = result.decodeToString()
            assertTrue(injectedStr.contains("GPano:ProjectionType=\"equirectangular\""))
        } finally {
            tempJpg.delete()
        }
    }
}
