package com.mozhi.reader.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShelfTagBackfillTest {
    @Test
    fun `旧标签去空去重并兼容中文逗号`() {
        assertEquals(listOf("玄幻", "修仙"), ShelfTagBackfill.parse("玄幻, 修仙,,玄幻 ，修仙"))
    }

    @Test
    fun `同名标签颜色稳定且能落入多个预设色`() {
        assertEquals(ShelfTagBackfill.colorFor("玄幻"), ShelfTagBackfill.colorFor("玄幻"))
        assertNotEquals(
            1,
            listOf("玄幻", "科幻", "历史", "言情", "悬疑", "工具")
                .map(ShelfTagBackfill::colorFor)
                .distinct()
                .size
        )
    }
}
