package com.example

import android.content.Context
import androidx.core.content.FileProvider
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Before
    fun clearFileProviderStaticCache() {
        runCatching {
            val field = FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

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

    @Test
    fun `on-device panorama stitcher produces 2to1 equirectangular jpeg with gpano metadata`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.filesDir, "test_stitch_scene").apply {
            deleteRecursively()
            mkdirs()
        }

        val hfov = 74f
        val vfov = 92f
        val plan = com.example.domain.capture.TargetPlanner.computeCoveragePlan(
            hfovDeg = hfov,
            vfovDeg = vfov,
            includeCeiling = true
        )
        // Capture a representative subset across rings (eye + upper + ceiling) for speed in JVM test
        val subset = plan.filter { it.ringType == RingType.EYE_LEVEL || it.ringType == RingType.CEILING }
        val entities = subset.mapIndexed { idx, p ->
            val pose = CameraPose.fromQuaternion(
                Quaternion.fromYawPitchRollDeg(p.targetYawDeg, p.targetPitchDeg, 0f),
                timestampNanos = (idx + 1) * 100_000_000L
            )
            val photoFile = File(tempDir, "target_${p.id}.jpg")
            val thumbFile = File(tempDir, "thumb_${p.id}.jpg")
            val cap = SimulatedCameraEngine.captureSimulatedPhoto(
                photoFile = photoFile,
                thumbFile = thumbFile,
                pose = pose,
                hfovDeg = hfov,
                vfovDeg = vfov,
                focalLengthMm = 2.2f
            )
            TargetEntity(
                sceneId = "stitch_scene_1",
                id = p.id,
                orderIndex = p.orderIndex,
                ringType = p.ringType.name,
                targetYawDeg = p.targetYawDeg,
                targetPitchDeg = p.targetPitchDeg,
                targetDirX = p.targetDir.x,
                targetDirY = p.targetDir.y,
                targetDirZ = p.targetDir.z,
                captured = true,
                photoFilePath = cap.photoFile.absolutePath,
                thumbFilePath = cap.thumbFile.absolutePath,
                captureOrder = idx + 1,
                capturedAtMs = cap.capturedAtEpochMs,
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
                imageWidth = cap.width,
                imageHeight = cap.height
            )
        }

        val scene = SceneEntity(
            id = "stitch_scene_1",
            name = "Stitch Hall",
            createdAtMs = 1_700_000_000_000L,
            updatedAtMs = 1_700_000_020_000L,
            hfovDeg = hfov,
            vfovDeg = vfov,
            overlapTarget = 0.275f,
            focalLengthMm = 2.2f,
            lensId = "simulated_ultrawide",
            isUltrawide = true,
            aeLocked = true,
            awbLocked = true,
            sensorSource = "SIMULATED",
            isSimulated = true
        )

        val stitcher = com.example.data.stitch.OnDevicePanoramaStitcher(context)
        val result = stitcher.stitchScene(SceneWithTargets(scene, entities))

        assertTrue(result.localStitchedFile.exists())
        assertTrue(result.width >= 2400)
        assertEquals(result.width, result.height * 2)
        assertEquals(subset.size, result.sourcePhotoCount)

        val stitchedHeader = result.localStitchedFile.readBytes().take(4096).toByteArray().decodeToString()
        assertTrue(stitchedHeader.contains("GPano:UsePanoramaViewer=\"True\""))
        assertTrue(stitchedHeader.contains("GPano:ProjectionType=\"equirectangular\""))
    }
}
