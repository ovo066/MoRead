package com.mozhi.reader.feature.reader

import android.app.Application
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.mozhi.reader.core.datastore.AutoReadSettings
import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.feature.reader.engine.ChapterMeta
import com.mozhi.reader.feature.reader.engine.ReaderChapterContent
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutoReadSurfaceTest {
    @Test fun pausingDuringAnAutomaticTurnDoesNotCommitOrChangeItsSourceAnchor() = runTest {
        val body = "这一页是可读的原文。\n".repeat(120)
        val controller = ReaderContentController(this, { ReaderChapterContent(body) }, object : ReaderContentController.Listener {
            override fun onContentChanged(relativePosition: Int) = Unit
            override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int, pageCount: Int, bookProgress: Float) = Unit
        })
        val holder = ReaderPaneHolder(controller)
        val settings = ReaderSettings()
        val palette = readerPalette(settings.theme, false, androidx.compose.ui.graphics.Color.Blue)
        val key = readerRenderStyleKey(settings, palette, Density(1f), IntSize(320, 560), 0f, 0f)
        holder.setViewport(320, 560)
        holder.applyStyle(key, { ReaderPageStyle.resolve(settings, palette, Density(1f), 320, 560, 0f, 0f) }, null, false, {})
        controller.setChapters(listOf(ChapterMeta(0, "第一章", body.length)))
        controller.openPosition(0, 0)
        coroutineContext.job.children.forEach { it.join() }
        holder.refresh(0)
        holder.ensureFresh()
        assertTrue(controller.hasNextPage())
        val session = AutoReadSession().apply { start(AutoReadSettings(mode = PageMode.PAGINATED)); onReady(PageMode.PAGINATED) }
        val token = session.generation
        var result: ReaderTurnResult? = null
        holder.followRequest = ReaderPageTurnRequest(1, PageTurnDirection.NEXT,
            onFinished = { result = it }, automatic = true, isValid = { session.owns(token) })
        holder.startTurn()
        session.pause(AutoReadPauseReason.TOUCH)
        assertFalse(holder.commitTurn(PageTurnDirection.NEXT))
        holder.finishFollowTurn()
        assertEquals(ReaderTurnResult.CANCELLED, result)
        assertEquals(0, controller.charOffset)
        assertEquals(0, controller.pageIndex)
        holder.release()
    }
}
