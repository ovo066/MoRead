package com.mozhi.reader.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val Migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reading_daily` (
                    `bookId` INTEGER NOT NULL,
                    `epochDay` INTEGER NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `lastReadAt` INTEGER NOT NULL,
                    PRIMARY KEY(`bookId`, `epochDay`),
                    FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_daily_bookId` ON `reading_daily` (`bookId`)"
            )
        }
    }

    /**
     * Additive only. minSdk 26 can ship SQLite older than 3.25, which has neither `RENAME COLUMN`
     * nor `DROP COLUMN`, so the Readium locator columns stay until a later table-recreating
     * migration removes them.
     */
    val Migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `chapters` ADD COLUMN `textByteOffset` INTEGER NOT NULL DEFAULT -1"
            )
            db.execSQL(
                "ALTER TABLE `chapters` ADD COLUMN `textByteLength` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE `books` ADD COLUMN `lastReadCharOffset` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("ALTER TABLE `books` ADD COLUMN `textVersion` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "ALTER TABLE `bookmarks` ADD COLUMN `chapterIndex` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `charOffset` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `excerpt` TEXT NOT NULL DEFAULT ''")
        }
    }

    /** M1: conversations/messages for selection AI, plus the provider API dialect column. */
    val Migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `ai_providers` ADD COLUMN `apiFormat` TEXT NOT NULL DEFAULT 'OPENAI'"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` INTEGER,
                    `personaId` INTEGER,
                    `title` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversations_bookId` ON `conversations` (`bookId`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `role` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `toolCallsJson` TEXT,
                    `toolCallId` TEXT,
                    `tokenUsage` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)"
            )
        }
    }

    /** M2: 用户可编辑的书籍元数据 —— 自定义标签，以及「已手改」标记。 */
    val Migration4To5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `books` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "ALTER TABLE `books` ADD COLUMN `metadataEdited` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * RikkaHub 化：一个 Provider 挂多个模型（新表 ai_models），角色分配从
     * role→provider 改为 role→model。原 provider.modelName 拆成首条模型记录，
     * 原分配 JOIN 到对应模型上。
     */
    val Migration5To6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_models` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `providerId` INTEGER NOT NULL,
                    `modelName` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`providerId`) REFERENCES `ai_providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_models_providerId` ON `ai_models` (`providerId`)"
            )
            db.execSQL(
                """
                INSERT INTO `ai_models` (`providerId`, `modelName`, `createdAt`)
                SELECT `id`, `modelName`, `createdAt` FROM `ai_providers` WHERE `modelName` != ''
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE `model_assignments_new` (
                    `role` TEXT NOT NULL,
                    `modelId` INTEGER,
                    PRIMARY KEY(`role`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `model_assignments_new` (`role`, `modelId`)
                SELECT a.`role`, m.`id` FROM `model_assignments` a
                LEFT JOIN `ai_models` m ON m.`providerId` = a.`providerId`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `model_assignments`")
            db.execSQL("ALTER TABLE `model_assignments_new` RENAME TO `model_assignments`")
            db.execSQL(
                """
                CREATE TABLE `ai_providers_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `apiKeyAlias` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `extraJson` TEXT NOT NULL,
                    `apiFormat` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `ai_providers_new`
                    (`id`, `name`, `baseUrl`, `apiKeyAlias`, `type`, `extraJson`, `apiFormat`, `createdAt`)
                SELECT `id`, `name`, `baseUrl`, `apiKeyAlias`, `type`, `extraJson`, `apiFormat`, `createdAt`
                FROM `ai_providers`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `ai_providers`")
            db.execSQL("ALTER TABLE `ai_providers_new` RENAME TO `ai_providers`")
        }
    }

    /**
     * M2 后端第一步：伴读角色卡 + 批注 + 读书笔记三表。
     * 批注/笔记的定位走 (chapterIndex, charOffset) 新轨坐标，不带 locatorJson 遗留列；
     * personaId 不设 FK（角色删除后内容留存）。迁移同时落两条内置角色种子。
     */
    val Migration6To7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `personas` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `avatarPath` TEXT,
                    `subtitle` TEXT NOT NULL,
                    `personality` TEXT NOT NULL,
                    `speakingStyle` TEXT NOT NULL,
                    `greeting` TEXT NOT NULL,
                    `exampleDialogsJson` TEXT NOT NULL,
                    `isRoleplay` INTEGER NOT NULL,
                    `enabledToolsJson` TEXT NOT NULL,
                    `chatModelId` INTEGER,
                    `isBuiltIn` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `annotations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` INTEGER NOT NULL,
                    `personaId` INTEGER,
                    `chapterIndex` INTEGER NOT NULL,
                    `startCharOffset` INTEGER NOT NULL,
                    `endCharOffset` INTEGER NOT NULL,
                    `selectedText` TEXT NOT NULL,
                    `note` TEXT NOT NULL,
                    `colorTag` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_annotations_bookId_chapterIndex` " +
                    "ON `annotations` (`bookId`, `chapterIndex`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `notes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` INTEGER NOT NULL,
                    `personaId` INTEGER,
                    `title` TEXT NOT NULL,
                    `contentMarkdown` TEXT NOT NULL,
                    `relatedChapterIndex` INTEGER,
                    `relatedCharOffset` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_bookId` ON `notes` (`bookId`)")
            PersonaSeeds.insertInto(db)
        }
    }

    /** M2：世界书与人设分离——personas 增条目 JSON 与总开关（注入方式存在条目里）。 */
    val Migration7To8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `personas` ADD COLUMN `worldBookJson` TEXT NOT NULL DEFAULT '[]'"
            )
            db.execSQL(
                "ALTER TABLE `personas` ADD COLUMN `worldBookEnabled` INTEGER NOT NULL DEFAULT 1"
            )
        }
    }

    /**
     * Provider 能力下沉到模型：一个 Provider 可混合承载对话、向量、TTS 与生图模型。
     * 存量模型继承旧 Provider.type；同时给记忆固化增加逐消息水位，保证任务幂等续跑。
     */
    val Migration8To9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `ai_models` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'CHAT'"
            )
            db.execSQL(
                "ALTER TABLE `ai_models` ADD COLUMN `endpointPath` TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "ALTER TABLE `ai_models` ADD COLUMN `extraJson` TEXT NOT NULL DEFAULT '{}'"
            )
            db.execSQL(
                "ALTER TABLE `ai_providers` ADD COLUMN `adapter` TEXT NOT NULL DEFAULT 'CUSTOM'"
            )
            db.execSQL(
                """
                UPDATE `ai_providers` SET `adapter` = CASE
                    WHEN lower(`baseUrl`) LIKE '%openrouter.ai%' THEN 'OPENROUTER'
                    WHEN lower(`baseUrl`) LIKE '%api.openai.com%' THEN 'OPENAI'
                    WHEN lower(`baseUrl`) LIKE '%api.anthropic.com%' THEN 'ANTHROPIC'
                    WHEN lower(`baseUrl`) LIKE '%generativelanguage.googleapis.com%' THEN 'GEMINI'
                    WHEN lower(`baseUrl`) LIKE '%api.deepseek.com%' THEN 'DEEPSEEK'
                    ELSE 'CUSTOM'
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE `ai_models`
                SET `type` = COALESCE(
                    (SELECT `type` FROM `ai_providers`
                     WHERE `ai_providers`.`id` = `ai_models`.`providerId`),
                    'CHAT'
                )
                """.trimIndent()
            )
            db.execSQL(
                "ALTER TABLE `conversations` ADD COLUMN " +
                    "`memoryConsolidatedThroughMessageId` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * 请求协议下沉到聊天模型。空值表示继承 Provider 默认值；非聊天能力由 adapter
     * 按能力选择固定协议，避免 OpenRouter 的 Anthropic Messages 污染 embedding 路由。
     */
    val Migration9To10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `ai_models` ADD COLUMN `chatApiFormat` TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    /**
     * AI 客户端基础会话能力：自由新建/历史切换、编辑、重 roll 与分支。
     * 分支关系只作导航元数据，不设自引用外键；母会话删除后分支仍是用户资产。
     */
    val Migration10To11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `parentConversationId` INTEGER")
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `branchedFromMessageId` INTEGER")
            db.execSQL(
                "ALTER TABLE `conversations` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("UPDATE `conversations` SET `updatedAt` = `createdAt`")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `editedAt` INTEGER")
        }
    }

    /** 剧情梗概复用 notes 资产表，以 kind 分类并记录来源会话。 */
    val Migration11To12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notes` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'NOTE'")
            db.execSQL("ALTER TABLE `notes` ADD COLUMN `sourceConversationId` INTEGER")
        }
    }

    /** M4 多模态起步：持久化书籍插图画廊；内置角色默认开放新增的安全媒体工具。 */
    val Migration12To13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `illustrations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` INTEGER NOT NULL,
                    `chapterIndex` INTEGER,
                    `charOffset` INTEGER,
                    `sourceText` TEXT NOT NULL,
                    `prompt` TEXT NOT NULL,
                    `imagePath` TEXT NOT NULL,
                    `mediaType` TEXT,
                    `pixelWidth` INTEGER NOT NULL,
                    `pixelHeight` INTEGER NOT NULL,
                    `createdByPersonaId` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_illustrations_bookId_createdAt` " +
                    "ON `illustrations` (`bookId`, `createdAt`)"
            )
            listOf(
                "read_book_section",
                "save_plot_summary",
                "generate_image",
                "synthesize_speech"
            ).forEach { tool ->
                db.execSQL(
                    """
                    UPDATE `personas`
                    SET `enabledToolsJson` = CASE
                        WHEN `enabledToolsJson` = '[]' THEN '["$tool"]'
                        ELSE substr(`enabledToolsJson`, 1, length(`enabledToolsJson`) - 1) || ',"$tool"]'
                    END
                    WHERE `isBuiltIn` = 1
                      AND instr(`enabledToolsJson`, '"$tool"') = 0
                    """.trimIndent()
                )
            }
        }
    }

    /** 伴读消息附件（图片/文本文件）：JSON 数组存相对路径与 MIME，文件本体在 filesDir/attachments。 */
    val Migration13To14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentsJson` TEXT")
        }
    }

    /**
     * 伴读体验二期（M2.5）v15 一次到位：批注划线样式列、段评讨论串回复表，
     * 以及批次三的创作两表（表先行，UI 后置，省一轮迁移）。
     */
    val Migration14To15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `annotations` ADD COLUMN `style` TEXT NOT NULL DEFAULT 'HIGHLIGHT'"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `annotation_replies` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `annotationId` INTEGER NOT NULL,
                    `personaId` INTEGER,
                    `replyToId` INTEGER,
                    `contentMarkdown` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`annotationId`) REFERENCES `annotations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_annotation_replies_annotationId` " +
                    "ON `annotation_replies` (`annotationId`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_creations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `chapterIndex` INTEGER NOT NULL,
                    `startCharOffset` INTEGER NOT NULL,
                    `endCharOffset` INTEGER NOT NULL,
                    `directive` TEXT NOT NULL,
                    `activeVersionId` INTEGER,
                    `personaId` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_creations_bookId_chapterIndex` " +
                    "ON `ai_creations` (`bookId`, `chapterIndex`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_creation_versions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `creationId` INTEGER NOT NULL,
                    `ord` INTEGER NOT NULL,
                    `directive` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `modelName` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`creationId`) REFERENCES `ai_creations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_creation_versions_creationId` " +
                    "ON `ai_creation_versions` (`creationId`)"
            )
        }
    }

    /**
     * 书架阅读状态与置顶：进度只能推导「未读/在读/已读完」，读完回顾旧章会掉回在读，
     * 所以给一列手动标记（null = 仍按进度推导），「搁置」只存在于手动标记里。
     */
    val Migration15To16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `books` ADD COLUMN `manualReadState` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `books` ADD COLUMN `pinnedAt` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * 记忆 2.0 与伴读外观 v17 一次建齐（沿用 v15 的「列先行、UI 后接」经验，省掉后续两轮迁移）：
     * - conversations：会话滚动摘要与它的水位，补「出窗口但未固化」的上下文裂缝
     * - personas：常驻用户画像、每角色记忆开关、聊天外观（单 JSON 列，同 worldBookJson 先例）
     * - messages：发送时的用户面具，固化时据此区分本人记忆与面具内经历
     */
    val Migration16To17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `conversations` ADD COLUMN `rollingSummary` TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "ALTER TABLE `conversations` " +
                    "ADD COLUMN `summarizedThroughMessageId` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("ALTER TABLE `personas` ADD COLUMN `userProfile` TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "ALTER TABLE `personas` ADD COLUMN `memoryEnabled` INTEGER NOT NULL DEFAULT 1"
            )
            db.execSQL(
                "ALTER TABLE `personas` ADD COLUMN `chatAppearanceJson` TEXT NOT NULL DEFAULT '{}'"
            )
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `maskId` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** 书架分组与规范化标签；旧 books.tags 保留，供旧版回退。 */
    val Migration17To18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `shelf_groups` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `parentId` INTEGER,
                    `sortOrder` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_shelf_groups_parentId_name` " +
                    "ON `shelf_groups` (`parentId`, `name`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_shelf_groups_parentId` " +
                    "ON `shelf_groups` (`parentId`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book_tags` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `colorTag` TEXT NOT NULL,
                    `groupName` TEXT NOT NULL,
                    `sortOrder` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_tags_name` ON `book_tags` (`name`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book_tag_refs` (
                    `bookId` INTEGER NOT NULL,
                    `tagId` INTEGER NOT NULL,
                    PRIMARY KEY(`bookId`, `tagId`),
                    FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`tagId`) REFERENCES `book_tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_book_tag_refs_tagId` ON `book_tag_refs` (`tagId`)"
            )
            db.execSQL("ALTER TABLE `books` ADD COLUMN `groupId` INTEGER DEFAULT NULL")

            val createdAt = System.currentTimeMillis()
            db.query("SELECT `id`, `tags` FROM `books` WHERE `tags` <> ''").use { cursor ->
                while (cursor.moveToNext()) {
                    val bookId = cursor.getLong(0)
                    ShelfTagBackfill.parse(cursor.getString(1)).forEach { name ->
                        db.execSQL(
                            "INSERT OR IGNORE INTO `book_tags` " +
                                "(`name`, `colorTag`, `groupName`, `sortOrder`, `createdAt`) " +
                                "VALUES (?, ?, '', 0, ?)",
                            arrayOf<Any>(name, ShelfTagBackfill.colorFor(name), createdAt)
                        )
                        db.query(
                            "SELECT `id` FROM `book_tags` WHERE `name` = ? LIMIT 1",
                            arrayOf(name)
                        ).use { tagCursor ->
                            if (tagCursor.moveToFirst()) {
                                db.execSQL(
                                    "INSERT OR IGNORE INTO `book_tag_refs` (`bookId`, `tagId`) VALUES (?, ?)",
                                    arrayOf(bookId, tagCursor.getLong(0))
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 听书音色库与 AI 有声书三表一次建齐，S3 不再新增迁移。 */
    val Migration18To19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `tts_voices` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `voiceId` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `tags` TEXT NOT NULL,
                    `gender` TEXT NOT NULL,
                    `providerHint` TEXT NOT NULL,
                    `extraJson` TEXT NOT NULL,
                    `pinned` INTEGER NOT NULL,
                    `sortOrder` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_tts_voices_providerHint_voiceId` " +
                    "ON `tts_voices` (`providerHint`, `voiceId`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `audiobook_roles` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `aliases` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `gender` TEXT NOT NULL,
                    `engine` TEXT NOT NULL,
                    `voiceId` TEXT NOT NULL,
                    `extraJson` TEXT NOT NULL,
                    `color` TEXT NOT NULL,
                    `sortOrder` INTEGER NOT NULL,
                    `source` TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_audiobook_roles_bookId` " +
                    "ON `audiobook_roles` (`bookId`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `audiobook_segments` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` INTEGER NOT NULL,
                    `chapterIndex` INTEGER NOT NULL,
                    `startCharOffset` INTEGER NOT NULL,
                    `endCharOffset` INTEGER NOT NULL,
                    `roleId` INTEGER,
                    `emotion` TEXT,
                    `instruction` TEXT,
                    `audioPath` TEXT,
                    `audioMillis` INTEGER NOT NULL,
                    `revision` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_audiobook_segments_bookId_chapterIndex_startCharOffset` " +
                    "ON `audiobook_segments` (`bookId`, `chapterIndex`, `startCharOffset`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `audiobook_chapters` (
                    `bookId` INTEGER NOT NULL,
                    `chapterIndex` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    `scriptedAt` INTEGER NOT NULL,
                    `confirmedAt` INTEGER NOT NULL,
                    `synthesizedAt` INTEGER NOT NULL,
                    `segmentCount` INTEGER NOT NULL,
                    `readySegmentCount` INTEGER NOT NULL,
                    `totalMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`bookId`, `chapterIndex`)
                )
                """.trimIndent()
            )
        }
    }

    /**
     * 伴读三期五列一次加齐（批次二/三才用到的列也在此落地，只跑一次 MigrationTest）：
     * 思维链、角色音色与情绪、批注与讨论回复的媒体清单。
     */
    val Migration19To20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 思维链可空：不支持 reasoning 的模型这一列恒为 NULL，界面据此整条不出现。
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `reasoningContent` TEXT")
            db.execSQL("ALTER TABLE `personas` ADD COLUMN `voiceId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `personas` ADD COLUMN `voiceEmotion` TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "ALTER TABLE `annotations` ADD COLUMN `mediaJson` TEXT NOT NULL DEFAULT '{}'"
            )
            db.execSQL(
                "ALTER TABLE `annotation_replies` ADD COLUMN `mediaJson` TEXT NOT NULL DEFAULT '{}'"
            )
        }
    }

    /** Preserves the EPUB navigation tree independently from the linear reading-order chapters. */
    val Migration20To21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book_toc_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` INTEGER NOT NULL,
                    `orderIndex` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    `href` TEXT NOT NULL,
                    `depth` INTEGER NOT NULL,
                    `parentOrderIndex` INTEGER,
                    `chapterIndex` INTEGER,
                    `hasChildren` INTEGER NOT NULL,
                    FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_book_toc_entries_bookId` " +
                    "ON `book_toc_entries` (`bookId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_toc_entries_bookId_orderIndex` " +
                    "ON `book_toc_entries` (`bookId`, `orderIndex`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_book_toc_entries_bookId_chapterIndex` " +
                    "ON `book_toc_entries` (`bookId`, `chapterIndex`)"
            )
        }
    }

    /** Adds monotonic reading watermarks and provenance for AI-generated persisted content. */
    val Migration21To22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `books` ADD COLUMN `maxReachedChapterIndex` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `books` ADD COLUMN `maxReachedCharOffset` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE `books` SET `maxReachedChapterIndex` = `lastReadChapterIndex`, " +
                    "`maxReachedCharOffset` = `lastReadCharOffset`"
            )
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `sourceScopeChapterIndex` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `sourceScopeCharOffset` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `annotations` ADD COLUMN `sourceScopeChapterIndex` INTEGER")
            db.execSQL("ALTER TABLE `annotations` ADD COLUMN `sourceScopeCharOffset` INTEGER")
            db.execSQL("ALTER TABLE `notes` ADD COLUMN `sourceScopeChapterIndex` INTEGER")
            db.execSQL("ALTER TABLE `notes` ADD COLUMN `sourceScopeCharOffset` INTEGER")
        }
    }

    val Migration22To23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book_collections` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("ALTER TABLE `books` ADD COLUMN `collectionId` INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE `books` ADD COLUMN `collectionOrder` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_books_collectionId` ON `books` (`collectionId`)"
            )
            db.execSQL("ALTER TABLE `annotations` ADD COLUMN `textAnchorJson` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `illustrations` ADD COLUMN `textAnchorJson` TEXT NOT NULL DEFAULT ''")
        }
    }

}
