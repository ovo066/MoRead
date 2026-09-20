package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.unit.Density
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.feature.reader.engine.*
import com.mozhi.reader.feature.reader.render.*
import com.mozhi.reader.feature.stats.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderEnhancementsVisualTest {
    @Test fun modernShaderCompilesAndBindsAllUniformsOnARecordingCanvas() {
        val front = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val under = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val node = android.graphics.RenderNode("curl-contract").apply { setPosition(0, 0, 360, 640) }
        val curl = ModernPageCurl()
        for (direction in PageTurnDirection.entries) {
            val canvas = node.beginRecording()
            try {
                assertTrue(canvas.isHardwareAccelerated)
                curl.draw(canvas, direction, front, under, if (direction == PageTurnDirection.NEXT) -160f else 160f,
                    470f, 360f, 640f, Color.WHITE, null, startX = 350f, startY = 320f)
            } finally { node.endRecording() }
        }
        curl.clear()
        node.discardDisplayList()
    }

    @Test fun titleStyleIsMeasuredInPaginationAndRendersOnlyReaderHeadings() {
        val settings = ReaderSettings(font = ReaderFont.SERIF, titleStyle = ReaderTitleStyle(
            colorArgb = 0xff795132.toInt(), borderColorArgb = 0xff997545.toInt(), borderWidthEm = .06f,
            paddingEm = .6f, cornerRadiusEm = .3f, alignment = ReaderTitleAlignment.CENTER,
            css = "font-size: 1.8em; margin-top: 4em; margin-bottom: 6em;"))
        val style = ReaderPageStyle.resolve(settings, readerPalette(settings.theme, false, androidx.compose.ui.graphics.Color.Blue), Density(1f), 411, 800, 0f, 0f)
        val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "第1章 春江月夜", "春江潮水连海平，海上明月共潮生。\n".repeat(45))
        val title = chapter.pages.first().lines.first { it.isTitle }
        val body = chapter.pages.first().lines.first { !it.isTitle }
        assertTrue(title.isReaderTitle)
        assertTrue(title.lineTop >= style.contentSizePx * 4)
        assertTrue(body.lineTop - title.lineBottom >= style.contentSizePx * 5)
        assertTrue(title.startX > 20f)
        val renderer = PageBitmapRenderer(style)
        save(renderer.render(RenderPage.Laid(0, chapter.title, 0, chapter.pages.size, chapter.pages.first()), null, .1f, "12:00", 90), "chapter-title.png")
        renderer.release()
        assertTrue(chapter.pages.all { page -> page.lines.all { it.lineBottom <= style.contentHeight + 1 } })
    }

    @Test fun readerTitleCssDoesNotChangePublisherHeadingLayout() {
        val parsed = com.mozhi.reader.feature.importer.EpubLayoutDocumentParser().parseWithText(
            "<html><body><h1>原书标题</h1><p>保持出版商正文与标题的距离。</p></body></html>".toByteArray(),
            0, "OPS/ch.xhtml", emptyMap())
        val layout = com.mozhi.reader.core.library.EpubLayoutChapterBundle(parsed.document, emptyMap(), emptyMap(), dom = parsed.dom,
            stylesheets = listOf(com.mozhi.reader.core.library.EpubStylesheetText("OPS/main.css", "h1{color:#884422;text-align:center}")))
        fun typeset(settings: ReaderSettings): TextChapter {
            val style = ReaderPageStyle.resolve(settings, readerPalette(settings.theme, false, androidx.compose.ui.graphics.Color.Blue), Density(1f), 411, 800, 0f, 0f)
            return ChapterTypesetter(style.spec, style.measure).typeset(0, "原书标题", parsed.text, epubLayout = layout)
        }
        val baseline = typeset(ReaderSettings())
        val custom = typeset(ReaderSettings(titleStyle = ReaderTitleStyle(font = ReaderSyntaxFont.MONOSPACE,
            borderWidthEm = .2f, css = "font-size: 3em; margin-top: 10em; margin-bottom: 12em; color: #ff0000;")))
        fun geometry(chapter: TextChapter) = chapter.pages.map { page -> page.lines.map { line ->
            listOf(line.text, line.lineTop, line.lineBase, line.lineBottom, line.startX,
                line.columns.map { listOf(it.charData, it.start, it.end, it.textSizeScale, it.syntaxColorArgb, it.fontFamily) })
        } }
        assertEquals(geometry(baseline), geometry(custom))
        assertTrue(custom.pages.flatMap { it.lines }.none { it.isReaderTitle })
    }

    @Test fun cloudsUseStableDenseRotatedGlyphPackingWithoutPixelOverlap() {
        val names = listOf("鲁迅", "余华", "莫言", "张爱玲", "史铁生", "钱钟书", "沈从文", "老舍", "冰心", "王小波", "汪曾祺", "迟子建", "萧红", "苏童", "刘慈欣", "阿来", "三毛", "木心", "孙犁", "巴金", "文学", "历史", "哲学", "散文", "科幻", "诗歌", "传记", "小说", "旅行", "童话")
        val input = names.mapIndexed { index, name -> StatsCloudItem(name, ((names.size - index) * (names.size - index)).toLong(), 1) }
        val cloud = StatsCloudLayout.arrange(input)
        assertEquals(cloud, StatsCloudLayout.arrange(input.reversed()))
        assertEquals(input.size, cloud.words.size + cloud.omitted.size)
        assertTrue(cloud.words.size >= 25)
        assertTrue(cloud.words.any { it.rotation != 0f })
        assertTrue(cloud.words.maxOf { it.fontSize } / cloud.words.minOf { it.fontSize } > 3f)
        val result = Bitmap.createBitmap(cloud.width, cloud.height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xfffaf6f0.toInt()) }
        val canvas = Canvas(result)
        val occupied = BooleanArray(cloud.width * cloud.height)
        cloud.words.forEachIndexed { index, word ->
            val layer = Bitmap.createBitmap(cloud.width, cloud.height, Bitmap.Config.ARGB_8888)
            val paint = StatsCloudLayout.paint(word.fontSize, word.bold).apply { color = listOf(0xff346ba8.toInt(), 0xff79609a.toInt(), 0xffa3612f.toInt(), 0xff327d70.toInt())[index % 4] }
            Canvas(layer).apply {
                translate(word.x + word.width / 2, word.y + word.height / 2)
                rotate(word.rotation)
                drawText(word.item.label, -paint.measureText(word.item.label) / 2, -(paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2, paint)
            }
            val pixels = IntArray(occupied.size).also { layer.getPixels(it, 0, cloud.width, 0, 0, cloud.width, cloud.height) }
            pixels.forEachIndexed { i, pixel -> if (pixel ushr 24 > 64) { assertFalse("overlap at $i for ${word.item.label}", occupied[i]); occupied[i] = true } }
            canvas.drawBitmap(layer, 0f, 0f, null)
            layer.recycle()
        }
        save(result, "authors-cloud.png")
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = File("build/reports/reader-enhancements").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
