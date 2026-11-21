package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PanoDao {
    @Transaction
    @Query("SELECT * FROM scenes ORDER BY updatedAtMs DESC")
    fun observeAllScenes(): Flow<List<SceneWithTargets>>

    @Transaction
    @Query("SELECT * FROM scenes WHERE id = :sceneId LIMIT 1")
    fun observeScene(sceneId: String): Flow<SceneWithTargets?>

    @Transaction
    @Query("SELECT * FROM scenes WHERE id = :sceneId LIMIT 1")
    suspend fun getSceneOnce(sceneId: String): SceneWithTargets?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScene(scene: SceneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTargets(targets: List<TargetEntity>)

    @Transaction
    suspend fun insertSceneWithTargets(scene: SceneEntity, targets: List<TargetEntity>) {
        insertScene(scene)
        insertTargets(targets)
    }

    @Update
    suspend fun updateScene(scene: SceneEntity)

    @Update
    suspend fun updateTarget(target: TargetEntity)

    @Query("SELECT * FROM targets WHERE sceneId = :sceneId AND id = :targetId LIMIT 1")
    suspend fun getTarget(sceneId: String, targetId: String): TargetEntity?

    @Query("DELETE FROM scenes WHERE id = :sceneId")
    suspend fun deleteSceneById(sceneId: String)

    @Query(
        """
        UPDATE scenes
        SET hfovDeg = :hfovDeg,
            vfovDeg = :vfovDeg,
            focalLengthMm = :focalLengthMm,
            lensId = :lensId,
            isUltrawide = :isUltrawide,
            aeLockSupported = :aeLockSupported,
            awbLockSupported = :awbLockSupported,
            updatedAtMs = :updatedAtMs
        WHERE id = :sceneId
        """
    )
    suspend fun updateSceneCameraInfo(
        sceneId: String,
        hfovDeg: Float,
        vfovDeg: Float,
        focalLengthMm: Float,
        lensId: String,
        isUltrawide: Boolean,
        aeLockSupported: Boolean,
        awbLockSupported: Boolean,
        updatedAtMs: Long
    )

    @Query("DELETE FROM targets WHERE sceneId = :sceneId")
    suspend fun deleteTargetsForScene(sceneId: String)

    @Transaction
    suspend fun replaceTargetsForScene(sceneId: String, targets: List<TargetEntity>) {
        deleteTargetsForScene(sceneId)
        insertTargets(targets)
    }
}
