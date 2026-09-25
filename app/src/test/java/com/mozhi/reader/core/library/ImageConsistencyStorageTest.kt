package com.mozhi.reader.core.library

import android.app.Application
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.mozhi.reader.ai.media.*
import com.mozhi.reader.core.database.*
import com.mozhi.reader.core.database.entity.*
import java.io.File
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ImageConsistencyStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getDatabasePath(name: String) = File(temporary.root, name)
    }

    @Test fun migration31PreservesIllustrationsAndAddsDurableRecipesAndQueue() = runBlocking {
        val relative = "schemas/com.mozhi.reader.core.database.MoReadDatabase/31.json"
        val schema = Json.parseToJsonElement(listOf(File(relative), File("app/$relative")).first { it.isFile }.readText())
            .jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("migration.db"), null).use { old ->
            schema.getValue("entities").jsonArray.forEach { item ->
                val entity = item.jsonObject
                val name = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                entity["indices"]?.jsonArray.orEmpty().forEach { index -> old.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO books (id,title,author,coverPath,epubPath,sourceType,importedAt,totalChapters,lastReadLocator,lastReadChapterIndex,lastReadCharOffset,lastReadAt,textVersion,tags,metadataEdited) VALUES (1,'Book','',NULL,'','TXT',1,1,NULL,0,0,0,4,'',0)")
            old.execSQL("INSERT INTO illustrations (id,bookId,chapterIndex,charOffset,sourceText,prompt,imagePath,mediaType,pixelWidth,pixelHeight,createdByPersonaId,textAnchorJson,createdAt) VALUES (1,1,0,5,'source','prompt','old.png','image/png',100,200,NULL,'anchor',123)")
            old.version = 31
        }
        val db = Room.databaseBuilder(context, MoReadDatabase::class.java, "migration.db").addMigrations(DatabaseMigrations.Migration31To32).build()
        try {
            val old = db.illustrationDao().get(1)!!
            assertEquals("old.png", old.imagePath)
            assertEquals("anchor", old.textAnchorJson)
            assertEquals("", old.recipeJson)
            assertEquals("[]", old.castKeys)
            val dao = db.imageConsistencyDao()
            dao.saveStyle(BookImageStyleEntity(1, ImageRecipeCodec.json.encodeToString(StyleSpec.serializer(), StyleSpec())))
            val look = LookSpec("look", "stable-key", "Name", referenceIds = listOf("asset"))
            dao.saveLook(CharacterLookEntity("look", 1, "stable-key", 0, ImageRecipeCodec.json.encodeToString(LookSpec.serializer(), look)))
            val recipe = ImageRecipe(cast = listOf(look), seed = 42)
            dao.saveQueue(IllustrationQueueEntity("queue", 1, 0, "source", recipe.encode(), createdAt = 123))
            assertEquals(recipe, ImageRecipeCodec.decode(dao.queue(1).single().recipeJson))
            assertTrue(dao.referenceDocuments().any { "asset" in it })
            val repository = ImageConsistencyRepository(db, mockk(), mockk(), mockk(), mockk(), context)
            val style = StyleSpec(presetId = "custom", natural = "Muted ink on paper", tags = "ink, muted colors",
                negative = "text", seed = 42, referenceIds = listOf("template-reference"), referenceStrength = .45f)
            repository.saveTemplate("My ink style", style)
            val template = dao.observeTemplates().first().single()
            db.bookDao().deleteBook(1)
            assertNull(dao.style(1))
            assertTrue(dao.looks(1).isEmpty())
            assertTrue(dao.queue(1).isEmpty())
            assertEquals(template, dao.template(template.id))
            db.bookDao().insertBook(BookEntity(2, "Another book", "", null, "", BookSourceType.TXT, 1, totalChapters = 1))
            repository.saveStyle(2, ImageRecipeCodec.json.decodeFromString<StyleSpec>(template.specJson))
            assertEquals(style, ImageRecipeCodec.json.decodeFromString<StyleSpec>(template.specJson))
            assertTrue(dao.referenceDocuments().any { "template-reference" in it })
            repository.renameTemplate(template.id, "Evening ink")
            assertEquals(template.specJson, dao.template(template.id)!!.specJson)
            assertEquals("Evening ink", dao.template(template.id)!!.name)
            repository.saveTemplate("Evening ink", style.copy(natural = "Warm ink"), template.id)
            assertEquals(1, dao.observeTemplates().first().size)
            assertEquals("Warm ink", ImageRecipeCodec.json.decodeFromString<StyleSpec>(dao.template(template.id)!!.specJson).natural)
            assertEquals(style, repository.style(2))
            dao.deleteTemplate(template.id)
            assertTrue(dao.observeTemplates().first().isEmpty())
            assertEquals(style, repository.style(2))
            assertTrue(dao.referenceDocuments().any { "template-reference" in it })
        } finally { db.close() }
    }
}
