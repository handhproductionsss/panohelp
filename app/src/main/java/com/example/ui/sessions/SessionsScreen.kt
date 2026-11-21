package com.example.ui.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.local.SceneWithTargets
import com.example.data.local.SessionStatus
import com.example.data.local.TargetEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpenScene: (String) -> Unit,
    onOpenPanoramaViewer: (String) -> Unit,
    viewModel: SessionsViewModel = viewModel()
) {
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val simulatedMode by viewModel.simulatedMode.collectAsStateWithLifecycle()
    val actionState by viewModel.actionState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showNewSceneDialog by rememberSaveable { mutableStateOf(false) }
    var newSceneName by rememberSaveable { mutableStateOf("") }
    var pendingDeleteScene by remember { mutableStateOf<SceneWithTargets?>(null) }

    LaunchedEffect(actionState.pendingShareIntent) {
        val intent = actionState.pendingShareIntent
        if (intent != null) {
            context.startActivity(intent)
            viewModel.consumeShareIntent()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    brush = Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CameraAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = stringResource(R.string.tagline),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    newSceneName = ""
                    showNewSceneDialog = true
                },
                icon = { Icon(Icons.Filled.AddAPhoto, contentDescription = null) },
                text = { Text(stringResource(R.string.new_scene_cta)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("new_scene_fab")
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 620.dp)
                    .padding(horizontal = 16.dp)
            ) {
                // Simulated Mode card (for computer / emulator verification without physical phone)
                SimulatedModeBanner(
                    enabled = simulatedMode,
                    onToggle = { viewModel.setSimulatedMode(it) }
                )

                Spacer(Modifier.height(16.dp))

                if (scenes.isEmpty()) {
                    EmptySessionsState(
                        onCreateFirst = {
                            newSceneName = ""
                            showNewSceneDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.sessions_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(scenes, key = { it.scene.id }) { item ->
                            SceneSessionCard(
                                item = item,
                                onResume = { onOpenScene(item.scene.id) },
                                onStitchOrView = { onOpenPanoramaViewer(item.scene.id) },
                                onShare = { viewModel.exportAndShareScene(item) },
                                onDelete = { pendingDeleteScene = item }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewSceneDialog) {
        AlertDialog(
            onDismissRequest = { showNewSceneDialog = false },
            title = { Text(stringResource(R.string.new_scene_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = newSceneName,
                    onValueChange = { newSceneName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.new_scene_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scene_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalName = newSceneName.trim().ifEmpty { "Living Room" }
                        showNewSceneDialog = false
                        viewModel.createScene(finalName) { createdId ->
                            onOpenScene(createdId)
                        }
                    },
                    modifier = Modifier.testTag("start_capture_button")
                ) {
                    Text(stringResource(R.string.start_capture))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewSceneDialog = false },
                    modifier = Modifier.testTag("cancel_new_scene_button")
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingDeleteScene?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteScene = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.delete_confirm_body, toDelete.scene.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteScene(toDelete.scene.id)
                        pendingDeleteScene = null
                    },
                    modifier = Modifier.testTag("confirm_delete_button")
                ) {
                    Text(stringResource(R.string.delete_session))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteScene = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (actionState.isExporting) {
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

    actionState.exportSuccess?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDialog() },
            title = { Text(stringResource(R.string.export_success_title)) },
            text = {
                Text(stringResource(R.string.export_success_body, result.zipFileName))
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissDialog() }) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }

    actionState.errorMessage?.let { err ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDialog() },
            title = { Text("Export Error") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDialog() }) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }
}

@Composable
private fun SimulatedModeBanner(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.simulated_mode_label),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.simulated_mode_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("simulated_mode_switch")
            )
        }
    }
}

@Composable
private fun SceneSessionCard(
    item: SceneWithTargets,
    onResume: () -> Unit,
    onStitchOrView: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val scene = item.scene
    val hasAnyCapture = item.targets.any { it.captured }
    val isExported = scene.status == SessionStatus.EXPORTED.name
    val hasStitchedPanorama = remember(scene.stitchedImagePath) {
        !scene.stitchedImagePath.isNullOrBlank() && File(scene.stitchedImagePath).exists()
    }

    val dateStr = remember(scene.createdAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(scene.createdAtMs))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_card_${scene.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Visual-only coverage glyph (no numeric counts)
                CoverageSphereGlyph(
                    targets = item.targets,
                    modifier = Modifier.size(54.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = scene.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusChip(isExported = isExported)
                        if (scene.isUltrawide) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "UW",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (scene.isSimulated) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "SIM",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.created_at_format, dateStr),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_scene_${scene.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.delete_session),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("resume_scene_${scene.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.resume_session), maxLines = 1)
                }

                if (hasAnyCapture) {
                    FilledTonalButton(
                        onClick = onStitchOrView,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("stitch_scene_${scene.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Panorama,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (hasStitchedPanorama) R.string.view_panorama else R.string.stitch_now
                            ),
                            maxLines = 1
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onShare,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("share_scene_${scene.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(
                                if (isExported) R.string.export_share else R.string.re_export
                            ),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(isExported: Boolean) {
    val bg = if (isExported) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
    val fg = if (isExported) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            if (isExported) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = if (isExported) {
                    stringResource(R.string.status_exported)
                } else {
                    stringResource(R.string.status_in_progress)
                },
                style = MaterialTheme.typography.labelMedium,
                color = fg
            )
        }
    }
}

/**
 * Compact visual-only polar coverage indicator showing captured vs uncaptured directions
 * without displaying numeric counts or percentages.
 */
@Composable
private fun CoverageSphereGlyph(
    targets: List<TargetEntity>,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline

    Canvas(modifier = modifier.semantics(mergeDescendants = true) {}) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val maxR = size.minDimension * 0.44f

        drawCircle(color = outline.copy(alpha = 0.35f), radius = maxR, style = Stroke(width = 1.5f))
        drawCircle(color = outline.copy(alpha = 0.25f), radius = maxR * 0.62f, style = Stroke(width = 1f))

        for (t in targets) {
            val pitchRad = Math.toRadians(t.targetPitchDeg.toDouble())
            // Map +90 (ceiling) to center, 0 (eye) to mid ring, -50 (lower) to outer ring
            val radialNorm = ((90.0 - t.targetPitchDeg) / 155.0).toFloat().coerceIn(0f, 1f)
            val r = maxR * radialNorm
            val yawRad = Math.toRadians(t.targetYawDeg.toDouble())
            val px = cx + r * sin(yawRad).toFloat()
            val py = cy - r * cos(yawRad).toFloat()

            if (t.captured) {
                drawCircle(color = secondary, radius = 3.2f, center = Offset(px, py))
            } else {
                drawCircle(
                    color = primary.copy(alpha = 0.55f),
                    radius = 2.6f,
                    center = Offset(px, py),
                    style = Stroke(width = 1.2f)
                )
            }
        }
    }
}

@Composable
private fun EmptySessionsState(
    onCreateFirst: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(modifier = Modifier.size(136.dp)) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val r = size.minDimension * 0.44f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(primary.copy(alpha = 0.22f), secondary.copy(alpha = 0.04f))
                ),
                radius = r
            )
            drawCircle(color = primary.copy(alpha = 0.55f), radius = r, style = Stroke(2.5f))
            drawCircle(color = outline.copy(alpha = 0.45f), radius = r * 0.65f, style = Stroke(1.5f))
            drawCircle(color = secondary, radius = 8f, center = Offset(cx, cy))
            for (deg in 0 until 360 step 45) {
                val rad = Math.toRadians(deg.toDouble())
                val px = cx + r * 0.65f * cos(rad).toFloat()
                val py = cy + r * 0.65f * sin(rad).toFloat()
                drawCircle(color = primary, radius = 4.5f, center = Offset(px, py))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.empty_sessions_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_sessions_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCreateFirst,
            modifier = Modifier.testTag("empty_create_scene_button")
        ) {
            Icon(Icons.Filled.AddAPhoto, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.new_scene_cta))
        }
    }
}
