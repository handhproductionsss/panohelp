package com.example.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PanoramaHorizontal
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.local.PanoDatabase
import com.example.data.repository.SceneRepository
import com.example.data.stitch.OnDevicePanoramaStitcher
import com.example.data.stitch.StitchProgress
import com.example.domain.math.Quaternion
import com.example.domain.math.Vec3
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.Obsidian
import com.example.ui.theme.OffWhiteText
import com.example.ui.theme.SignalCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.tan

data class PanoramaViewerUiState(
    val sceneName: String = "",
    val stitchedFilePath: String? = null,
    val savedPublicName: String? = null,
    val equirectW: Int = 0,
    val equirectH: Int = 0,
    val equirectPixels: IntArray? = null,
    val flatBitmap: ImageBitmap? = null,
    val isStitching: Boolean = false,
    val stitchProgress: StitchProgress = StitchProgress(0f, ""),
    val errorMessage: String? = null
)

class PanoramaViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = PanoDatabase.getInstance(application).panoDao()
    private val repository = SceneRepository(application, dao)
    private val stitcher = OnDevicePanoramaStitcher(application)

    private val _uiState = MutableStateFlow(PanoramaViewerUiState())
    val uiState: StateFlow<PanoramaViewerUiState> = _uiState.asStateFlow()

    private var currentSceneId: String? = null

    fun loadScene(sceneId: String) {
        if (currentSceneId == sceneId) return
        currentSceneId = sceneId

        viewModelScope.launch {
            val swt = repository.getSceneOnce(sceneId) ?: return@launch
            val path = swt.scene.stitchedImagePath
            _uiState.value = _uiState.value.copy(
                sceneName = swt.scene.name,
                stitchedFilePath = path,
                savedPublicName = swt.scene.stitchedSavedName
            )
            if (path != null && File(path).exists()) {
                loadEquirectPreview(path)
            } else {
                restitch()
            }
        }
    }

    fun restitch() {
        val sceneId = currentSceneId ?: return
        if (_uiState.value.isStitching) return

        viewModelScope.launch {
            val swt = repository.getSceneOnce(sceneId) ?: return@launch
            _uiState.value = _uiState.value.copy(
                isStitching = true,
                errorMessage = null,
                stitchProgress = StitchProgress(0.02f, "Starting on-device stitcher…")
            )
            runCatching {
                val res = stitcher.stitchScene(swt) { prog ->
                    _uiState.value = _uiState.value.copy(stitchProgress = prog)
                }
                repository.markStitched(
                    sceneId = sceneId,
                    localStitchedPath = res.localStitchedFile.absolutePath,
                    savedPublicName = res.publicDisplayName
                )
                res
            }.onSuccess { res ->
                _uiState.value = _uiState.value.copy(
                    isStitching = false,
                    stitchedFilePath = res.localStitchedFile.absolutePath,
                    savedPublicName = res.publicDisplayName
                )
                loadEquirectPreview(res.localStitchedFile.absolutePath)
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isStitching = false,
                    errorMessage = err.message ?: "Stitching failed"
                )
            }
        }
    }

    private suspend fun loadEquirectPreview(path: String) = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val rawW = bounds.outWidth.coerceAtLeast(1)
        var sample = 1
        while (rawW / (sample * 2) >= 1600) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return@withContext
        val scaled = if (bmp.width > 1600) {
            val h = (bmp.height * (1600f / bmp.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, 1600, h, true).also {
                if (it != bmp) bmp.recycle()
            }
        } else bmp

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        val flatImage = scaled.asImageBitmap()

        _uiState.value = _uiState.value.copy(
            equirectW = w,
            equirectH = h,
            equirectPixels = pixels,
            flatBitmap = flatImage
        )
    }

    fun createShareIntent(): android.content.Intent? {
        val path = _uiState.value.stitchedFilePath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        return stitcher.createSharePanoramaIntent(uri, _uiState.value.sceneName)
    }
}

