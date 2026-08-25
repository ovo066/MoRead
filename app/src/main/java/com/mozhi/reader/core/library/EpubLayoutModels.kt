package com.mozhi.reader.core.library

import kotlinx.serialization.Serializable

@Serializable
data class EpubLayoutPackage(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val packageDocumentPath: String,
    val epubVersion: String? = null,
    val uniqueIdentifier: String? = null,
    val resources: List<EpubLayoutResource> = emptyList(),
    val spine: List<EpubLayoutSpineItem> = emptyList(),
    val fontFaces: List<EpubFontFace> = emptyList(),
    val chapters: List<EpubLayoutChapterRef> = emptyList(),
    val diagnostics: List<EpubLayoutDiagnostic> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

@Serializable
data class EpubLayoutResource(
    val id: String,
    val href: String,
    val archivePath: String,
    val mediaType: String,
    val properties: List<String> = emptyList(),
    val kind: EpubLayoutResourceKind,
    val sizeBytes: Long,
    val sha256: String? = null
)

@Serializable
enum class EpubLayoutResourceKind {
    DOCUMENT,
    STYLESHEET,
    IMAGE,
    SVG,
    FONT,
    NAVIGATION,
    OTHER
}

@Serializable
data class EpubLayoutSpineItem(
    val index: Int,
    val idref: String,
    val href: String?,
    val linear: Boolean,
    val properties: List<String> = emptyList()
)

@Serializable
data class EpubFontFace(
    val family: String,
    val resourceHref: String,
    val weight: Int? = null,
    val italic: Boolean = false
)

@Serializable
data class EpubBoxShadow(
    val offsetXEm: Float,
    val offsetYEm: Float,
    val blurRadiusEm: Float = 0f,
    val spreadRadiusEm: Float = 0f,
    val colorArgb: Int = 0xFF000000.toInt(),
    val inset: Boolean = false
)

@Serializable
data class EpubLayoutChapterRef(
    val chapterIndex: Int,
    val href: String,
    val textLength: Int,
    val fileName: String
)

@Serializable
data class EpubLayoutChapter(
    val schemaVersion: Int = EpubLayoutPackage.CURRENT_SCHEMA_VERSION,
    val chapterIndex: Int,
    val href: String,
    val documentTitle: String? = null,
    val bodyStyle: EpubComputedStyle = EpubComputedStyle(),
    val stylesheetHrefs: List<String> = emptyList(),
    val blocks: List<EpubLayoutBlock> = emptyList(),
    val textLength: Int,
    val diagnostics: List<EpubLayoutDiagnostic> = emptyList()
)

@Serializable
data class EpubLayoutBlock(
    val orderIndex: Int,
    val kind: EpubLayoutBlockKind,
    val textStart: Int,
    val textEnd: Int,
    val element: EpubElementRef,
    val ancestors: List<EpubElementRef> = emptyList(),
    val style: EpubComputedStyle = EpubComputedStyle(),
    val spans: List<EpubLayoutSpan> = emptyList(),
    val resourceHref: String? = null,
    val altText: String = ""
)

@Serializable
enum class EpubLayoutBlockKind {
    PARAGRAPH,
    HEADING,
    QUOTE,
    LIST_ITEM,
    CONTAINER,
    IMAGE,
    SEPARATOR
}

@Serializable
data class EpubLayoutSpan(
    val textStart: Int,
    val textEnd: Int,
    val elements: List<EpubElementRef> = emptyList(),
    val style: EpubComputedStyle = EpubComputedStyle(),
    val linkHref: String? = null,
    val rubyText: String? = null
)

@Serializable
data class EpubElementRef(
    val tag: String,
    val id: String? = null,
    val classes: List<String> = emptyList(),
    val inlineStyle: String? = null
)

@Serializable
data class EpubComputedStyle(
    val fontFamily: String? = null,
    val fontSizeEm: Float? = null,
    val fontWeight: Int? = null,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val colorArgb: Int? = null,
    val backgroundColorArgb: Int? = null,
    val backgroundImageHref: String? = null,
    val textAlign: EpubTextAlign? = null,
    val textIndentEm: Float? = null,
    val lineHeightEm: Float? = null,
    val letterSpacingEm: Float? = null,
    val marginTopEm: Float? = null,
    val marginRightEm: Float? = null,
    val marginBottomEm: Float? = null,
    val marginLeftEm: Float? = null,
    val paddingTopEm: Float? = null,
    val paddingRightEm: Float? = null,
    val paddingBottomEm: Float? = null,
    val paddingLeftEm: Float? = null,
    val borderWidthEm: Float? = null,
    val borderColorArgb: Int? = null,
    val borderTopWidthEm: Float? = null,
    val borderRightWidthEm: Float? = null,
    val borderBottomWidthEm: Float? = null,
    val borderLeftWidthEm: Float? = null,
    val borderTopColorArgb: Int? = null,
    val borderRightColorArgb: Int? = null,
    val borderBottomColorArgb: Int? = null,
    val borderLeftColorArgb: Int? = null,
    val borderRadiusEm: Float? = null,
    val boxShadows: List<EpubBoxShadow> = emptyList(),
    val widthEm: Float? = null,
    val widthFraction: Float? = null,
    val maxWidthEm: Float? = null,
    val maxWidthFraction: Float? = null,
    val heightEm: Float? = null,
    val heightViewportFraction: Float? = null,
    val maxHeightEm: Float? = null,
    val maxHeightViewportFraction: Float? = null,
    val verticalAlign: EpubVerticalAlign = EpubVerticalAlign.BASELINE,
    val float: EpubFloat = EpubFloat.NONE,
    val centerBlock: Boolean = false,
    val opacity: Float = 1f,
    val breakBefore: Boolean = false,
    val breakAfter: Boolean = false,
    val avoidBreakInside: Boolean = false,
    val hidden: Boolean = false
)

@Serializable
enum class EpubTextAlign { START, CENTER, END, JUSTIFY }

@Serializable
enum class EpubVerticalAlign { BASELINE, SUPER, SUB }

@Serializable
enum class EpubFloat { NONE, START, END }

@Serializable
data class EpubLayoutDiagnostic(
    val severity: EpubLayoutDiagnosticSeverity,
    val code: String,
    val message: String,
    val href: String? = null
)

@Serializable
enum class EpubLayoutDiagnosticSeverity { INFO, WARNING, ERROR }

data class EpubLayoutChapterInput(
    val chapterIndex: Int,
    val href: String,
    val document: EpubLayoutChapter
)

data class EpubResolvedFontFace(
    val family: String,
    val filePath: String,
    val weight: Int? = null,
    val italic: Boolean = false
)

data class EpubLayoutChapterBundle(
    val document: EpubLayoutChapter,
    val resourcePaths: Map<String, String>,
    val fontPaths: Map<String, String>,
    val fontFaces: List<EpubResolvedFontFace> = emptyList()
)
