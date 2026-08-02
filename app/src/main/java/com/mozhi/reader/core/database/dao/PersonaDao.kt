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
}
