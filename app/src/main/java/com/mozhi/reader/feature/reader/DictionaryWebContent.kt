package com.mozhi.reader.feature.reader

import android.net.Uri
import android.webkit.*
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mozhi.reader.core.dictionary.DictionaryDefinition
import com.mozhi.reader.core.dictionary.LocalDictionaryRepository
import java.io.ByteArrayInputStream
import com.mozhi.reader.ui.components.blockSheetDrag

/** Local-only origin: dictionary HTML cannot execute scripts, read files, or contact a server. */
@Composable
internal fun DictionaryWebContent(entry: DictionaryDefinition, dark: Boolean, repository: LocalDictionaryRepository,
    onLookup: (String) -> Unit, modifier: Modifier = Modifier) {
    val lookup by rememberUpdatedState(onLookup)
    val html = remember(entry, dark) { dictionaryHtml(entry.html, dark) }
    var savedScrollY by rememberSaveable(entry.dictionaryId, entry.html) { mutableIntStateOf(0) }
    var simple by rememberSaveable(entry.dictionaryId) { mutableStateOf(false) }
    var failure by remember(entry) { mutableStateOf<String?>(null) }
    val plain = remember(entry.html) { dictionaryPlainText(entry.html) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { if (simple || failure != null) { simple = false; failure = null } else simple = true }) {
                Text(if (simple || failure != null) "查看词典排版" else "简明释义")
            }
        }
        if (simple || failure != null) {
            val scroll = rememberScrollState()
            Column(Modifier.weight(1f).fillMaxWidth().blockSheetDrag(scroll).verticalScroll(scroll).padding(20.dp)) {
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                SelectionContainer { Text(plain, style = MaterialTheme.typography.bodyLarge) }
            }
        } else AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
        runCatching { WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Static dictionary pages do not need a GPU layer. Software compositing avoids
            // blank/black WebView surfaces inside a translated Compose dialog on some devices.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            settings.defaultTextEncodingName = "UTF-8"
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
        } }.getOrElse { failure = "词典排版无法启动，已显示简明释义"; android.widget.FrameLayout(context) }
    }, update = { view ->
        if (view is WebView) {
        val key = entry.dictionaryId + html
        if (view.tag != key) {
            view.tag = key
            val restoreY = savedScrollY
            var restoring = true
            view.setOnScrollChangeListener { _, _, scrollY, _, _ -> if (!restoring) savedScrollY = scrollY }
            view.setBackgroundColor(if (dark) 0xff202020.toInt() else 0xfffafafa.toInt())
            val pageUrl = dictionaryPageUrl(entry.dictionaryId, html)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(webView: WebView, url: String?) {
                    webView.post { webView.scrollTo(0, restoreY); restoring = false }
                }
                override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failure = "词典排版加载失败，已显示简明释义"
                }
                override fun onRenderProcessGone(webView: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failure = "词典渲染中断，已显示简明释义"
                    return true
                }
                override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "entry") {
                        val word = Uri.decode(uri.toString().removePrefix("entry://").substringBefore('#'))
                        webView.post { lookup(word) }
                    } else if (uri.host == DICTIONARY_HOST && uri.fragment != null) return false
                    return true
                }
                override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse {
                    return dictionaryWebResponse(entry.dictionaryId, html, request) { path -> repository.resource(entry.dictionaryId, path) }
                }
            }
            // Explicitly serve the main document as HTML. Never return an empty 200 for a
            // main-frame load or a missing resource; that looks successful but paints nothing.
            view.loadUrl(pageUrl)
        }
        }
    }, onRelease = { if (it is WebView) { it.stopLoading(); it.destroy() } })
    }
}

internal fun dictionaryPlainText(source: String): String {
    val document = org.jsoup.Jsoup.parse(source.take(2_000_000))
    document.select("script,style,iframe,object").remove()
    return document.body().wholeText().trim().ifBlank { "这条释义没有可显示的文字，可查看词典排版或切换其他词典。" }
}

