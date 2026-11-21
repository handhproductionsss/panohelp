package com.example.data.stitch

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.data.local.SceneWithTargets
import com.example.data.local.TargetEntity
import com.example.domain.math.Quaternion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

data class StitchProgress(
    val fraction: Float,
    val stageLabel: String
)

data class StitchResult(
    val localStitchedFile: File,
    val shareUri: Uri,
    val publicDisplayName: String,
    val publicUriString: String,
    val width: Int,
    val height: Int,
    val sourcePhotoCount: Int
)

/**
 * Multi-core on-device spherical panorama stitcher.
 * Uses the captured photos' exposure-time quaternions, camera FOV, EXIF orientation,
 * small-angle overlap refinement, and exposure gain balancing to render a 360°x180°
 * equirectangular JPEG with Google Photo Sphere (`GPano`) XMP metadata.
 */
class OnDevicePanoramaStitcher(private val context: Context) {

    suspend fun stitchScene(
        sceneWithTargets: SceneWithTargets,
        onProgress: (StitchProgress) -> Unit = {}
    ): StitchResult = withContext(Dispatchers.Default) {
        val scene = sceneWithTargets.scene
        val capturedTargets = sceneWithTargets.targets
            .filter { it.captured && !it.photoFilePath.isNullOrBlank() && File(it.photoFilePath).exists() }
            .sortedWith(compareBy({ it.captureOrder ?: Int.MAX_VALUE }, { it.capturedAtMs ?: Long.MAX_VALUE }))

        if (capturedTargets.isEmpty()) {
            throw IllegalStateException("Capture at least one photo before stitching.")
        }

        onProgress(StitchProgress(0.05f, "Reading photos & pose metadata…"))

        // Choose output resolution safely based on available JVM heap
        val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        val (outW, outH, maxSourceLongSide) = when {
            maxHeapMb >= 450L -> Triple(4096, 2048, 1080)
            maxHeapMb >= 256L -> Triple(3072, 1536, 860)
            else -> Triple(2400, 1200, 680)
        }

        val frames = mutableListOf<StitchFrameInput>()
        capturedTargets.forEachIndexed { idx, t ->
            val frame = loadStitchFrame(
                target = t,
                hfovDeg = scene.hfovDeg,
                vfovDeg = scene.vfovDeg,
                maxLongSide = maxSourceLongSide
            )
            if (frame != null) {
                frames += frame
            }
            val loadFrac = 0.05f + 0.25f * ((idx + 1).toFloat() / capturedTargets.size)
            onProgress(StitchProgress(loadFrac, "Decoding photo ${idx + 1} of ${capturedTargets.size}…"))
        }

        if (frames.isEmpty()) {
            throw IllegalStateException("Could not decode any captured photos.")
        }

        onProgress(StitchProgress(0.35f, "Refining overlaps & balancing exposure…"))
        EquirectangularMath.refineOverlapsAndExposure(frames)

        val matrices = Array(frames.size) { i -> frames[i].buildWorldToCamMatrix() }
        val outputBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

        onProgress(StitchProgress(0.45f, "Rendering ${outW}×${outH} panorama…"))

        val stripHeight = 96
        val stripRanges = (0 until outH step stripHeight).map { y0 ->
            y0 to (y0 + stripHeight).coerceAtMost(outH)
        }
        val completedStrips = AtomicInteger(0)
        val totalStrips = stripRanges.size
        val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        // Render strips in bounded parallel batches to utilize multi-core CPUs while keeping RAM low
        stripRanges.chunked(workerCount).forEach { batch ->
            val renderedBatch = coroutineScope {
                batch.map { (yStart, yEnd) ->
                    async(Dispatchers.Default) {
                        val rows = yEnd - yStart
                        val stripPixels = IntArray(outW * rows)
                        EquirectangularMath.renderStrip(
                            frames = frames,
                            matrices = matrices,
                            outW = outW,
                            outH = outH,
                            yStart = yStart,
                            yEnd = yEnd,
                            outStripPixels = stripPixels
                        )
                        val done = completedStrips.incrementAndGet()
                        val frac = 0.45f + 0.45f * (done.toFloat() / totalStrips)
                        onProgress(StitchProgress(frac, "Blending spherical seams…"))
                        Triple(yStart, rows, stripPixels)
                    }
                }.awaitAll()
            }
            for ((yStart, rows, stripPixels) in renderedBatch) {
                outputBitmap.setPixels(stripPixels, 0, outW, 0, yStart, outW, rows)
            }
        }

        onProgress(StitchProgress(0.92f, "Embedding GPano 360° metadata…"))

        val sceneDir = File(File(context.filesDir, "scenes"), scene.id).apply { mkdirs() }
        val localStitchedFile = File(sceneDir, "stitched_panorama.jpg")
        FileOutputStream(localStitchedFile).use { fos ->
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }
        outputBitmap.recycle()

        // Write standard EXIF + Google Photo Sphere GPano XMP tags
        writeStandardExif(localStitchedFile, outW, outH)
        GPanoXmpWriter.injectIntoJpeg(localStitchedFile, outW, outH)

        // Also save to public Pictures/PanoGuide (and Downloads/PanoGuide fallback) so Gallery/Files see it
        val slug = scene.name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "scene" }
            .take(28)
        val tsStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val publicFileName = "PanoGuide_${slug}_360_${tsStamp}.jpg"
        val publicUri = saveJpegToPublicPictures(localStitchedFile, publicFileName)

        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            localStitchedFile
        )

        onProgress(StitchProgress(1.0f, "Panorama ready"))

        StitchResult(
            localStitchedFile = localStitchedFile,
            shareUri = shareUri,
            publicDisplayName = publicFileName,
            publicUriString = publicUri.toString(),
            width = outW,
            height = outH,
            sourcePhotoCount = frames.size
        )
    }

    private fun loadStitchFrame(
        target: TargetEntity,
        hfovDeg: Float,
        vfovDeg: Float,
        maxLongSide: Int
    ): StitchFrameInput? {
        val path = target.photoFilePath ?: return null
        val file = File(path)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val rawLong = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var inSampleSize = 1
        while (rawLong / (inSampleSize * 2) >= maxLongSide) {
            inSampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

        // Respect EXIF orientation so frame pixels always match portrait camera basis
        val exifOrient = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val rotateDeg = when (exifOrient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotateDeg != 0f) {
            val m = Matrix().apply { postRotate(rotateDeg) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }

        // Scale down precisely to maxLongSide if needed for consistent memory footprint
        val curLong = max(bmp.width, bmp.height)
        if (curLong > maxLongSide) {
            val scale = maxLongSide.toFloat() / curLong
            val scaledW = (bmp.width * scale).toInt().coerceAtLeast(16)
            val scaledH = (bmp.height * scale).toInt().coerceAtLeast(16)
            val scaled = Bitmap.createScaledBitmap(bmp, scaledW, scaledH, true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }

        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        val q = if (target.quatW != null && target.quatX != null && target.quatY != null && target.quatZ != null) {
            Quaternion(target.quatW, target.quatX, target.quatY, target.quatZ).normalized()
        } else {
            Quaternion.fromYawPitchRollDeg(
                yawDeg = target.actualYawDeg ?: target.targetYawDeg,
                pitchDeg = target.actualPitchDeg ?: target.targetPitchDeg,
                rollDeg = target.actualRollDeg ?: 0f
            )
        }

        return StitchFrameInput(
            id = target.id,
            quaternion = q,
            hfovDeg = hfovDeg,
            vfovDeg = vfovDeg,
            width = w,
            height = h,
            pixels = pixels
        )
    }

    private fun writeStandardExif(file: File, width: Int, height: Int) {
        runCatching {
            val exif = ExifInterface(file)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER ?: "Android")
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL ?: "PanoGuide")
            exif.setAttribute(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            )
            exif.saveAttributes()
        }
    }

    private fun saveJpegToPublicPictures(sourceFile: File, displayName: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PanoGuide")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create panorama entry in Pictures/PanoGuide")

            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            } ?: throw IllegalStateException("Could not write panorama to MediaStore")

            val doneValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(itemUri, doneValues, null, null)
            itemUri
        } else {
            @Suppress("DEPRECATION")
            val picsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val appDir = File(picsDir, "PanoGuide").apply { mkdirs() }
            val destFile = File(appDir, displayName)
            sourceFile.copyTo(destFile, overwrite = true)
            Uri.fromFile(destFile)
        }
    }

    fun createSharePanoramaIntent(shareUri: Uri, sceneName: String): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, "PanoGuide 360° Panorama: $sceneName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share 360° Panorama")
    }
}
