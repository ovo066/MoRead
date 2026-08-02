package com.mozhi.reader.ai.persona

import com.mozhi.reader.core.database.dao.PersonaDao
import com.mozhi.reader.core.database.entity.PersonaEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 角色卡 CRUD。内置模板由数据种子落库（见 PersonaSeeds），这里不区别对待：
 * 用户可改可删，[save] 只保证 isBuiltIn 与 createdAt 不被调用方伪造/漂移。
 */
@Singleton
class PersonaRepository @Inject constructor(
    private val personaDao: PersonaDao,
    private val avatarStore: PersonaAvatarStore
) {
    fun observePersonas(): Flow<List<PersonaEntity>> = personaDao.observePersonas()

    suspend fun getPersonas(): List<PersonaEntity> = personaDao.getPersonas()

    suspend fun getPersona(personaId: Long): PersonaEntity? = personaDao.getPersona(personaId)

    /** 新建（id = 0）或整卡更新；返回落库 id。换头像时旧头像文件顺手清掉。 */
    suspend fun save(persona: PersonaEntity): Long {
        require(persona.name.isNotBlank()) { "角色名称不能为空" }
        val existing = persona.id.takeIf { it != 0L }?.let { personaDao.getPersona(it) }
        val entity = persona.copy(
            name = persona.name.trim(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            isBuiltIn = existing?.isBuiltIn ?: false
        )
        val savedId = if (existing == null) {
            personaDao.insert(entity.copy(id = 0))
        } else {
            personaDao.update(entity)
            entity.id
        }
        if (existing?.avatarPath != null && existing.avatarPath != entity.avatarPath) {
            avatarStore.delete(existing.avatarPath)
        }
        return savedId
    }

    suspend fun delete(personaId: Long) {
        val existing = personaDao.getPersona(personaId)
        personaDao.delete(personaId)
        existing?.avatarPath?.let(avatarStore::delete)
    }
}
