package com.example.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.camera.SimulatedCameraEngine
import com.example.domain.capture.TiltStatus
import com.example.ui.theme.AmberCaution
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.MintEmerald
import com.example.ui.theme.Obsidian
import com.example.ui.theme.OffWhiteText
import com.example.ui.theme.SignalCyan

@Composable
fun CaptureScreen(
    sceneId: String,
    onNavigateBack: () -> Unit,
    viewModel: CaptureViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(sceneId) {
        viewModel.initSession(sceneId)
    }

    // Keep screen awake during active capture
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Pause/resume sensors cleanly on incoming calls or app switches
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onLifecycleResume()
                Lifecycle.Event.ON_PAUSE -> viewModel.onLifecyclePause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequestedOnce = true
    }

    // Brief visual shutter flash on capture
    var flashAlphaTarget by remember { mutableFloatStateOf(0f) }
    val flashAlpha by animateFloatAsState(
        targetValue = flashAlphaTarget,
        animationSpec = tween(durationMillis = 160),
        label = "shutter_flash"
    )
    LaunchedEffect(state.flashTriggerCounter) {
        if (state.flashTriggerCounter > 0) {
            flashAlphaTarget = 0.55f
            kotlinx.coroutines.delay(90L)
            flashAlphaTarget = 0f
        }
    }

    if (!state.isSimulated && !hasCameraPermission) {
        CameraPermissionGate(
            permissionRequestedOnce = permissionRequestedOnce,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
                context.startActivity(intent)
            },
            onSwitchToSimulated = { viewModel.switchToSimulatedMode() },
            onBack = onNavigateBack
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
    ) {
        // 1. Live camera feed OR interactive Simulated 3D room viewfinder
        if (state.isSimulated) {
            SimulatedRoomViewfinder(
                state = state,
                onDrag = { dx, dy, w, h -> viewModel.onSimDrag(dx, dy, w, h) }
            )
        } else {
            val previewView = remember {
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
            }
            DisposableEffect(lifecycleOwner, previewView) {
                viewModel.cameraController.bindCamera(
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    shouldLockExposureImmediately = state.targets.any { it.captured }
                )
                onDispose { }
            }
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Live 3D sphere overlay (thumbnails, empty markers, reticle, hold ring, level bar)
        SphereOverlayCanvas(
            pose = state.currentPose,
            targets = state.targets,
            evaluation = state.evaluation,
            hfovDeg = state.cameraInfo.hfovDeg,
            vfovDeg = state.cameraInfo.vfovDeg,
            onTargetTap = { targetId -> viewModel.onTargetTapped(targetId) }
        )

        // 3. Shutter flash overlay
        if (flashAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flashAlpha))
            )
        }

        // 4. Top HUD (Back, Scene Name, Sound toggle, Finish button, Warning chips)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = CharcoalSurface.copy(alpha = 0.82f),
                    shape = CircleShape
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 14.dp)
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("capture_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to scenes",
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = CharcoalSurface.copy(alpha = 0.82f),
                        shape = CircleShape
                    ) {
                        IconButton(
                            onClick = { viewModel.toggleSound() },
                            modifier = Modifier.testTag("sound_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (state.soundEnabled) {
                                    Icons.AutoMirrored.Filled.VolumeUp
                                } else {
                                    Icons.AutoMirrored.Filled.VolumeOff
                                },
                                contentDescription = stringResource(
                                    if (state.soundEnabled) R.string.sound_on_cd else R.string.sound_off_cd
                                ),
                                tint = OffWhiteText
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.onFinishClicked() },
                        modifier = Modifier.testTag("finish_scene_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.finish_scene))
                    }
                }
            }

            // Status / Warning Banners (plain-language feedback)
            StatusBanners(state = state, onCancelRetake = { viewModel.cancelRetakeMode() })
        }

        // 5. Bottom HUD (Mini-Sphere Radar, Manual Shutter, Simulated Test Controls)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.isSimulated) {
                SimulatedControlsBar(
                    tiltActive = state.simTiltActive,
                    shakeActive = state.simShakeActive,
                    onAimNearest = { viewModel.onSimAimNearest() },
                    onToggleTilt = { viewModel.onSimToggleTilt() },
                    onToggleShake = { viewModel.onSimToggleShake() }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Visual-only mini-sphere overview (no numeric counts)
                MiniSphereRadar(
                    pose = state.currentPose,
                    targets = state.targets,
                    activeTargetId = state.evaluation.activeTarget?.id,
                    onTargetTap = { viewModel.onTargetTapped(it) }
                )

                // Manual shutter button (locked when moving too fast to avoid blur)
                ManualShutterButton(
                    enabled = state.evaluation.canManualCapture,
                    isMovingTooFast = state.evaluation.isMovingTooFast,
                    isBusy = state.isCaptureInFlight,
                    onClick = { viewModel.onManualCaptureClick() }
                )

                // Exposure / White Balance status pill for symmetry
                ExposureStatusPill(
                    isSimulated = state.isSimulated,
                    aeLocked = state.cameraInfo.aeLocked,
                    awbLocked = state.cameraInfo.awbLocked,
                    aeSupported = state.cameraInfo.aeLockSupported,
                    awbSupported = state.cameraInfo.awbLockSupported
                )
            }
        }
    }

    // Incomplete finish confirmation dialog
    if (state.showIncompleteFinishConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissIncompleteFinishConfirm() },
            title = { Text(stringResource(R.string.finish_incomplete_title)) },
            text = { Text(stringResource(R.string.finish_incomplete_body)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.performExport() },
                    modifier = Modifier.testTag("confirm_export_button")
                ) {
                    Text(stringResource(R.string.export_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissIncompleteFinishConfirm() }) {
                    Text(stringResource(R.string.keep_shooting))
                }
            }
        )
    }

    if (state.isExporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.exporting_package)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.exporting_package))
                }
            },
            confirmButton = {}
        )
    }

    state.exportResult?.let { res ->
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissExportResult()
                onNavigateBack()
            },
            title = { Text(stringResource(R.string.export_success_title)) },
            text = {
                Text(stringResource(R.string.export_success_body, res.zipFileName))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val shareIntent = viewModel.buildShareIntent(res)
                        context.startActivity(shareIntent)
                    },
                    modifier = Modifier.testTag("share_export_button")
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share_package))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissExportResult()
                        onNavigateBack()
                    },
                    modifier = Modifier.testTag("done_export_button")
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }

    state.errorMessage?.let { err ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Notice") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }
}

