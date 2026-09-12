package com.mozhi.reader.ai.companion

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.ReaderTextAnchorCodec
import com.mozhi.reader.core.library.ReaderTextAnchors
import javax.inject.Inject
import javax.inject.Singleton

data class LibraryCitation(val bookId: Long, val chapterIndex: Int, val quote: String)
data class LocatedLibraryCitation(
    val bookId: Long,
    val chapterIndex: Int,
    val start: Int,
    val end: Int,
    val sourceAnchorJson: String
)
data class ParsedLibraryMessage(val text: String, val citations: List<LibraryCitation>)

object LibraryCitationParser {
    private val marked = Regex("""〔\s*书籍#(\d{1,19})\s+第\s*(\d{1,7})\s*章\s*〕\s*「([^」]{6,600})」""")

    fun parse(raw: String): ParsedLibraryMessage {
        val citations = mutableListOf<LibraryCitation>()
        val text = marked.replace(raw) { match ->
            val bookId = match.groupValues[1].toLongOrNull()
            val chapter = match.groupValues[2].toIntOrNull()
            val quote = match.groupValues[3].trim()
            if (bookId != null && bookId > 0 && chapter != null && chapter > 0 && quote.length >= 6) {
                citations += LibraryCitation(bookId, chapter - 1, quote)
            }
            "「$quote」"
        }
        return ParsedLibraryMessage(text, citations.distinct().take(6))
    }

    fun locate(citation: LibraryCitation, scope: LibraryBookScope, body: String): LocatedLibraryCitation? {
        if (citation.bookId != scope.bookId || citation.quote.length < 6) return null
        val readable = scope.readingScope.readableText(citation.chapterIndex, body)
        val start = readable.indexOf(citation.quote)
        if (start < 0) return null
        val end = start + citation.quote.length
        return LocatedLibraryCitation(scope.bookId, citation.chapterIndex, start, end,
            ReaderTextAnchorCodec.encode(ReaderTextAnchors.create(body, start, end, ChineseConversionMode.OFF)))
    }
}

/** Lazy verification on explicit click, rather than loading every cited chapter on each token. */
@Singleton
class LibraryCitationVerifier @Inject constructor(private val library: LibraryRepository, private val guard: LibraryScopeGuard) {
    suspend fun locate(citation: LibraryCitation, scopes: List<LibraryBookScope>): LocatedLibraryCitation? {
        val scope = scopes.firstOrNull { it.bookId == citation.bookId } ?: return null
        if (!scope.readingScope.allowsChapter(citation.chapterIndex)) return null
        return guard.withVerifiedSource(scope) {
            val chapter = library.getChapter(scope.bookId, citation.chapterIndex) ?: return@withVerifiedSource null
            val body = library.readChapterTextStrict(scope.bookId, chapter)
            LibraryCitationParser.locate(citation, scope, body)
        }
    }
}
