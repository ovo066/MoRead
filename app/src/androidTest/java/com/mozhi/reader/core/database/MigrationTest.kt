package com.mozhi.reader.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MoReadDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate2To3KeepsExistingRowsAndDefaultsNewColumns() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO books (
                    id, title, author, coverPath, epubPath, sourceType, importedAt,
                    totalChapters, lastReadLocator, lastReadChapterIndex, lastReadAt
                ) VALUES (
                    1, '测试书籍', '作者', NULL, '/data/books/a.epub', 'TXT', 1000,
                    2, '{"href":"text/chapter-00002.xhtml","locations":{"progression":0.5}}', 1, 2000
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO chapters (id, bookId, chapterIndex, title, href, charCount)
                VALUES (1, 1, 0, '第一章', 'text/chapter-00001.xhtml', 120),
                       (2, 1, 1, '第二章', 'text/chapter-00002.xhtml', 240)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO bookmarks (id, bookId, locatorJson, label, createdAt)
                VALUES (1, 1, '{"href":"text/chapter-00002.xhtml"}', '第二章', 3000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            3,
            true,
            DatabaseMigrations.Migration2To3
        )

        db.query("SELECT lastReadCharOffset, textVersion, lastReadLocator FROM books WHERE id = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.getString(2).contains("chapter-00002"))
            }

        db.query("SELECT textByteOffset, textByteLength, charCount FROM chapters ORDER BY chapterIndex")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(-1L, cursor.getLong(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(120, cursor.getInt(2))
                assertTrue(cursor.moveToNext())
                assertEquals(240, cursor.getInt(2))
            }

        db.query("SELECT chapterIndex, charOffset, excerpt, label FROM bookmarks WHERE id = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals("", cursor.getString(2))
                assertEquals("第二章", cursor.getString(3))
            }
    }

    /** v5 加了用户可编辑的 tags / metadataEdited；存量书必须保留且拿到默认值。 */
    @Test
    fun migrate4To5DefaultsEditableMetadataColumns() {
        helper.createDatabase(DB_NAME, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO books (
                    id, title, author, coverPath, epubPath, sourceType, importedAt,
                    totalChapters, lastReadLocator, lastReadChapterIndex, lastReadCharOffset,
                    lastReadAt, textVersion
                ) VALUES (
                    1, 'Unknown', 'WPS_1532705572', NULL, '/data/books/a.epub', 'EPUB', 1000,
                    3, NULL, 1, 42, 2000, 1
                )
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            5,
            true,
            DatabaseMigrations.Migration4To5
        )

        db.query(
            "SELECT title, author, tags, metadataEdited, lastReadCharOffset FROM books WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            // 迁移不改写既有元数据——清洗只发生在导入时，存量书靠用户手动编辑。
            assertEquals("Unknown", cursor.getString(0))
            assertEquals("WPS_1532705572", cursor.getString(1))
            assertEquals("", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(42, cursor.getInt(4))
        }
    }

    /** v6 把 provider 单模型拆成 ai_models 多模型，分配从 provider 改到 model。 */
    @Test
    fun migrate5To6SplitsModelsAndRetargetsAssignments() {
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO ai_providers
                    (id, name, baseUrl, apiKeyAlias, type, modelName, extraJson, apiFormat, createdAt)
                VALUES
                    (1, 'DeepSeek', 'https://api.deepseek.com', 'alias-1', 'CHAT', 'deepseek-chat', '{}', 'OPENAI', 1000),
                    (2, '未配模型', 'https://api.example.com', 'alias-2', 'CHAT', '', '{}', 'OPENAI', 2000)
                """.trimIndent()
            )
            db.execSQL("INSERT INTO model_assignments (role, providerId) VALUES ('CHAT', 1)")
            db.execSQL("INSERT INTO model_assignments (role, providerId) VALUES ('CHEAP', 2)")
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            6,
            true,
            DatabaseMigrations.Migration5To6
        )

        db.query("SELECT providerId, modelName FROM ai_models").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("deepseek-chat", cursor.getString(1))
            // 空 modelName 的 provider 不产生模型记录。
            assertEquals(1, cursor.count)
        }

        db.query(
            "SELECT a.role, m.modelName FROM model_assignments a " +
                "LEFT JOIN ai_models m ON m.id = a.modelId ORDER BY a.role"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CHAT", cursor.getString(0))
            assertEquals("deepseek-chat", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            // 没有模型可指向的分配置空，而不是悬空引用。
            assertEquals("CHEAP", cursor.getString(0))
            assertEquals(null, cursor.getString(1))
        }

        db.query("SELECT name FROM ai_providers ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)
        }
    }

    /** v7 建 personas/annotations/notes 三表，并落两条内置角色种子。 */
    @Test
    fun migrate6To7CreatesCompanionTablesAndSeedsBuiltInPersonas() {
        helper.createDatabase(DB_NAME, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO books (
                    id, title, author, coverPath, epubPath, sourceType, importedAt,
                    totalChapters, lastReadLocator, lastReadChapterIndex, lastReadCharOffset,
                    lastReadAt, textVersion, tags, metadataEdited
                ) VALUES (
                    1, '测试书籍', '作者', NULL, '/data/books/a.epub', 'TXT', 1000,
                    9, NULL, 3, 42, 2000, 1, '', 0
                )
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            7,
            true,
            DatabaseMigrations.Migration6To7
        )

        db.query(
            "SELECT name, isRoleplay, isBuiltIn, enabledToolsJson FROM personas ORDER BY createdAt"
        ).use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("阅读助手", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
            assertTrue(cursor.getString(3).contains("search_book"))
            assertTrue(cursor.moveToNext())
            // 第二条是扮演型示例角色。
            assertEquals(1, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
        }

        // 新表冒烟：列形状由 validate 保证，这里验证能写能读、可空列语义正确。
        db.execSQL(
            """
            INSERT INTO annotations
                (bookId, personaId, chapterIndex, startCharOffset, endCharOffset,
                 selectedText, note, colorTag, createdAt)
            VALUES (1, NULL, 3, 10, 24, '选中的句子', '一点想法', '', 3000)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO notes
                (bookId, personaId, title, contentMarkdown, relatedChapterIndex,
                 relatedCharOffset, createdAt, updatedAt)
            VALUES (1, 1, '读书笔记', '# 摘要', NULL, NULL, 3000, 3000)
            """.trimIndent()
        )
        db.query(
            "SELECT personaId, startCharOffset, endCharOffset, selectedText FROM annotations"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals(10, cursor.getInt(1))
            assertEquals(24, cursor.getInt(2))
            assertEquals("选中的句子", cursor.getString(3))
        }
        db.query("SELECT title FROM notes WHERE personaId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("读书笔记", cursor.getString(0))
        }
    }

    /** v8：personas 增世界书条目 JSON 与总开关，老行拿到默认值。 */
    @Test
    fun migrate7To8DefaultsWorldBookColumns() {
        helper.createDatabase(DB_NAME, 7).use { db ->
            db.execSQL(
                """
                INSERT INTO personas
                    (name, avatarPath, subtitle, personality, speakingStyle, greeting,
                     exampleDialogsJson, isRoleplay, enabledToolsJson, chatModelId,
                     isBuiltIn, createdAt)
                VALUES ('老角色', NULL, '', '人设', '', '', '[]', 1, '[]', NULL, 0, 1000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            8,
            true,
            DatabaseMigrations.Migration7To8
        )

        db.query(
            "SELECT worldBookJson, worldBookEnabled FROM personas WHERE name = '老角色'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
    }

    /** v9：模型继承旧 Provider 能力，OpenRouter 被识别为专属适配，记忆水位默认 0。 */
    @Test
    fun migrate8To9MovesCapabilityToModelsAndPreservesRows() {
        helper.createDatabase(DB_NAME, 8).use { db ->
            db.execSQL(
                """
                INSERT INTO ai_providers
                    (id, name, baseUrl, apiKeyAlias, type, extraJson, apiFormat, createdAt)
                VALUES
                    (1, 'OpenRouter', 'https://openrouter.ai/api/v1', 'alias-1',
                     'CHAT', '{}', 'OPENAI', 1000),
                    (2, '向量服务', 'https://embed.example.com/v1', 'alias-2',
                     'EMBEDDING', '{}', 'OPENAI', 2000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO ai_models (id, providerId, modelName, createdAt)
                VALUES (1, 1, 'anthropic/claude-sonnet', 1000),
                       (2, 2, 'text-embedding-3-large', 2000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, bookId, personaId, title, type, createdAt)
                VALUES (1, NULL, 7, '旧会话', 'COMPANION', 3000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            9,
            true,
            DatabaseMigrations.Migration8To9
        )

        db.query("SELECT modelName, type, endpointPath, extraJson FROM ai_models ORDER BY id")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("anthropic/claude-sonnet", cursor.getString(0))
                assertEquals("CHAT", cursor.getString(1))
                assertEquals("", cursor.getString(2))
                assertEquals("{}", cursor.getString(3))
                assertTrue(cursor.moveToNext())
                assertEquals("EMBEDDING", cursor.getString(1))
            }

        db.query("SELECT adapter FROM ai_providers ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("OPENROUTER", cursor.getString(0))
            assertTrue(cursor.moveToNext())
            assertEquals("CUSTOM", cursor.getString(0))
        }
        db.query(
            "SELECT memoryConsolidatedThroughMessageId FROM conversations WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
    }

    /** v10：聊天协议可按模型覆盖；存量模型默认继续继承 Provider。 */
    @Test
    fun migrate9To10AddsInheritedModelChatProtocol() {
        helper.createDatabase(DB_NAME, 9).use { db ->
            db.execSQL(
                """
                INSERT INTO ai_providers
                    (id, name, baseUrl, apiKeyAlias, type, extraJson, apiFormat, adapter, createdAt)
                VALUES
                    (1, 'OpenRouter', 'https://openrouter.ai/api/v1', 'alias-1',
                     'CHAT', '{}', 'CLAUDE', 'OPENROUTER', 1000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO ai_models
                    (id, providerId, modelName, type, endpointPath, extraJson, createdAt)
                VALUES
                    (1, 1, 'anthropic/claude-sonnet', 'CHAT', '', '{}', 1000),
                    (2, 1, 'openai/text-embedding-3-small', 'EMBEDDING', '/embeddings', '{}', 2000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            10,
            true,
            DatabaseMigrations.Migration9To10
        )

        db.query("SELECT type, chatApiFormat FROM ai_models ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CHAT", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("EMBEDDING", cursor.getString(0))
            assertEquals("", cursor.getString(1))
        }
        db.query("SELECT apiFormat FROM ai_providers WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CLAUDE", cursor.getString(0))
        }
    }

    /** v11：会话获得分支/最近交互字段，消息获得编辑时间；存量时间顺序不变。 */
    @Test
    fun migrate10To11AddsConversationClientMetadata() {
        helper.createDatabase(DB_NAME, 10).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, bookId, personaId, title, type,
                     memoryConsolidatedThroughMessageId, createdAt)
                VALUES (1, NULL, 7, '旧会话', 'COMPANION', 42, 3000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, role, content, toolCallsJson, toolCallId,
                     tokenUsage, createdAt)
                VALUES (9, 1, 'user', '旧消息', NULL, NULL, NULL, 3100)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            11,
            true,
            DatabaseMigrations.Migration10To11
        )

        db.query(
            "SELECT parentConversationId, branchedFromMessageId, updatedAt, " +
                "memoryConsolidatedThroughMessageId FROM conversations WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertEquals(3000L, cursor.getLong(2))
            assertEquals(42L, cursor.getLong(3))
        }
        db.query("SELECT content, editedAt FROM messages WHERE id = 9").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧消息", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
    }

    /** v12：旧笔记默认保持普通 NOTE，梗概来源会话列可空。 */
    @Test
    fun migrate11To12ClassifiesExistingNotesAsRegularNotes() {
        helper.createDatabase(DB_NAME, 11).use { db ->
            db.execSQL(
                """
                INSERT INTO books (
                    id, title, author, coverPath, epubPath, sourceType, importedAt,
                    totalChapters, lastReadLocator, lastReadChapterIndex, lastReadCharOffset,
                    lastReadAt, textVersion, tags, metadataEdited
                ) VALUES (1, '测试书', '', NULL, '/book.epub', 'EPUB', 1,
                          3, NULL, 1, 0, 2, 1, '', 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO notes
                    (id, bookId, personaId, title, contentMarkdown, relatedChapterIndex,
                     relatedCharOffset, createdAt, updatedAt)
                VALUES (1, 1, NULL, '旧笔记', '# 内容', 1, 0, 10, 10)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            12,
            true,
            DatabaseMigrations.Migration11To12
        )
        db.query("SELECT kind, sourceConversationId FROM notes WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("NOTE", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
    }

    /** v13：新增插图廊，且仅给内置角色追加新实现的工具，不改用户角色白名单。 */
    @Test
    fun migrate12To13CreatesGalleryAndExtendsOnlyBuiltInPersonaTools() {
        helper.createDatabase(DB_NAME, 12).use { db ->
            db.execSQL(
                """
                INSERT INTO books (
                    id, title, author, coverPath, epubPath, sourceType, importedAt,
                    totalChapters, lastReadLocator, lastReadChapterIndex, lastReadCharOffset,
                    lastReadAt, textVersion, tags, metadataEdited
                ) VALUES (1, '测试书', '', NULL, '/book.epub', 'EPUB', 1,
                          3, NULL, 1, 20, 2, 1, '', 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO personas (
                    id, name, avatarPath, subtitle, personality, speakingStyle, greeting,
                    exampleDialogsJson, isRoleplay, enabledToolsJson, worldBookJson,
                    worldBookEnabled, chatModelId, isBuiltIn, createdAt
                ) VALUES
                    (1, '内置', NULL, '', '人设', '', '', '[]', 0, '["search_book"]',
                     '[]', 1, NULL, 1, 1),
                    (2, '用户', NULL, '', '人设', '', '', '[]', 0, '["search_book"]',
                     '[]', 1, NULL, 0, 1)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            13,
            true,
            DatabaseMigrations.Migration12To13
        )
        db.execSQL(
            """
            INSERT INTO illustrations (
                bookId, chapterIndex, charOffset, sourceText, prompt, imagePath, mediaType,
                pixelWidth, pixelHeight, createdByPersonaId, createdAt
            ) VALUES (1, 1, 10, '原文', '提示词', '/files/a.png', 'image/png', 800, 600, 1, 10)
            """.trimIndent()
        )
        db.query("SELECT COUNT(*) FROM illustrations WHERE bookId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT enabledToolsJson FROM personas ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            val builtIn = cursor.getString(0)
            assertTrue(builtIn.contains("generate_image"))
            assertTrue(builtIn.contains("synthesize_speech"))
            assertTrue(cursor.moveToNext())
            assertEquals("[\"search_book\"]", cursor.getString(0))
        }
    }

    /** v14：messages 新增 attachmentsJson，可空且旧行保持 null。 */
    @Test
    fun migrate13To14AddsNullableAttachmentsColumn() {
        helper.createDatabase(DB_NAME, 13).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations (id, bookId, personaId, title, type, createdAt, updatedAt)
                VALUES (1, NULL, NULL, '会话', 'COMPANION', 1, 1)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO messages (id, conversationId, role, content, createdAt)
                VALUES (1, 1, 'user', '旧消息', 1)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            14,
            true,
            DatabaseMigrations.Migration13To14
        )
        db.query("SELECT attachmentsJson FROM messages WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        db.execSQL(
            """
            INSERT INTO messages (conversationId, role, content, createdAt, attachmentsJson)
            VALUES (1, 'user', '带附件', 2, '[{"type":"image","path":"attachments/1/a.jpg","mime":"image/jpeg"}]')
            """.trimIndent()
        )
        db.query("SELECT COUNT(*) FROM messages WHERE attachmentsJson IS NOT NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    /** v15：批注补样式列（旧行回填 HIGHLIGHT），并建讨论串回复表与创作两表（级联删除生效）。 */
    @Test
    fun migrate14To15AddsAnnotationStyleAndCompanionPhase2Tables() {
        helper.createDatabase(DB_NAME, 14).use { db ->
            db.execSQL(
                """
                INSERT INTO books (
                    id, title, author, coverPath, epubPath, sourceType, importedAt,
                    totalChapters, lastReadLocator, lastReadChapterIndex, lastReadCharOffset,
                    lastReadAt, textVersion, tags, metadataEdited
                ) VALUES (1, '测试书', '', NULL, '/book.epub', 'EPUB', 1,
                          3, NULL, 1, 20, 2, 1, '', 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO annotations (
                    id, bookId, personaId, chapterIndex, startCharOffset, endCharOffset,
                    selectedText, note, colorTag, createdAt
                ) VALUES (1, 1, NULL, 0, 5, 12, '一段旧划线', '旧想法', '', 1)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            15,
            true,
            DatabaseMigrations.Migration14To15
        )
        db.query("SELECT style FROM annotations WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("HIGHLIGHT", cursor.getString(0))
        }
        db.execSQL(
            """
            INSERT INTO annotation_replies (annotationId, personaId, replyToId, contentMarkdown, createdAt)
            VALUES (1, NULL, NULL, '用户的讨论回复', 2)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO ai_creations (
                id, bookId, type, chapterIndex, startCharOffset, endCharOffset,
                directive, activeVersionId, personaId, createdAt
            ) VALUES (1, 1, 'CONTINUE', 1, 20, 20, '往温柔的方向写', NULL, NULL, 3)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO ai_creation_versions (creationId, ord, directive, content, status, modelName, createdAt)
            VALUES (1, 1, '往温柔的方向写', '（生成中）', 'STREAMING', 'test-model', 3)
            """.trimIndent()
        )
        // 外键级联：删除批注应带走回复，删除创作应带走版本
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM annotations WHERE id = 1")
        db.query("SELECT COUNT(*) FROM annotation_replies").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.execSQL("DELETE FROM ai_creations WHERE id = 1")
        db.query("SELECT COUNT(*) FROM ai_creation_versions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    /** v16：书架阅读状态与置顶两列；老书按「未标记 + 未置顶」进来，仍由进度推导状态。 */
    @Test
    fun migrate15To16AddsReadStateAndPinColumns() {
        helper.createDatabase(DB_NAME, 15).use { db ->
            db.execSQL(
                """
                INSERT INTO books (
                    id, title, author, coverPath, epubPath, sourceType, importedAt,
                    totalChapters, lastReadLocator, lastReadChapterIndex, lastReadCharOffset,
                    lastReadAt, textVersion, tags, metadataEdited
                ) VALUES (1, '测试书', '', NULL, '/book.epub', 'EPUB', 1,
                          3, NULL, 2, 20, 2, 1, '玄幻', 0)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            16,
            true,
            DatabaseMigrations.Migration15To16
        )
        db.query("SELECT manualReadState, pinnedAt FROM books WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("老书不应带手动标记", cursor.isNull(0))
            assertEquals(0L, cursor.getLong(1))
        }
        db.execSQL("UPDATE books SET manualReadState = 'SHELVED', pinnedAt = 42 WHERE id = 1")
        db.query("SELECT manualReadState, pinnedAt FROM books WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("SHELVED", cursor.getString(0))
            assertEquals(42L, cursor.getLong(1))
        }
    }

    /** v17：记忆 2.0 与伴读外观一次建齐；老会话/老角色/老消息全部拿到默认值。 */
    @Test
    fun migrate16To17AddsMemoryAndAppearanceColumns() {
        helper.createDatabase(DB_NAME, 16).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations (
                    id, bookId, personaId, title, type, parentConversationId,
                    branchedFromMessageId, memoryConsolidatedThroughMessageId, createdAt, updatedAt
                ) VALUES (1, NULL, 7, '旧会话', 'COMPANION', NULL, NULL, 42, 1000, 1000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO messages (id, conversationId, role, content, createdAt)
                VALUES (1, 1, 'user', '旧消息', 1000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO personas (
                    id, name, avatarPath, subtitle, personality, speakingStyle, greeting,
                    exampleDialogsJson, isRoleplay, enabledToolsJson, worldBookJson,
                    worldBookEnabled, chatModelId, isBuiltIn, createdAt
                ) VALUES (1, '老角色', NULL, '', '人设', '', '', '[]', 1, '[]', '[]', 1, NULL, 0, 1)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            17,
            true,
            DatabaseMigrations.Migration16To17
        )

        db.query(
            "SELECT rollingSummary, summarizedThroughMessageId, " +
                "memoryConsolidatedThroughMessageId FROM conversations WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
            assertEquals(0L, cursor.getLong(1))
            // 固化水位与摘要水位互不干扰，老水位必须原样保留。
            assertEquals(42L, cursor.getLong(2))
        }
        db.query("SELECT maskId, content FROM messages WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals("旧消息", cursor.getString(1))
        }
        db.query(
            "SELECT userProfile, memoryEnabled, chatAppearanceJson FROM personas WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
            // 记忆是既有能力，迁移后必须保持开启，否则等于静默关掉老用户的长期记忆。
            assertEquals(1, cursor.getInt(1))
            assertEquals("{}", cursor.getString(2))
        }
    }

    @Test
    fun migrate17To18BackfillsNormalizedTagsAndGroupColumn() {
        helper.createDatabase(DB_NAME, 17).use { db ->
            db.execSQL(
                """
                INSERT INTO books (
                    id, title, author, coverPath, epubPath, sourceType, importedAt,
                    totalChapters, lastReadLocator, lastReadChapterIndex, lastReadCharOffset,
                    lastReadAt, textVersion, tags, metadataEdited, manualReadState, pinnedAt
                ) VALUES (
                    1, '旧书', '作者', NULL, '/data/books/old.epub', 'EPUB', 1000,
                    1, NULL, 0, 0, 0, 1, '玄幻, 修仙,,玄幻 ', 0, NULL, 0
                )
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            18,
            true,
            DatabaseMigrations.Migration17To18
        )

        db.query("SELECT groupId, tags FROM books WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals("玄幻, 修仙,,玄幻 ", cursor.getString(1))
        }
        db.query("SELECT name, colorTag FROM book_tags ORDER BY name").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("修仙", cursor.getString(0))
            assertTrue(cursor.getString(1).isNotBlank())
            assertTrue(cursor.moveToNext())
            assertEquals("玄幻", cursor.getString(0))
        }
        db.query("SELECT COUNT(*) FROM book_tag_refs WHERE bookId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test
    fun migrate18To19CreatesVoiceAndAudiobookTables() {
        helper.createDatabase(DB_NAME, 18).close()

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            19,
            true,
            DatabaseMigrations.Migration18To19
        )

        val expectedTables = listOf(
            "tts_voices",
            "audiobook_roles",
            "audiobook_segments",
            "audiobook_chapters"
        )
        expectedTables.forEach { table ->
            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("missing table $table", 1, cursor.getInt(0))
            }
        }
    }

    /** v20：伴读三期五列一次加齐；老消息/老角色/老批注/老回复全部拿到默认值且原内容不动。 */
    @Test
    fun migrate19To20AddsReasoningVoiceAndMediaColumns() {
        helper.createDatabase(DB_NAME, 19).use { db ->
            db.execSQL(
                """
                INSERT INTO books (
                    id, title, author, coverPath, epubPath, sourceType, importedAt,
                    totalChapters, lastReadLocator, lastReadChapterIndex, lastReadCharOffset,
                    lastReadAt, textVersion, tags, metadataEdited, manualReadState, pinnedAt, groupId
                ) VALUES (
                    1, '旧书', '作者', NULL, '/data/books/old.epub', 'EPUB', 1000,
                    1, NULL, 0, 0, 0, 1, '', 0, NULL, 0, NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO conversations (
                    id, bookId, personaId, title, type, parentConversationId,
                    branchedFromMessageId, memoryConsolidatedThroughMessageId,
                    rollingSummary, summarizedThroughMessageId, createdAt, updatedAt
                ) VALUES (1, 1, 1, '旧会话', 'COMPANION', NULL, NULL, 0, '', 0, 1000, 1000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO messages (id, conversationId, role, content, createdAt, maskId)
                VALUES (1, 1, 'assistant', '旧回复', 1000, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO personas (
                    id, name, avatarPath, subtitle, personality, speakingStyle, greeting,
                    exampleDialogsJson, isRoleplay, enabledToolsJson, worldBookJson,
                    worldBookEnabled, chatModelId, userProfile, memoryEnabled,
                    chatAppearanceJson, isBuiltIn, createdAt
                ) VALUES (
                    1, '老角色', NULL, '', '人设', '', '', '[]', 1, '[]', '[]', 1, NULL,
                    '', 1, '{}', 0, 1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO annotations (
                    id, bookId, personaId, chapterIndex, startCharOffset, endCharOffset,
                    selectedText, note, colorTag, style, createdAt
                ) VALUES (1, 1, 1, 0, 10, 20, '选中的原文', '旧批注', 'amber', 'WAVY', 1000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO annotation_replies (
                    id, annotationId, personaId, replyToId, contentMarkdown, createdAt
                ) VALUES (1, 1, NULL, NULL, '旧回复层', 2000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            20,
            true,
            DatabaseMigrations.Migration19To20
        )

        // 思维链是可空列：老消息必须是 NULL 而不是空串，界面据此判断「整条不出现」。
        db.query("SELECT reasoningContent, content FROM messages WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals("旧回复", cursor.getString(1))
        }
        db.query("SELECT voiceId, voiceEmotion, name FROM personas WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // 空音色 = 老角色迁移后不会突然开始发语音（付费调用不得静默生效）。
            assertEquals("", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals("老角色", cursor.getString(2))
        }
        db.query("SELECT mediaJson, note, style FROM annotations WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{}", cursor.getString(0))
            assertEquals("旧批注", cursor.getString(1))
            assertEquals("WAVY", cursor.getString(2))
        }
        db.query(
            "SELECT mediaJson, contentMarkdown FROM annotation_replies WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{}", cursor.getString(0))
            assertEquals("旧回复层", cursor.getString(1))
        }
    }

    @Test
    fun migrate20To21CreatesHierarchicalTocTable() {
        helper.createDatabase(DB_NAME, 20).use { db ->
            db.execSQL(
                """
                INSERT INTO books (
                    id, title, author, coverPath, epubPath, sourceType, importedAt,
                    totalChapters, lastReadLocator, lastReadChapterIndex, lastReadCharOffset,
                    lastReadAt, textVersion, tags, metadataEdited, manualReadState, pinnedAt, groupId
                ) VALUES (
                    1, '旧 EPUB', '作者', NULL, '/data/books/old.epub', 'EPUB', 1000,
                    1, NULL, 0, 0, 0, 2, '', 0, NULL, 0, NULL
                )
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            21,
            true,
            DatabaseMigrations.Migration20To21
        )
        db.execSQL(
            """
            INSERT INTO book_toc_entries (
                bookId, orderIndex, title, href, depth, parentOrderIndex, chapterIndex, hasChildren
            ) VALUES
                (1, 0, '卷一', 'Text/part.xhtml', 0, NULL, 0, 1),
                (1, 1, '第一章', 'Text/chapter.xhtml', 1, 0, 0, 0)
            """.trimIndent()
        )
        db.query(
            """
            SELECT title, depth, parentOrderIndex, chapterIndex, hasChildren
            FROM book_toc_entries
            WHERE bookId = 1
            ORDER BY orderIndex
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("卷一", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(1, cursor.getInt(4))
            assertTrue(cursor.moveToNext())
            assertEquals("第一章", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