@Composable
private fun SimulatedRoomViewfinder(
    state: CaptureUiState,
    onDrag: (Float, Float, Float, Float) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(
                        dragAmount.x,
                        dragAmount.y,
                        size.width.toFloat(),
                        size.height.toFloat()
                    )
                }
            }
    ) {
        drawIntoCanvas { canvas ->
            SimulatedCameraEngine.renderRoomToCanvas(
                canvas = canvas.nativeCanvas,
                width = size.width.toInt().coerceAtLeast(1),
                height = size.height.toInt().coerceAtLeast(1),
                pose = state.currentPose,
                hfovDeg = state.cameraInfo.hfovDeg,
                vfovDeg = state.cameraInfo.vfovDeg
            )
        }
    }
}

@Composable
private fun StatusBanners(
    state: CaptureUiState,
    onCancelRetake: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (state.evaluation.isRetakeMode) {
            HudPill(
                text = stringResource(R.string.retake_banner),
                containerColor = AmberCaution,
                contentColor = Obsidian,
                trailingAction = {
                    IconButton(onClick = onCancelRetake, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cancel_retake),
                            tint = Obsidian,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            )
        }

        AnimatedVisibility(
            visible = state.evaluation.isMovingTooFast,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            HudPill(
                text = stringResource(R.string.moving_too_fast_warning),
                containerColor = CoralAlert,
                contentColor = Obsidian
            )
        }

        AnimatedVisibility(
            visible = !state.evaluation.isMovingTooFast && state.evaluation.tiltStatus != TiltStatus.LEVEL,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val tiltText = if (state.evaluation.tiltStatus == TiltStatus.TILTED_LEFT) {
                stringResource(R.string.tilt_left_warning)
            } else {
                stringResource(R.string.tilt_right_warning)
            }
            HudPill(
                text = tiltText,
                containerColor = AmberCaution,
                contentColor = Obsidian
            )
        }

        if (state.evaluation.allTargetsCaptured && !state.evaluation.isRetakeMode) {
            HudPill(
                text = stringResource(R.string.sphere_covered_hint),
                containerColor = MintEmerald,
                contentColor = Obsidian
            )
        }

        if (state.isSimulated) {
            HudPill(
                text = stringResource(R.string.sim_drag_hint),
                containerColor = CharcoalSurface.copy(alpha = 0.85f),
                contentColor = SignalCyan
            )
        }

        if (!state.isSimulated && (!state.cameraInfo.aeLockSupported || !state.cameraInfo.awbLockSupported)) {
            val msg = if (!state.cameraInfo.aeLockSupported) {
                stringResource(R.string.ae_lock_unsupported)
            } else {
                stringResource(R.string.awb_lock_unsupported)
            }
            HudPill(
                text = msg,
                containerColor = CharcoalSurface.copy(alpha = 0.85f),
                contentColor = AmberCaution
            )
        }

        if (!state.isSimulated && !state.sensorStatus.isAvailable) {
            HudPill(
                text = stringResource(R.string.sensor_unavailable_warning),
                containerColor = CoralAlert,
                contentColor = Obsidian
            )
        } else if (!state.isSimulated && state.sensorStatus.isCompassFallback) {
            HudPill(
                text = stringResource(R.string.gyro_fallback_warning),
                containerColor = CharcoalSurface.copy(alpha = 0.85f),
                contentColor = AmberCaution
            )
        }

        if (state.isLowStorage) {
            HudPill(
                text = stringResource(R.string.low_storage_warning),
                containerColor = CoralAlert,
                contentColor = Obsidian
            )
        }
    }
}

