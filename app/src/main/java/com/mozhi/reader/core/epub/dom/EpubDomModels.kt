package com.mozhi.reader.core.epub.dom

import com.mozhi.reader.core.library.EpubLayoutDiagnostic
import com.mozhi.reader.core.library.EpubStylesheetText
import kotlinx.serialization.Serializable

@Serializable
data class EpubDomChapter(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val chapterIndex: Int,
    val href: String,
    val documentTitle: String? = null,
    val bodyNode: EpubDomNode,
    val textLength: Int,
    val diagnostics: List<EpubLayoutDiagnostic> = emptyList(),
    /** Embedded <style> sheets, with document-relative URLs and their original cascade order. */
    val embeddedStylesheets: List<EpubStylesheetText> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 10
    }
}

@Serializable
data class EpubDomNode(
    val tag: String,
    val id: String? = null,
    val classes: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val childIndex: Int = 0,
    val childIndexOfType: Int = 0,
    val textStart: Int = -1,
    val textEnd: Int = -1,
    val children: List<EpubDomNode> = emptyList()
) {
    fun hasInlineStyles(): Boolean =
        !attributes["style"].isNullOrBlank() || children.any(EpubDomNode::hasInlineStyles)
}
