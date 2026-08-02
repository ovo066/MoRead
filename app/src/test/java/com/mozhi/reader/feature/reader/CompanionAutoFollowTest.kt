package com.mozhi.reader.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionAutoFollowTest {

    @Test
    fun `拖动中实时取手指位置`() {
        assertFalse(resolveAutoFollow(current = true, wasScrolling = false, scrolling = true, atBottom = false))
        assertTrue(resolveAutoFollow(current = false, wasScrolling = true, scrolling = true, atBottom = true))
    }

    @Test
    fun `手势结束瞬间采样落点`() {
        // 上滑离底后松手：停止跟随
        assertFalse(resolveAutoFollow(current = false, wasScrolling = true, scrolling = false, atBottom = false))
        // 滑回底部松手：恢复跟随
        assertTrue(resolveAutoFollow(current = false, wasScrolling = true, scrolling = false, atBottom = true))
    }

    @Test
    fun `布局位移不改变状态`() {
        // 流式气泡长高把 atBottom 顶成 true/false 都不得改变已停跟随的状态
        assertFalse(resolveAutoFollow(current = false, wasScrolling = false, scrolling = false, atBottom = true))
        assertTrue(resolveAutoFollow(current = true, wasScrolling = false, scrolling = false, atBottom = false))
    }
}
