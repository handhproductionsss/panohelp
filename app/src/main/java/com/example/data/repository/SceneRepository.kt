package com.example.data.repository

import android.content.Context
import android.os.StatFs
import com.example.data.local.PanoDao
import com.example.data.local.SceneEntity
import com.example.data.local.SceneWithTargets
import com.example.data.local.SessionStatus
import com.example.data.local.TargetEntity
import com.example.domain.capture.CaptureTargetState
import com.example.domain.capture.RingType
import com.example.domain.capture.TargetPlanner
import com.example.domain.math.CameraPose
import com.example.domain.math.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SceneRepository(
    private val context: Context,
    private val dao: PanoDao
) {
    val allScenes: Flow<List<SceneWithTargets>> = dao.observeAllScenes()

    fun observeScene(sceneId: String): Flow<SceneWithTargets?> = dao.observeScene(sceneId)

    suspend fun getSceneOnce(sceneId: String): SceneWithTargets? = dao.getSceneOnce(sceneId)

    suspend fun createScene(
        name: String,
        isSimulated: Boolean,
        hfovDeg: Float = if (isSimulated) 74.0f else TargetPlanner.DEFAULT_PORTRAIT_HFOV_DEG,
        vfovDeg: Float = if (isSimulated) 92.0f else TargetPlanner.DEFAULT_PORTRAIT_VFOV_DEG,
        focalLengthMm: Float = if (isSimulated) 2.2f else 4.2f,
        lensId: String = if (isSimulated) "simulated_ultrawide" else "0",
        isUltrawide: Boolean = isSimulated
    ): String = withContext(Dispatchers.IO) {
        val sceneId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cleanName = name.trim().ifEmpty { "Untitled Scene" }

        val plan = TargetPlanner.computeCoveragePlan(
            hfovDeg = hfovDeg,
            vfovDeg = vfovDeg,
            targetOverlap = TargetPlanner.DEFAULT_TARGET_OVERLAP,
            includeCeiling = true
        )

        val scene = SceneEntity(
            id = sceneId,
            name = cleanName,
            createdAtMs = now,
            updatedAtMs = now,
            status = SessionStatus.IN_PROGRESS.name,
            hfovDeg = hfovDeg,
            vfovDeg = vfovDeg,
            overlapTarget = TargetPlanner.DEFAULT_TARGET_OVERLAP,
            focalLengthMm = focalLengthMm,
            lensId = lensId,
            sensorSource = if (isSimulated) "SIMULATED" else "GAME_ROTATION_VECTOR",
            isSimulated = isSimulated,
            isUltrawide = isUltrawide
        )

        val targetEntities = plan.map { p ->
            TargetEntity(
                sceneId = sceneId,
                id = p.id,
                orderIndex = p.orderIndex,
                ringType = p.ringType.name,
                targetYawDeg = p.targetYawDeg,
                targetPitchDeg = p.targetPitchDeg,
                targetDirX = p.targetDir.x,
                targetDirY = p.targetDir.y,
                targetDirZ = p.targetDir.z
            )
        }

        sceneDir(sceneId).mkdirs()
        dao.insertSceneWithTargets(scene, targetEntities)
        sceneId
    }

    /**
     * Once the real camera opens on the first visit (before any photos are captured),
     * regenerate the target plan using the widest back camera's true optical FOV.
     */
    suspend fun updateCameraSpecsAndReplanIfUntouched(
        sceneId: String,
        hfovDeg: Float,
        vfovDeg: Float,
        focalLengthMm: Float,
        lensId: String,
        isUltrawide: Boolean,
        aeLockSupported: Boolean,
        awbLockSupported: Boolean
    ) = withContext(Dispatchers.IO) {
        val current = dao.getSceneOnce(sceneId) ?: return@withContext
        val now = System.currentTimeMillis()
        dao.updateSceneCameraInfo(
            sceneId = sceneId,
            hfovDeg = hfovDeg,
            vfovDeg = vfovDeg,
            focalLengthMm = focalLengthMm,
            lensId = lensId,
            isUltrawide = isUltrawide,
            aeLockSupported = aeLockSupported,
            awbLockSupported = awbLockSupported,
            updatedAtMs = now
        )

        val hasCapturedAny = current.targets.any { it.captured }
        if (!hasCapturedAny) {
            val newPlan = TargetPlanner.computeCoveragePlan(
                hfovDeg = hfovDeg,
                vfovDeg = vfovDeg,
                targetOverlap = current.scene.overlapTarget,
                includeCeiling = true
            )
            val newEntities = newPlan.map { p ->
                TargetEntity(
                    sceneId = sceneId,
                    id = p.id,
                    orderIndex = p.orderIndex,
                    ringType = p.ringType.name,
                    targetYawDeg = p.targetYawDeg,
                    targetPitchDeg = p.targetPitchDeg,
                    targetDirX = p.targetDir.x,
                    targetDirY = p.targetDir.y,
                    targetDirZ = p.targetDir.z
                )
            }
            dao.replaceTargetsForScene(sceneId, newEntities)
        }
    }

    fun photoFilesForTarget(sceneId: String, targetId: String): Pair<File, File> {
        val dir = sceneDir(sceneId)
        dir.mkdirs()
        val photo = File(dir, "target_${targetId}.jpg")
        val thumb = File(dir, "thumb_${targetId}.jpg")
        return Pair(photo, thumb)
    }

    suspend fun recordTargetCapture(
        sceneId: String,
        targetId: String,
        photoFile: File,
        thumbFile: File,
        width: Int,
        height: Int,
        capturedAtMs: Long,
        pose: CameraPose,
        aeLocked: Boolean,
        awbLocked: Boolean,
        sensorSource: String
    ) = withContext(Dispatchers.IO) {
        val current = dao.getSceneOnce(sceneId) ?: return@withContext
        val existingTarget = current.targets.firstOrNull { it.id == targetId } ?: return@withContext

        val assignedOrder = existingTarget.captureOrder ?: current.scene.nextCaptureOrder
        val nextOrder = if (existingTarget.captureOrder == null) {
            current.scene.nextCaptureOrder + 1
        } else {
            current.scene.nextCaptureOrder
        }

        val updatedTarget = existingTarget.copy(
            captured = true,
            photoFilePath = photoFile.absolutePath,
            thumbFilePath = thumbFile.absolutePath,
            captureOrder = assignedOrder,
            capturedAtMs = capturedAtMs,
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
            imageWidth = width,
            imageHeight = height
        )

        dao.updateTarget(updatedTarget)
        dao.updateScene(
            current.scene.copy(
                updatedAtMs = capturedAtMs,
                nextCaptureOrder = nextOrder,
                aeLocked = aeLocked,
                awbLocked = awbLocked,
                sensorSource = sensorSource
            )
        )
    }

    suspend fun markExported(
        sceneId: String,
        exportUri: String,
        exportDisplayName: String
    ) = withContext(Dispatchers.IO) {
        val current = dao.getSceneOnce(sceneId) ?: return@withContext
        dao.updateScene(
            current.scene.copy(
                status = SessionStatus.EXPORTED.name,
                updatedAtMs = System.currentTimeMillis(),
                lastExportUri = exportUri,
                lastExportDisplayName = exportDisplayName
            )
        )
    }

    suspend fun markStitched(
        sceneId: String,
        localStitchedPath: String,
        savedPublicName: String
    ) = withContext(Dispatchers.IO) {
        val current = dao.getSceneOnce(sceneId) ?: return@withContext
        dao.updateScene(
            current.scene.copy(
                updatedAtMs = System.currentTimeMillis(),
                stitchedImagePath = localStitchedPath,
                stitchedSavedName = savedPublicName
            )
        )
    }

    suspend fun deleteScene(sceneId: String) = withContext(Dispatchers.IO) {
        dao.deleteSceneById(sceneId)
        sceneDir(sceneId).deleteRecursively()
    }

    fun getAvailableStorageMb(): Long {
        return runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes / (1024L * 1024L)
        }.getOrDefault(1024L)
    }

    private fun sceneDir(sceneId: String): File = File(File(context.filesDir, "scenes"), sceneId)

    companion object {
        fun TargetEntity.toCaptureState(): CaptureTargetState = CaptureTargetState(
            id = id,
            orderIndex = orderIndex,
            ringType = runCatching { RingType.valueOf(ringType) }.getOrDefault(RingType.EYE_LEVEL),
            targetYawDeg = targetYawDeg,
            targetPitchDeg = targetPitchDeg,
            targetDir = Vec3(targetDirX, targetDirY, targetDirZ),
            captured = captured,
            photoPath = thumbFilePath ?: photoFilePath,
            captureOrder = captureOrder
        )
    }
}
