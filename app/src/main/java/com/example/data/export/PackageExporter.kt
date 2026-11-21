package com.example.data.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.local.SceneWithTargets
import com.example.domain.capture.RingType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class PosesDocument(
    val formatVersion: String = "1.0",
    val scene: SceneExportMetadata,
    val photos: List<PhotoPoseMetadata>
)

@Serializable
data class SceneExportMetadata(
    val id: String,
    val name: String,
    val createdAtIso: String,
    val exportedAtIso: String,
    val settings: SceneExportSettings,
    val readme: String = "README.txt"
)

@Serializable
data class SceneExportSettings(
    val targetOverlapRatio: Float,
    val portraitOnly: Boolean = true,
    val lensId: String,
    val isUltrawide: Boolean = false,
    val focalLengthMm: Float,
    val hfovDeg: Float,
    val vfovDeg: Float,
    val aeLocked: Boolean,
    val awbLocked: Boolean,
    val sensorSource: String,
    val coordinateConvention: String = "right_handed_world_z_up_camera_negative_z_forward"
)

@Serializable
data class PhotoPoseMetadata(
    val captureIndex: Int,
    val filename: String,
    val targetId: String,
    val ring: String,
    val capturedAtIso: String,
    val capturedAtEpochMs: Long,
    val orientation: OrientationMetadata,
    val camera: PhotoCameraMetadata
)

@Serializable
data class OrientationMetadata(
    val quaternion: QuaternionDto,
    val forwardVector: List<Float>,
    val upVector: List<Float>,
    val eulerDeg: EulerDto
)

@Serializable
data class QuaternionDto(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float
)

@Serializable
data class EulerDto(
    val yaw: Float,
    val pitch: Float,
    val roll: Float
)

@Serializable
data class PhotoCameraMetadata(
    val lensId: String,
    val focalLengthMm: Float,
    val hfovDeg: Float,
    val vfovDeg: Float,
    val imageWidth: Int,
    val imageHeight: Int
)

data class ExportResult(
    val shareUri: Uri,
    val downloadsUriString: String,
    val zipFileName: String,
    val photoCount: Int
)

/**
 * Packages the latest photo per captured target in capture order alongside `poses.json`
 * and `README.txt` into a single `.zip` archive saved to `Downloads/PanoGuide/`.
 */
