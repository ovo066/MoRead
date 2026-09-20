package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.dictionary.*
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import org.junit.Assert.*
import org.junit.Test

class EnglishReadingLayoutTest {
    private val spec = TypesetSpec(220f, 180f, 25f, 32f, 8f, 8f, 0f, 12f, contentFontSizePx = 20f, bottomAlign = false)
    private val measure = FakeMeasure()

    @Test fun aSourceHeadingCanHaveItsOwnTranslation() {
        val body = "Chapter One\nA reader opens a book."
        val p = englishParagraphs(body).first()
        val chapter = ChapterTypesetter(spec, measure).typeset(0, "Chapter One", body,
            translations = listOf(ParagraphTranslation(p.start, p.end, p.key, "第一章")))
        val lines = chapter.pages.flatMap { it.lines }
        assertEquals("Chapter One", lines[0].text)
        assertEquals("第一章", lines[1].text)
        assertEquals(0, lines[1].charLength)
    }

    @Test fun translationsParticipateInPaginationButNeverChangeOriginalCoordinatesOrSelection() {
        val body = "A reader found a book by the window.\nAnother reader opened the book."
        val translated = englishParagraphs(body).map { ParagraphTranslation(it.start, it.end, it.key, "窗边有位读者找到一本书。".repeat(5)) }
        val chapter = ChapterTypesetter(spec, measure).typeset(0, "", body, translations = translated)
        val all = chapter.pages.flatMap { it.lines }
        assertTrue(chapter.pageCount > 1)
        assertEquals(body.length, chapter.bodyLength)
        assertTrue(all.any { it.charLength == 0 && it.text.contains("读者") })
        chapter.pages.forEach { page ->
            assertTrue(page.lines.last().lineBottom <= spec.visibleHeight + 0.5f)
            page.lines.forEach { line ->
                if (line.charLength > 0) assertEquals(body.substring(line.chapterPosition, line.chapterPosition + line.charLength), line.text)
                else assertNull(page.hitTextPos(line.startX + 1f, line.lineBase, exact = true))
                if (line.paragraphTranslation != null) {
                    val hit = page.translationAt(line.startX + 1f, line.lineBase, 3)!!
                    assertEquals(3, hit.chapterIndex)
                    assertEquals(translated.first { it.end == line.chapterPosition }, hit.translation)
                    assertNull(page.translationAt(line.columns.last().end + 1f, line.lineBase, 3))
                }
            }
        }
        val merged = TextPage(0, all, 0, body.length, 1000f)
        val first = merged.textPosAtBodyOffset(0)!!
        val last = merged.textPosAtBodyOffset(body.lastIndex)!!
        assertEquals(body, merged.selectedText(first, last))
    }

    @Test fun staleAndHiddenTranslationsDisappearWithoutChangingEnglish() {
        val body = "The book is here."
        val paragraph = englishParagraphs(body).single()
        val translation = ParagraphTranslation(0, body.length, paragraph.key, "书在这里。")
        listOf(translation.copy(hidden = true), translation.copy(sourceKey = "stale")).forEach {
            val chapter = ChapterTypesetter(spec, measure).typeset(0, "", body, translations = listOf(it))
            assertTrue(chapter.pages.flatMap { it.lines }.none { it.charLength == 0 })
        }
        val original = ChapterTypesetter(spec, measure).typeset(0, "", body)
        assertEquals(body, original.pages.flatMap { it.lines }.joinToString("") { it.text })
    }

    @Test fun wordGlossesReserveSpaceAndKeepPhoneticsBelowEnglish() {
        val body = List(18) { "A book, another book." }.joinToString("\n")
        val style = spec.copy(wordGlosses = mapOf("book" to WordGloss("书本", "/bʊk/")))
        val chapter = ChapterTypesetter(style, measure).typeset(0, "", body)
        assertTrue(chapter.pages.size > ChapterTypesetter(spec, measure).typeset(0, "", body).pages.size)
        chapter.pages.forEach { page ->
            page.lines.forEach { line ->
                assertTrue(line.rubyPlacements.isNotEmpty())
                assertTrue(line.rubyPlacements.all { it.baseline > line.lineBase && it.baseline <= line.lineBottom })
                assertTrue(line.rubyPlacements.all { it.left >= 0 && it.right <= spec.visibleWidth })
            }
            assertTrue(page.lines.last().lineBottom <= spec.visibleHeight + 0.5f)
        }
    }

    @Test fun legacyAndDomEpubPlaceEachTranslationOnceAfterItsEnglishParagraph() {
        val css = "p { line-height: 1.4; margin: 0; }"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><p>A reader found a book.</p><p>The window was open.</p></body></html>".toByteArray(), 0, "OPS/ch.xhtml", emptyMap())
        val paragraphs = englishParagraphs(parsed.text)
        assertEquals(2, paragraphs.size)
        val translations = paragraphs.mapIndexed { i, p -> ParagraphTranslation(p.start, p.end, p.key, "第${i + 1}段的译文。") }
        listOf(false, true).forEach { dom ->
            val bundle = EpubLayoutChapterBundle(parsed.document, emptyMap(), emptyMap(),
                dom = if (dom) parsed.dom else null, stylesheets = if (dom) listOf(EpubStylesheetText("OPS/main.css", css)) else emptyList())
            val chapter = ChapterTypesetter(spec, measure).typeset(0, "", parsed.text, epubLayout = bundle, translations = translations)
            val lines = chapter.pages.flatMap { it.lines }
            translations.forEach { translation ->
                val translatedIndex = lines.indexOfFirst { it.text == translation.chinese }
                assertTrue("missing translation in dom=$dom", translatedIndex > 0)
                assertEquals(1, lines.count { it.text == translation.chinese })
                assertEquals(translation, lines[translatedIndex].paragraphTranslation)
                assertEquals(translation.end, lines[translatedIndex - 1].chapterPosition + lines[translatedIndex - 1].charLength)
            }
            assertTrue(chapter.pages.all { it.lines.last().lineBottom <= spec.visibleHeight + 0.5f })
        }
    }
}
