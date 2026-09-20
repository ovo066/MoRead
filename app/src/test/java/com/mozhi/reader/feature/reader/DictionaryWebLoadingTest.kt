package com.mozhi.reader.feature.reader

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DictionaryWebLoadingTest {
    private fun request(url: String, main: Boolean) = mockk<WebResourceRequest> {
        every { getUrl() } returns Uri.parse(url)
        every { isForMainFrame } returns main
    }

    @Test fun mainDocumentIsServedAsHtmlAndNeverReplacedByAnEmptySuccessfulResource() {
        val html = dictionaryHtml("<h2>词目</h2><p>释义</p>", false)
        val response = dictionaryWebResponse("one", html, request(dictionaryPageUrl("one", html), true)) { error("Main HTML must not be looked up in MDD") }
        assertEquals("text/html", response.mimeType)
        assertEquals(200, response.statusCode)
        assertEquals(html, response.data.reader().readText())
        assertEquals("no-store", response.responseHeaders["Cache-Control"])
    }

    @Test fun missingResourcesAreExplicit404AndBothRelativeAndRootMddPathsResolve() {
        val paths = mutableListOf<String>()
        for (url in listOf("https://$DICTIONARY_HOST/one/style.css", "https://$DICTIONARY_HOST/pic/book.svg")) {
            val response = dictionaryWebResponse("one", "", request(url, false)) { paths += it; "resource".toByteArray() }
            assertEquals(200, response.statusCode)
        }
        assertEquals(listOf("style.css", "pic/book.svg"), paths)
        assertEquals(404, dictionaryWebResponse("one", "", request("https://$DICTIONARY_HOST/one/missing.css", false)) { null }.statusCode)
        assertEquals(404, dictionaryWebResponse("one", "", request("https://example.com/data", false)) { error("No external fetch") }.statusCode)
        assertEquals(404, dictionaryWebResponse("one", "", request("https://$DICTIONARY_HOST/two/entry.html", true)) { error("Wrong dictionary") }.statusCode)
    }
}
