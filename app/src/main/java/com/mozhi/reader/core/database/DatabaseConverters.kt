package com.mozhi.reader.core.database

import androidx.room.TypeConverter
import com.mozhi.reader.core.database.entity.AiProviderType
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.ModelRole

class DatabaseConverters {
    @TypeConverter
    fun bookSourceTypeToString(value: BookSourceType): String = value.name

    @TypeConverter
    fun stringToBookSourceType(value: String): BookSourceType = BookSourceType.valueOf(value)

    @TypeConverter
    fun providerTypeToString(value: AiProviderType): String = value.name

    @TypeConverter
    fun stringToProviderType(value: String): AiProviderType = AiProviderType.valueOf(value)

    @TypeConverter
    fun providerAdapterToString(value: AiProviderAdapter): String = value.name

    @TypeConverter
    fun stringToProviderAdapter(value: String): AiProviderAdapter =
        AiProviderAdapter.entries.firstOrNull { it.name == value } ?: AiProviderAdapter.CUSTOM

    @TypeConverter
    fun modelTypeToString(value: AiModelType): String = value.name

    @TypeConverter
    fun stringToModelType(value: String): AiModelType = AiModelType.valueOf(value)

    @TypeConverter
    fun modelRoleToString(value: ModelRole): String = value.name

    @TypeConverter
    fun stringToModelRole(value: String): ModelRole = ModelRole.valueOf(value)
}