class PackageExporter(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun exportScene(sceneWithTargets: SceneWithTargets): ExportResult = withContext(Dispatchers.IO) {
        val scene = sceneWithTargets.scene
        val capturedTargets = sceneWithTargets.targets
            .filter { it.captured && !it.photoFilePath.isNullOrBlank() && File(it.photoFilePath).exists() }
            .sortedWith(compareBy({ it.captureOrder ?: Int.MAX_VALUE }, { it.capturedAtMs ?: Long.MAX_VALUE }))

        if (capturedTargets.isEmpty()) {
            throw IllegalStateException("No captured photos to export.")
        }

        val nowMs = System.currentTimeMillis()
        val slug = scene.name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "scene" }
            .take(32)
        val tsStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        val zipFileName = "PanoGuide_${slug}_${tsStamp}.zip"

        // Build poses.json entries
        val photoMetadataList = mutableListOf<PhotoPoseMetadata>()
        val photoEntryPairs = mutableListOf<Pair<String, File>>()

        capturedTargets.forEachIndexed { idx, target ->
            val seq = idx + 1
            val ringCode = runCatching { RingType.valueOf(target.ringType).exportCode }
                .getOrDefault("eye")
            val yawInt = ((target.targetYawDeg % 360f + 360f) % 360f).toInt()
            val filename = String.format(Locale.US, "%03d_%s_%03d.jpg", seq, ringCode, yawInt)

            val file = File(target.photoFilePath!!)
            photoEntryPairs += filename to file

            val capturedMs = target.capturedAtMs ?: nowMs
            photoMetadataList += PhotoPoseMetadata(
                captureIndex = seq,
                filename = filename,
                targetId = target.id,
                ring = ringCode,
                capturedAtIso = formatIso8601Millis(capturedMs),
                capturedAtEpochMs = capturedMs,
                orientation = OrientationMetadata(
                    quaternion = QuaternionDto(
                        w = target.quatW ?: 1f,
                        x = target.quatX ?: 0f,
                        y = target.quatY ?: 0f,
                        z = target.quatZ ?: 0f
                    ),
                    forwardVector = listOf(
                        target.actualDirX ?: 0f,
                        target.actualDirY ?: 1f,
                        target.actualDirZ ?: 0f
                    ),
                    upVector = listOf(
                        target.actualUpX ?: 0f,
                        target.actualUpY ?: 0f,
                        target.actualUpZ ?: 1f
                    ),
                    eulerDeg = EulerDto(
                        yaw = target.actualYawDeg ?: target.targetYawDeg,
                        pitch = target.actualPitchDeg ?: target.targetPitchDeg,
                        roll = target.actualRollDeg ?: 0f
                    )
                ),
                camera = PhotoCameraMetadata(
                    lensId = scene.lensId,
                    focalLengthMm = scene.focalLengthMm,
                    hfovDeg = scene.hfovDeg,
                    vfovDeg = scene.vfovDeg,
                    imageWidth = target.imageWidth ?: 0,
                    imageHeight = target.imageHeight ?: 0
                )
            )
        }

        val posesDoc = PosesDocument(
            scene = SceneExportMetadata(
                id = scene.id,
                name = scene.name,
                createdAtIso = formatIso8601Millis(scene.createdAtMs),
                exportedAtIso = formatIso8601Millis(nowMs),
                settings = SceneExportSettings(
                    targetOverlapRatio = scene.overlapTarget,
                    portraitOnly = true,
                    lensId = scene.lensId,
                    isUltrawide = scene.isUltrawide,
                    focalLengthMm = scene.focalLengthMm,
                    hfovDeg = scene.hfovDeg,
                    vfovDeg = scene.vfovDeg,
                    aeLocked = scene.aeLocked,
                    awbLocked = scene.awbLocked,
                    sensorSource = scene.sensorSource
                )
            ),
            photos = photoMetadataList
        )

        val posesJsonText = json.encodeToString(posesDoc)
        val readmeText = buildReadmeText()

        // Create temporary zip in cacheDir first (guaranteed shareable via FileProvider)
        val cacheZip = File(context.cacheDir, zipFileName)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(cacheZip))).use { zos ->
            zos.putNextEntry(ZipEntry("poses.json"))
            zos.write(posesJsonText.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("README.txt"))
            zos.write(readmeText.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val buffer = ByteArray(16 * 1024)
            for ((entryName, file) in photoEntryPairs) {
                zos.putNextEntry(ZipEntry("photos/$entryName"))
                BufferedInputStream(FileInputStream(file)).use { bis ->
                    while (true) {
                        val count = bis.read(buffer)
                        if (count <= 0) break
                        zos.write(buffer, 0, count)
                    }
                }
                zos.closeEntry()
            }
        }

        // Write zip into public Downloads/PanoGuide/ folder
        val downloadsUri = saveZipToPublicDownloads(cacheZip, zipFileName)

        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheZip
        )

        ExportResult(
            shareUri = shareUri,
            downloadsUriString = downloadsUri.toString(),
            zipFileName = zipFileName,
            photoCount = photoEntryPairs.size
        )
    }

    private fun saveZipToPublicDownloads(sourceZip: File, zipFileName: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, zipFileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PanoGuide")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file in Downloads/PanoGuide")

            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceZip).use { input ->
                    input.copyTo(out)
                }
            } ?: throw IllegalStateException("Could not open Downloads output stream")

            val doneValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(itemUri, doneValues, null, null)
            itemUri
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appDir = File(downloadsDir, "PanoGuide").apply { mkdirs() }
            val destFile = File(appDir, zipFileName)
            sourceZip.copyTo(destFile, overwrite = true)
            Uri.fromFile(destFile)
        }
    }

    fun createShareIntent(shareUri: Uri, sceneName: String): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, "PanoGuide 360° Capture: $sceneName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share 360° Capture Package")
    }

    private fun formatIso8601Millis(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(epochMs))
    }

    private fun buildReadmeText(): String = """
        |PanoGuide 360° Panorama Export Package
        |=====================================
        |
        |Contents
        |--------
        |- photos/001_*.jpg ... : Latest captured photo for each target, in capture order.
        |                          Standard EXIF tags (FocalLength, Orientation, DateTimeOriginal,
        |                          SubSecTimeOriginal) are preserved/written.
        |- poses.json            : Scene settings and per-photo 3D camera orientation recorded at
        |                          the moment of exposure.
        |- README.txt            : Coordinate system and unit definitions (this file).
        |
        |Coordinate System & Angle Conventions
        |-------------------------------------
        |1. Handedness & World Frame:
        |   - Right-handed Cartesian world frame:
        |       +X : Right / East (at yaw = 0°)
        |       +Y : Forward / Reference North (yaw = 0° horizontal direction)
        |       +Z : Up / Zenith (opposite gravity)
        |   - Verify: (+X) cross (+Y) = (+Z).
        |   - Note on Yaw = 0°: When sensorSource is GAME_ROTATION_VECTOR (preferred indoors
        |     to avoid magnetic distortion from steel/wiring), +Y is the initial reference
        |     heading when the sensor started, not magnetic North.
        |
        |2. Camera / Device Portrait Frame (local frame before rotation):
        |   - +X_cam : Right edge of portrait photo
        |   - +Y_cam : Top edge of portrait photo
        |   - -Z_cam : Optical viewing axis (direction the back lens points)
        |   - +Z_cam : Out of the front screen toward the photographer
        |
        |3. Singularity-Free 3D Orientation (Quaternion & Basis Vectors):
        |   - orientation.quaternion = (w, x, y, z) is a unit quaternion rotating vectors from
        |     Camera Local Frame -> World Frame:
        |       v_world = q * (0, v_cam) * conjugate(q)
        |   - orientation.forwardVector = q rotates (0, 0, -1) -> world optical viewing direction.
        |   - orientation.upVector      = q rotates (0, 1,  0) -> world top-of-image direction.
        |   - Because quaternions and 3D unit vectors have no gimbal lock, they remain exact
        |     even when pointing straight up at the ceiling (+90° pitch).
        |
        |4. Convenience Euler Angles (eulerDeg):
        |   - Units : Degrees.
        |   - yaw   : [0, 360) clockwise azimuth around +Z_world starting from +Y_world:
        |             0° = +Y (Forward), 90° = +X (Right), 180° = -Y (Back), 270° = -X (Left).
        |   - pitch : [-90, +90] elevation above the horizontal XY plane:
        |             0° = horizon, +90° = straight up (zenith), -90° = straight down (nadir).
        |   - roll  : (-180, +180] clockwise horizon tilt around the optical axis when looking
        |             through the lens (+roll = right side dropped; 0° = level horizon).
        |             Near zenith (|pitch| > 80°), horizon roll is set to 0°.
        |
        |5. Timing & Camera Fields:
        |   - capturedAtIso / capturedAtEpochMs : UTC timestamp with millisecond precision.
        |   - hfovDeg / vfovDeg                 : Portrait horizontal & vertical field of view (°).
        |   - focalLengthMm                     : Physical lens focal length (mm).
    """.trimMargin()
}
