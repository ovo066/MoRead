package com.mozhi.reader.core.library

import com.mozhi.reader.feature.importer.TxtChapterSplitter
import org.junit.Assert.*
import org.junit.Test

class TxtSourceTextTest {
    @Test fun splittingAgainRestoresHeadingsInIndexOrderWithoutAnEpub() {
        val original = "第一章 风起\n　　第一段🙂。\n\n第二章 云来\n　 第二段。\n\n第三章 雨落\n　 第三段。"
        val splitter = TxtChapterSplitter()
        val split = splitter.splitWithCustomRegex(original, "^第[一二三]章.*$")!!
        val writer = BookTextWriter()
        val drafts = split.chapters.map { EditableChapterDraft(it.index, it.title, "", writer.normalize(it.content)) }.reversed()
        val again = splitter.splitWithCustomRegex(reconstructTxtSource(drafts), "^第[一二三]章.*$")!!
        assertEquals(split.chapters.map { it.title }, again.chapters.map { it.title })
        assertEquals(split.chapters.map { writer.normalize(it.content) }, again.chapters.map { it.content })
        assertEquals(reconstructTxtSource(drafts), reconstructTxtSource(again.chapters.map { EditableChapterDraft(it.index, it.title, "", it.content) }))
    }

    @Test fun olderTextThatAlreadyContainsItsHeadingDoesNotGainAnotherCopy() {
        assertEquals("第一章\n正文", reconstructTxtSource(listOf(EditableChapterDraft(0, "第一章", "", "第一章\n正文"))))
    }
}
