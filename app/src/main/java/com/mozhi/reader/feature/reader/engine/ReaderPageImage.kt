package com.mozhi.reader.feature.reader.engine

/** A single occurrence in the displayed chapter. Convert the anchor before persisting/navigation. */
data class ReaderPageImage(
    val imagePath: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val altText: String = ""
)

/** Hit the painted rectangle, not the entire line containing an inline image. */
fun TextPage.imageAt(x: Float, y: Float, chapterIndex: Int): ReaderPageImage? {
    fun contains(left: Float, top: Float, width: Float, height: Float) =
        width > 0f && height > 0f && x >= left && x < left + width && y >= top && y < top + height
    for (line in lines.asReversed()) {
        for (image in (line.inlineImages + line.inlineGlyphImages).asReversed()) {
            if (contains(image.left, line.lineTop + image.topOffset, image.width, image.height)) {
                return ReaderPageImage(image.imagePath, chapterIndex, image.charOffset ?: line.chapterPosition, image.altText)
            }
        }
        line.inlineImage?.let { image ->
            if (contains(line.startX, line.lineTop, image.width, image.height)) {
                return ReaderPageImage(image.imagePath, chapterIndex, line.chapterPosition, image.altText)
            }
        }
    }
    // Text selection wins over a wallpaper or a heading's decorative background.
    if (lines.any { line -> y >= line.lineTop && y < line.lineBottom &&
            line.columns.any { it.charData.isNotBlank() && x >= it.start && x < it.end } }) return null
    for (decoration in decorations.asReversed()) {
        if (!contains(decoration.left, decoration.top, decoration.right - decoration.left,
                decoration.bottom - decoration.top)) continue
        decoration.backgroundImagePath?.let { path ->
            val anchor = lines.firstOrNull { it.lineBottom > decoration.top }?.chapterPosition ?: chapterPosition
            return ReaderPageImage(path, chapterIndex, anchor)
        }
        // An opaque card hides the page's wallpaper beneath it.
        if (decoration.backgroundColorArgb?.ushr(24) == 255) return null
    }
    return backgroundImagePath?.let { ReaderPageImage(it, chapterIndex, chapterPosition) }
}
