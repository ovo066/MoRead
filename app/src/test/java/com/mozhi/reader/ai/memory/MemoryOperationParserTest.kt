package com.mozhi.reader.ai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryOperationParserTest {

    @Test
    fun parsesAllFourActions() {
        val operations = MemoryOperationParser.parse(
            """
            [{"action":"ADD","summary":"用户喜欢科幻"},
             {"action":"UPDATE","id":7,"summary":"用户现在更喜欢历史"},
             {"action":"DELETE","id":9},
             {"action":"NOOP"}]
            """.trimIndent()
        )

        // NOOP 不占位置，调用方拿到的都是要真正执行的操作。
        assertEquals(3, operations.size)
        assertEquals(MemoryOperation.Add("用户喜欢科幻"), operations[0])
        assertEquals(MemoryOperation.Update(7, "用户现在更喜欢历史"), operations[1])
        assertEquals(MemoryOperation.Delete(9), operations[2])
    }

    @Test
    fun toleratesCodeFencesAndSurroundingProse() {
        val operations = MemoryOperationParser.parse(
            "好的，我的判断如下：\n```json\n[{\"action\":\"ADD\",\"summary\":\"用户在读《三体》\"}]\n```\n希望有用。"
        )

        assertEquals(listOf(MemoryOperation.Add("用户在读《三体》")), operations)
    }

    @Test
    fun acceptsOperationsWrappedInAnObject() {
        val operations = MemoryOperationParser.parse(
            """{"operations":[{"action":"ADD","summary":"用户是猫派"}]}"""
        )

        assertEquals(listOf(MemoryOperation.Add("用户是猫派")), operations)
    }

    /** 缺 id 的 UPDATE 无从下手；降级成新增比丢掉这条信息好。 */
    @Test
    fun updateWithoutIdDegradesToAdd() {
        val operations = MemoryOperationParser.parse(
            """[{"action":"UPDATE","summary":"用户改口说喜欢冬天"}]"""
        )

        assertEquals(listOf(MemoryOperation.Add("用户改口说喜欢冬天")), operations)
    }

    /** 空正文的 UPDATE 等于变相删除，绝不能照做。 */
    @Test
    fun updateWithoutSummaryIsDropped() {
        assertTrue(
            MemoryOperationParser.parse("""[{"action":"UPDATE","id":3,"summary":"  "}]""").isEmpty()
        )
    }

    @Test
    fun deleteWithoutValidIdIsDropped() {
        assertTrue(MemoryOperationParser.parse("""[{"action":"DELETE"}]""").isEmpty())
        assertTrue(MemoryOperationParser.parse("""[{"action":"DELETE","id":0}]""").isEmpty())
    }

    @Test
    fun unknownActionsAndGarbageYieldNothing() {
        assertTrue(MemoryOperationParser.parse("""[{"action":"MERGE","id":1}]""").isEmpty())
        assertTrue(MemoryOperationParser.parse("完全不是 JSON").isEmpty())
        assertTrue(MemoryOperationParser.parse("[]").isEmpty())
        assertTrue(MemoryOperationParser.parse("").isEmpty())
    }

    @Test
    fun capsOperationCountAndSummaryLength() {
        val many = (1..20).joinToString(",", "[", "]") {
            """{"action":"ADD","summary":"${"长".repeat(800)}"}"""
        }

        val operations = MemoryOperationParser.parse(many)

        assertEquals(MemoryOperationParser.MAX_OPERATIONS, operations.size)
        assertEquals(
            MemoryOperationParser.MAX_SUMMARY_CHARS,
            (operations.first() as MemoryOperation.Add).summary.length
        )
    }

    @Test
    fun blankAddIsDropped() {
        assertTrue(MemoryOperationParser.parse("""[{"action":"ADD","summary":""}]""").isEmpty())
    }

    @Test
    fun profileParserReadsBothHalves() {
        val draft = UserProfileParser.parse(
            """
            {"operations":[{"action":"ADD","summary":"用户在读《活着》"}],
             "user_profile":"称呼：老周。偏好沉重题材。"}
            """.trimIndent()
        )

        assertEquals(listOf(MemoryOperation.Add("用户在读《活着》")), draft.operations)
        assertEquals("称呼：老周。偏好沉重题材。", draft.userProfile)
    }

    @Test
    fun profileParserTreatsNoChangeMarkersAsNull() {
        listOf("null", "无变化", "", "-").forEach { marker ->
            val draft = UserProfileParser.parse(
                """{"operations":[],"user_profile":"$marker"}"""
            )
            assertNull("「$marker」应视为画像无更新", draft.userProfile)
        }
    }

    @Test
    fun profileParserFallsBackToBareOperationArray() {
        val draft = UserProfileParser.parse("""[{"action":"ADD","summary":"用户住在南方"}]""")

        assertEquals(listOf(MemoryOperation.Add("用户住在南方")), draft.operations)
        assertNull(draft.userProfile)
    }

    @Test
    fun profileIsCappedSoItNeverCrowdsOutThePersona() {
        val draft = UserProfileParser.parse(
            """{"operations":[],"user_profile":"${"字".repeat(3000)}"}"""
        )

        assertEquals(UserProfileParser.MAX_PROFILE_CHARS, draft.userProfile!!.length)
    }
}
