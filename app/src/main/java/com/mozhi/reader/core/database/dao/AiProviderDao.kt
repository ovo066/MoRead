package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.ModelAssignmentEntity
import com.mozhi.reader.core.database.entity.ModelRole
import kotlinx.coroutines.flow.Flow

@Dao
interface AiProviderDao {
    @Query("SELECT * FROM ai_providers ORDER BY createdAt DESC")
    fun observeProviders(): Flow<List<AiProviderEntity>>

    @Query("SELECT * FROM ai_providers WHERE id = :providerId")
    suspend fun getProvider(providerId: Long): AiProviderEntity?

    @Insert
    suspend fun insertProvider(provider: AiProviderEntity): Long

    @Update
    suspend fun updateProvider(provider: AiProviderEntity)

    @Delete
    suspend fun deleteProvider(provider: AiProviderEntity)

    // ---- models ----

    @Query("SELECT * FROM ai_models ORDER BY providerId, createdAt")
    fun observeModels(): Flow<List<AiModelEntity>>

    @Query("SELECT * FROM ai_models WHERE providerId = :providerId ORDER BY createdAt")
    suspend fun getModels(providerId: Long): List<AiModelEntity>

    @Query("SELECT * FROM ai_models WHERE id = :modelId")
    suspend fun getModel(modelId: Long): AiModelEntity?

    @Query(
        "SELECT * FROM ai_models WHERE providerId = :providerId " +
            "AND modelName = :modelName AND type = :type LIMIT 1"
    )
    suspend fun findModel(
        providerId: Long,
        modelName: String,
        type: AiModelType
    ): AiModelEntity?

    @Insert
    suspend fun insertModel(model: AiModelEntity): Long

    @Update
    suspend fun updateModel(model: AiModelEntity)

    @Query("DELETE FROM ai_models WHERE id = :modelId")
    suspend fun deleteModel(modelId: Long)

    // ---- assignments ----

    @Query("SELECT * FROM model_assignments")
    fun observeAssignments(): Flow<List<ModelAssignmentEntity>>

    @Query("SELECT * FROM model_assignments WHERE role = :role")
    suspend fun getAssignment(role: ModelRole): ModelAssignmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssignment(assignment: ModelAssignmentEntity)

    @Query(
        """
        UPDATE model_assignments SET modelId = NULL
        WHERE modelId IN (SELECT id FROM ai_models WHERE providerId = :providerId)
        """
    )
    suspend fun clearAssignmentsForProvider(providerId: Long)

    @Query("UPDATE model_assignments SET modelId = NULL WHERE modelId = :modelId")
    suspend fun clearAssignmentsForModel(modelId: Long)
}
