package com.example.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class SessionStatus {
    IN_PROGRESS,
    EXPORTED
}

@Entity(tableName = "scenes")
data class SceneEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val status: String = SessionStatus.IN_PROGRESS.name,
    val hfovDeg: Float,
    val vfovDeg: Float,
    val overlapTarget: Float,
    val focalLengthMm: Float,
    val lensId: String,
    val aeLocked: Boolean = false,
    val awbLocked: Boolean = false,
    val aeLockSupported: Boolean = true,
    val awbLockSupported: Boolean = true,
    val sensorSource: String = "UNKNOWN",
    val nextCaptureOrder: Int = 1,
    val lastExportUri: String? = null,
    val lastExportDisplayName: String? = null,
    val isSimulated: Boolean = false
)

@Entity(
    tableName = "targets",
    primaryKeys = ["sceneId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = SceneEntity::class,
            parentColumns = ["id"],
            childColumns = ["sceneId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sceneId")]
)
data class TargetEntity(
    val sceneId: String,
    val id: String,
    val orderIndex: Int,
    val ringType: String,
    val targetYawDeg: Float,
    val targetPitchDeg: Float,
    val targetDirX: Float,
    val targetDirY: Float,
    val targetDirZ: Float,
    val captured: Boolean = false,
    val photoFilePath: String? = null,
    val thumbFilePath: String? = null,
    val captureOrder: Int? = null,
    val capturedAtMs: Long? = null,
    val actualDirX: Float? = null,
    val actualDirY: Float? = null,
    val actualDirZ: Float? = null,
    val actualUpX: Float? = null,
    val actualUpY: Float? = null,
    val actualUpZ: Float? = null,
    val quatW: Float? = null,
    val quatX: Float? = null,
    val quatY: Float? = null,
    val quatZ: Float? = null,
    val actualYawDeg: Float? = null,
    val actualPitchDeg: Float? = null,
    val actualRollDeg: Float? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null
)

data class SceneWithTargets(
    @Embedded val scene: SceneEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "sceneId"
    )
    val targets: List<TargetEntity>
)
