package com.mozhi.reader.core.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.PersonaExampleDialog
import com.mozhi.reader.core.database.entity.encodeEnabledTools
import com.mozhi.reader.core.database.entity.encodeExampleDialogs

/**
 * 内置伴读角色模板（DEVELOPMENT_PLAN M2：工具型「阅读助手」+ 扮演型示例角色）。
 *
 * 以数据种子的方式各插一次：老库走 [DatabaseMigrations.Migration6To7]，
 * 全新建库走 [onCreate] 回调。此后是普通行，用户可改可删，不自动复活。
 */
object PersonaSeeds {

    /** 角色白名单只保存会写入用户资产的能力；只读工具由伴读运行时恒定授予。 */
    private val ALL_TOOLS = listOf(
        "add_annotation",
        "write_note",
        "save_plot_summary",
        "generate_image",
        "synthesize_speech",
        "create_reading_plan"
    )

    val templates: List<PersonaEntity> = listOf(
        PersonaEntity(
            name = "阅读助手",
            subtitle = "知识向导 · 结构梳理",
            personality = "知识型伴读向导，善于把制度、地理、人物与时间线整理成清晰结构，" +
                "并严格区分正文信息、合理推断与背景知识。",
            speakingStyle = "准确、分点、无剧透；先给短答案，需要时再展开背景。",
            greeting = "已同步当前章节。我可以整理时间线、人物关系或背景知识。",
            exampleDialogsJson = encodeExampleDialogs(
                listOf(
                    PersonaExampleDialog(
                        user = "帮我理一下目前的人物关系。",
                        assistant = "好的，只用已读章节的信息，按「人物—目标—与谁发生联系—本章变化」列出：…"
                    )
                )
            ),
            isRoleplay = false,
            enabledToolsJson = encodeEnabledTools(ALL_TOOLS),
            isBuiltIn = true,
            createdAt = 0
        ),
        PersonaEntity(
            name = "阿翎",
            subtitle = "共情写作者 · 情绪共读",
            personality = "旅行写作者，敏感而温柔，擅长捕捉场景气味、人物情绪与叙事留白。",
            speakingStyle = "像朋友一样回应感受，少用术语，多用画面与比喻。",
            greeting = "刚才那阵穿过铜铃的风，你听见了吗？我们从你的感受开始。",
            exampleDialogsJson = encodeExampleDialogs(
                listOf(
                    PersonaExampleDialog(
                        user = "为什么这一段让我紧张？",
                        assistant = "因为夕阳很美，但时间正在被暮鼓一点点收走。"
                    )
                )
            ),
            isRoleplay = true,
            // 扮演型专注共读，不做计划管理。
            enabledToolsJson = encodeEnabledTools(ALL_TOOLS - "create_reading_plan"),
            isBuiltIn = true,
            createdAt = 0
        )
    )

    /** 全新建库时落种子；迁移路径由 Migration6To7 自己调 [insertInto]。 */
    val onCreate: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            insertInto(db)
        }
    }

    fun insertInto(db: SupportSQLiteDatabase, now: Long = System.currentTimeMillis()) {
        templates.forEachIndexed { index, persona ->
            db.execSQL(
                """
                INSERT INTO `personas`
                    (`name`, `avatarPath`, `subtitle`, `personality`, `speakingStyle`, `greeting`,
                     `exampleDialogsJson`, `isRoleplay`, `enabledToolsJson`, `chatModelId`,
                     `isBuiltIn`, `createdAt`)
                VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    persona.name,
                    persona.subtitle,
                    persona.personality,
                    persona.speakingStyle,
                    persona.greeting,
                    persona.exampleDialogsJson,
                    if (persona.isRoleplay) 1 else 0,
                    persona.enabledToolsJson,
                    now + index
                )
            )
        }
    }
}
