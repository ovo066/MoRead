package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mozhi.reader.core.database.entity.PersonaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {
    @Insert
    suspend fun insert(persona: PersonaEntity): Long

    @Update
    suspend fun update(persona: PersonaEntity)

    @Query("DELETE FROM personas WHERE id = :personaId")
    suspend fun delete(personaId: Long)

    @Query("SELECT * FROM personas WHERE id = :personaId")
    suspend fun getPersona(personaId: Long): PersonaEntity?

    @Query("SELECT * FROM personas ORDER BY createdAt ASC")
    fun observePersonas(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas ORDER BY createdAt ASC")
    suspend fun getPersonas(): List<PersonaEntity>

    /** 画像由固化任务整段覆盖式改写，不走 @Update 以免和用户正在编辑的角色卡互相盖写。 */
    @Query("UPDATE personas SET userProfile = :profile WHERE id = :personaId")
    suspend fun updateUserProfile(personaId: Long, profile: String)

    @Query("UPDATE personas SET memoryEnabled = :enabled WHERE id = :personaId")
    suspend fun updateMemoryEnabled(personaId: Long, enabled: Boolean)

    @Query("UPDATE personas SET chatAppearanceJson = :json WHERE id = :personaId")
    suspend fun updateChatAppearance(personaId: Long, json: String)
}