@Composable
fun PanoramaViewerScreen(
    sceneId: String,
    onNavigateBack: () -> Unit,
    viewModel: PanoramaViewerViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var is360Mode by remember { mutableStateOf(true) }
    var viewYawDeg by remember { mutableFloatStateOf(0f) }
    var viewPitchDeg by remember { mutableFloatStateOf(0f) }
    var perspectiveBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(sceneId) {
        viewModel.loadScene(sceneId)
    }

    // Render perspective look-around viewport whenever yaw/pitch or source pixels change
    LaunchedEffect(state.equirectPixels, viewYawDeg, viewPitchDeg, is360Mode) {
        val pixels = state.equirectPixels
        if (is360Mode && pixels != null && state.equirectW > 0 && state.equirectH > 0) {
            val rendered = withContext(Dispatchers.Default) {
                renderPerspectiveViewport(
                    equirect = pixels,
                    eqW = state.equirectW,
                    eqH = state.equirectH,
                    yawDeg = viewYawDeg,
                    pitchDeg = viewPitchDeg,
                    outW = 420,
                    outH = 560,
                    hfovDeg = 72f
                )
            }
            perspectiveBitmap = rendered
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
    ) {
        if (state.isStitching) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = SignalCyan)
                Text(
                    text = stringResource(R.string.stitching_panorama),
                    style = MaterialTheme.typography.titleLarge,
                    color = OffWhiteText
                )
                LinearProgressIndicator(
                    progress = { state.stitchProgress.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = state.stitchProgress.stageLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OffWhiteText.copy(alpha = 0.78f)
                )
            }
        } else if (state.errorMessage != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = state.errorMessage ?: "Stitching failed",
                    style = MaterialTheme.typography.bodyLarge,
                    color = OffWhiteText
                )
                Button(onClick = { viewModel.restitch() }) {
                    Text(stringResource(R.string.viewer_restitch))
                }
            }
        } else {
            val displayBitmap = if (is360Mode) perspectiveBitmap else state.flatBitmap
            if (displayBitmap != null) {
                Image(
                    bitmap = displayBitmap,
                    contentDescription = "Stitched 360 panorama",
                    contentScale = if (is360Mode) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(is360Mode) {
                            if (is360Mode) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val w = max(size.width, 1)
                                    val h = max(size.height, 1)
                                    viewYawDeg = ((viewYawDeg - (drag.x / w) * 85f) % 360f + 360f) % 360f
                                    viewPitchDeg = (viewPitchDeg + (drag.y / h) * 75f).coerceIn(-82f, 82f)
                                }
                            }
                        }
                )
            }
        }

        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                color = CharcoalSurface.copy(alpha = 0.84f),
                shape = CircleShape
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 14.dp)
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("viewer_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = OffWhiteText
                        )
                    }
                    Text(
                        text = state.sceneName,
                        style = MaterialTheme.typography.titleMedium,
                        color = OffWhiteText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                color = CharcoalSurface.copy(alpha = 0.84f),
                shape = CircleShape
            ) {
                IconButton(
                    onClick = { is360Mode = !is360Mode },
                    modifier = Modifier.testTag("viewer_mode_toggle")
                ) {
                    Icon(
                        imageVector = if (is360Mode) Icons.Filled.PanoramaHorizontal else Icons.Filled.Public,
                        contentDescription = stringResource(
                            if (is360Mode) R.string.viewer_mode_flat else R.string.viewer_mode_360
                        ),
                        tint = SignalCyan
                    )
                }
            }
        }

        // Bottom Controls
        if (!state.isStitching && state.stitchedFilePath != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = CharcoalSurface.copy(alpha = 0.84f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = state.savedPublicName?.let { "Saved: Pictures/PanoGuide/$it" }
                            ?: stringResource(R.string.viewer_saved_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = OffWhiteText,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.restitch() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("viewer_restitch_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.viewer_restitch))
                    }

                    Button(
                        onClick = {
                            viewModel.createShareIntent()?.let { context.startActivity(it) }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("viewer_share_button")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.viewer_share_jpeg))
                    }
                }
            }
        }
    }
}

private fun renderPerspectiveViewport(
    equirect: IntArray,
    eqW: Int,
    eqH: Int,
    yawDeg: Float,
    pitchDeg: Float,
    outW: Int,
    outH: Int,
    hfovDeg: Float
): ImageBitmap {
    val q = Quaternion.fromYawPitchRollDeg(yawDeg, pitchDeg, 0f)
    val c0 = q.rotateVector(Vec3(1f, 0f, 0f))
    val c1 = q.rotateVector(Vec3(0f, 1f, 0f))
    val c2 = q.rotateVector(Vec3(0f, 0f, 1f))

    val halfTanH = tan(Math.toRadians((hfovDeg * 0.5f).toDouble())).toFloat()
    val halfTanV = halfTanH * (outH.toFloat() / outW.toFloat())

    val outPixels = IntArray(outW * outH)
    val invTwoPi = (1.0 / (2.0 * PI)).toFloat()
    val invPi = (1.0 / PI).toFloat()

    for (y in 0 until outH) {
        val ndcY = 1f - 2f * (y + 0.5f) / outH
        val camY = ndcY * halfTanV
        val rowOff = y * outW

        for (x in 0 until outW) {
            val ndcX = 2f * (x + 0.5f) / outW - 1f
            val camX = ndcX * halfTanH
            // Camera optical forward is -Z
            val wx = c0.x * camX + c1.x * camY - c2.x
            val wy = c0.y * camX + c1.y * camY - c2.y
            val wz = c0.z * camX + c1.z * camY - c2.z
            val norm = kotlin.math.sqrt(wx * wx + wy * wy + wz * wz).coerceAtLeast(1e-5f)

            val yawRad = atan2((wx / norm).toDouble(), (wy / norm).toDouble()).toFloat()
            val pitchRad = asin((wz / norm).coerceIn(-1f, 1f).toDouble()).toFloat()

            val uNorm = (yawRad * invTwoPi + 0.5f).let { if (it < 0f) it + 1f else if (it >= 1f) it - 1f else it }
            val vNorm = (0.5f - pitchRad * invPi).coerceIn(0f, 0.9999f)

            val sx = (uNorm * eqW).toInt().coerceIn(0, eqW - 1)
            val sy = (vNorm * eqH).toInt().coerceIn(0, eqH - 1)
            outPixels[rowOff + x] = equirect[sy * eqW + sx]
        }
    }

    val bmp = Bitmap.createBitmap(outPixels, outW, outH, Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap()
}