internal const val DICTIONARY_HOST = "dictionary.moread.invalid"
internal fun dictionaryPageUrl(id: String, html: String): String =
    "https://$DICTIONARY_HOST/$id/entry.html?v=${html.hashCode()}"

internal fun dictionaryWebResponse(id: String, html: String, request: WebResourceRequest,
    resource: (String) -> ByteArray?): WebResourceResponse {
    val uri = request.url
    val local = uri.scheme == "https" && uri.host == DICTIONARY_HOST
    val main = local && request.isForMainFrame && uri.path == "/$id/entry.html"
    val path = uri.path.orEmpty().removePrefix("/$id/").trimStart('/')
    val bytes = when {
        main -> html.toByteArray(Charsets.UTF_8)
        local && !request.isForMainFrame -> runCatching { resource(path) }.getOrNull()
        else -> null
    }
    val extension = path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
    val mime = if (main) "text/html" else when (extension) {
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
    return WebResourceResponse(mime, "UTF-8", if (bytes != null) 200 else 404,
        if (bytes != null) "OK" else "Not Found", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
        ByteArrayInputStream(bytes ?: byteArrayOf()))
}

internal fun dictionaryHtml(source: String, dark: Boolean): String {
    val doc = org.jsoup.Jsoup.parse(source.take(2_000_000))
    doc.select("script,iframe,frame,object,embed,form,base,meta[http-equiv]").remove()
    doc.allElements.forEach { node ->
        node.attributes().asList().filter { it.key.startsWith("on", true) }.forEach { node.removeAttr(it.key) }
    }
    doc.head().prependElement("meta").attr("name", "viewport").attr("content", "width=device-width,initial-scale=1")
    doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
        .attr("content", "default-src 'none'; img-src https://$DICTIONARY_HOST data:; style-src 'unsafe-inline' https://$DICTIONARY_HOST; font-src https://$DICTIONARY_HOST; media-src https://$DICTIONARY_HOST;")
    doc.head().appendElement("style").text("body{margin:16px;line-height:1.65;overflow-wrap:anywhere;color:${if (dark) "#dedede" else "#242424"};background:${if (dark) "#202020" else "#fafafa"};font-size:17px}img{max-width:100%;height:auto}table{max-width:100%}a{color:${if (dark) "#b8cfff" else "#305e9d"}}")
    if (dark) doc.head().appendElement("style").text("html,body{background:#202020!important;color:#dedede!important}body *{color:inherit!important;background-color:transparent!important}a{color:#b8cfff!important}")
    // Some MDX distributions contain only styled spans and expect a separate CSS/JS bundle.
    // Supply a readable layout for this vocabulary instead of depending on dictionary scripts.
    if (doc.select("[class*=oalecd8e]").isNotEmpty()) {
        doc.body().attr("data-moread-format", "oxford")
        doc.head().appendElement("style").text("""
            body{font-family:system-ui,sans-serif}
            .entry,.h-g,.top-g,.pos-g,.sn-g,.def-g,.x-g,.idiom-g,.id-g,.sense-g,.xr-g{display:block!important;visibility:visible!important;opacity:1!important}
            .entry{margin-bottom:1.2em}.h{font-size:1.65em;font-weight:700;color:${if (dark) "#bdceef" else "#31598a"}!important}
            .ei-g{font-family:serif}.pos-g{margin:.35em 0;font-style:italic}.sn-g,.def-g{margin:.7em 0}
            .oalecd8e_chn{display:inline!important;visibility:visible!important}.d>.oalecd8e_chn{display:block!important;margin-top:.2em}
            .x-g{margin:.6em 0;padding-left:.8em;border-left:2px solid ${if (dark) "#66758e" else "#bbc9dd"}}
            .x-g>.oalecd8e_chn{display:block!important}.x{font-style:italic}.idm,.id{font-weight:600}.id-g{margin:1em 0}
            .xr-g{margin-top:.8em}.oalecd8e_show_all,.pracpron,img.fayin{display:none!important}
        """.trimIndent())
    }
    return doc.outerHtml()
}
