package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.camera.SimulatedCameraEngine
import com.example.data.export.PackageExporter
import com.example.data.export.PosesDocument
import com.example.data.local.SceneEntity
import com.example.data.local.SceneWithTargets
import com.example.data.local.TargetEntity
import com.example.domain.capture.RingType
import com.example.domain.math.CameraPose
import com.example.domain.math.Quaternion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from resources`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PanoGuide", appName)
    }

    @Test
    fun `simulated capture and package exporter write valid zip with poses json and readme`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.filesDir, "test_export_scene").apply { mkdirs() }
        val photoFile = File(tempDir, "target_ceiling.jpg")
        val thumbFile = File(tempDir, "thumb_ceiling.jpg")

        val pose = CameraPose.fromQuaternion(
            Quaternion.fromYawPitchRollDeg(yawDeg = 30f, pitchDeg = 90f, rollDeg = 0f),
            timestampNanos = 5_000_000L
        )
        val captured = SimulatedCameraEngine.captureSimulatedPhoto(
            photoFile = photoFile,
            thumbFile = thumbFile,
            pose = pose,
            hfovDeg = 54f,
            vfovDeg = 68f
        )
        assertTrue(captured.photoFile.exists())
        assertTrue(captured.thumbFile.exists())

        val scene = SceneEntity(
            id = "scene_1",
            name = "Test Loft",
            createdAtMs = 1_700_000_000_000L,
            updatedAtMs = 1_700_000_010_000L,
            hfovDeg = 54f,
            vfovDeg = 68f,
            overlapTarget = 0.275f,
            focalLengthMm = 4.2f,
            lensId = "simulated_back",
            aeLocked = true,
            awbLocked = true,
            sensorSource = "SIMULATED",
            isSimulated = true
        )
        val target = TargetEntity(
            sceneId = "scene_1",
            id = "ceiling_0",
            orderIndex = 0,
            ringType = RingType.CEILING.name,
            targetYawDeg = 0f,
            targetPitchDeg = 90f,
            targetDirX = 0f,
            targetDirY = 0f,
            targetDirZ = 1f,
            captured = true,
            photoFilePath = photoFile.absolutePath,
            thumbFilePath = thumbFile.absolutePath,
            captureOrder = 1,
            capturedAtMs = captured.capturedAtEpochMs,
            actualDirX = pose.forward.x,
            actualDirY = pose.forward.y,
            actualDirZ = pose.forward.z,
            actualUpX = pose.up.x,
            actualUpY = pose.up.y,
            actualUpZ = pose.up.z,
            quatW = pose.quaternion.w,
            quatX = pose.quaternion.x,
            quatY = pose.quaternion.y,
            quatZ = pose.quaternion.z,
            actualYawDeg = pose.yawDeg,
            actualPitchDeg = pose.pitchDeg,
            actualRollDeg = pose.rollDeg,
            imageWidth = captured.width,
            imageHeight = captured.height
        )

        val exporter = PackageExporter(context)
        val result = exporter.exportScene(SceneWithTargets(scene, listOf(target)))
        assertEquals(1, result.photoCount)

        val cachedZip = File(context.cacheDir, result.zipFileName)
        assertTrue(cachedZip.exists())

        ZipFile(cachedZip).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertTrue(entries.contains("poses.json"))
            assertTrue(entries.contains("README.txt"))
            assertTrue(entries.any { it.startsWith("photos/001_ceiling_") && it.endsWith(".jpg") })

            val posesText = zip.getInputStream(zip.getEntry("poses.json")).bufferedReader().readText()
            val parsed = Json.decodeFromString<PosesDocument>(posesText)
            assertEquals("Test Loft", parsed.scene.name)
            assertEquals(1, parsed.photos.size)
            assertEquals(90f, parsed.photos[0].orientation.eulerDeg.pitch, 0.5f)
            assertEquals(1200, parsed.photos[0].camera.imageWidth)
        }
    }
}