@Composable
private fun HudPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    trailingAction: (@Composable () -> Unit)? = null
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor
            )
            if (trailingAction != null) {
                Spacer(Modifier.width(6.dp))
                trailingAction()
            }
        }
    }
}

@Composable
private fun ManualShutterButton(
    enabled: Boolean,
    isMovingTooFast: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit
) {
    val ringColor = when {
        isMovingTooFast -> CoralAlert.copy(alpha = 0.6f)
        enabled -> OffWhiteText
        else -> OffWhiteText.copy(alpha = 0.35f)
    }
    Surface(
        onClick = onClick,
        enabled = enabled && !isBusy,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, ringColor, CircleShape)
            .padding(6.dp)
            .testTag("manual_shutter_button")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    if (enabled && !isBusy) OffWhiteText else OffWhiteText.copy(alpha = 0.3f)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = Obsidian,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = stringResource(R.string.manual_capture_cd),
                    tint = Obsidian,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun ExposureStatusPill(
    isSimulated: Boolean,
    aeLocked: Boolean,
    awbLocked: Boolean,
    aeSupported: Boolean,
    awbSupported: Boolean
) {
    val locked = isSimulated || (aeLocked && awbLocked)
    val unsupported = !isSimulated && (!aeSupported || !awbSupported)

    Surface(
        color = CharcoalSurface.copy(alpha = 0.80f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.width(96.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = when {
                    unsupported -> Icons.Filled.WarningAmber
                    locked -> Icons.Filled.Lock
                    else -> Icons.Filled.LockOpen
                },
                contentDescription = null,
                tint = when {
                    unsupported -> AmberCaution
                    locked -> MintEmerald
                    else -> SignalCyan
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = when {
                    unsupported -> "Auto"
                    locked -> "AE/WB"
                    else -> "1st Lock"
                },
                style = MaterialTheme.typography.labelSmall,
                color = OffWhiteText
            )
        }
    }
}

@Composable
private fun SimulatedControlsBar(
    tiltActive: Boolean,
    shakeActive: Boolean,
    onAimNearest: () -> Unit,
    onToggleTilt: () -> Unit,
    onToggleShake: () -> Unit
) {
    Surface(
        color = CharcoalSurface.copy(alpha = 0.88f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onAimNearest,
                modifier = Modifier.testTag("sim_aim_nearest_button")
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.sim_snap_nearest), style = MaterialTheme.typography.labelMedium)
            }

            FilterChip(
                selected = tiltActive,
                onClick = onToggleTilt,
                label = { Text(stringResource(R.string.sim_test_tilt)) },
                leadingIcon = {
                    Icon(Icons.Filled.ScreenRotation, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.testTag("sim_tilt_chip")
            )

            FilterChip(
                selected = shakeActive,
                onClick = onToggleShake,
                label = { Text(stringResource(R.string.sim_test_shake)) },
                leadingIcon = {
                    Icon(Icons.Filled.Vibration, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.testTag("sim_shake_chip")
            )
        }
    }
}

@Composable
private fun CameraPermissionGate(
    permissionRequestedOnce: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchToSimulated: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                color = CharcoalSurface,
                shape = CircleShape,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null,
                        tint = SignalCyan,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.camera_permission_title),
                style = MaterialTheme.typography.headlineMedium,
                color = OffWhiteText
            )
            Text(
                text = stringResource(R.string.camera_permission_reason),
                style = MaterialTheme.typography.bodyLarge,
                color = OffWhiteText.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("grant_camera_button")
            ) {
                Text(stringResource(R.string.grant_camera_permission))
            }
            if (permissionRequestedOnce) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.open_settings))
                }
            }
            FilledTonalButton(
                onClick = onSwitchToSimulated,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("use_simulated_instead_button")
            ) {
                Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.use_simulated_instead))
            }
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
