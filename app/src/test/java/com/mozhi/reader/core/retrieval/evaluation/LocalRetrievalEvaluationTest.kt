package com.mozhi.reader.core.retrieval.evaluation

import com.mozhi.reader.ai.agent.ChapterDocument
import com.mozhi.reader.ai.agent.loadReadableCorpus
import com.mozhi.reader.ai.agent.parseChapterSearchBounds
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.*
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.importer.EpubPackageInspector
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in private corpus runner. No network/model calls and no copyrighted books in test resources. */
class LocalRetrievalEvaluationTest {
    private val json = Json { prettyPrint = true }

    @Test fun runConfiguredLocalEvaluation() = runBlocking {
        val manifestPath = System.getenv("MOREAD_RETRIEVAL_EVAL_MANIFEST").orEmpty()
        assumeTrue("Optional local corpus; set MOREAD_RETRIEVAL_EVAL_MANIFEST", manifestPath.isNotBlank())
        val manifest = File(manifestPath).canonicalFile
        require(manifest.isFile) { "Local evaluation manifest not found" }
        val config = json.parseToJsonElement(manifest.readText()).jsonObject
        val root = requireNotNull(manifest.parentFile)
        when (System.getenv("MOREAD_RETRIEVAL_EVAL_ACTION")?.lowercase() ?: "evaluate") {
            "export" -> config.getValue("books").jsonArray.forEach { export(it.jsonObject, root) }
            "evaluate" -> evaluate(config, root)
            else -> error("Action must be export or evaluate")
        }
    }

    private fun export(book: JsonObject, root: File) {
        val key = book.string("key")
        require(key.matches(Regex("[a-zA-Z0-9_-]+")))
        val file = File(book.string("epub"))
        require(file.isFile)
        val inspector = EpubPackageInspector()
        val layout = inspector.inspect(file)
        val stylesheets = inspector.readStylesheets(file, layout)
        val parser = EpubLayoutDocumentParser()
        var chars = 0L
        val chapters = buildJsonArray {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().filterNot { it.isDirectory }
                    .associateBy { it.name.replace('\\', '/').lowercase() }
                layout.spine.filter { it.linear && it.href != null }.forEachIndexed { index, spine ->
                    val href = requireNotNull(spine.href)
                    val resource = layout.resources.first { it.href.equals(href, true) }
                    val entry = requireNotNull(entries[resource.archivePath.lowercase()])
                    val bytes = zip.getInputStream(entry).use { it.readNBytes(8 * 1024 * 1024 + 1) }
                    require(bytes.size <= 8 * 1024 * 1024) { "Chapter exceeds extraction bound" }
                    val parsed = parser.parseWithText(bytes, index, href, stylesheets)
                    chars += parsed.text.length
                    require(chars <= 20_000_000) { "Corpus exceeds the production lexical scan bound" }
                    add(buildJsonObject {
                        put("index", index); put("href", href)
                        put("title", parsed.document.documentTitle.orEmpty())
                        put("text", parsed.text); put("textSha256", digest(parsed.text.toByteArray(Charsets.UTF_8)))
                    })
                }
            }
        }
        val snapshot = buildJsonObject {
            put("schemaVersion", 1); put("parserTextVersion", LibraryRepository.CURRENT_TEXT_VERSION)
            put("id", book.getValue("id")); put("title", book.getValue("title"))
            put("sourceSha256", file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().hex()
            })
            put("chapters", chapters)
        }
        File(root, "$key.corpus.json").writeText(json.encodeToString(JsonObject.serializer(), snapshot))
        println("Exported $key: ${chapters.size} chapters, $chars UTF-16 characters; local only")
    }

    private suspend fun evaluate(config: JsonObject, root: File) {
        val results = mutableListOf<JsonObject>()
        config.getValue("books").jsonArray.forEach { value ->
            val book = value.jsonObject
            val key = book.string("key")
            require(key.matches(Regex("[a-zA-Z0-9_-]+")))
            val snapshot = json.parseToJsonElement(File(root, "$key.corpus.json").readText()).jsonObject
            require(snapshot.getValue("parserTextVersion").jsonPrimitive.int == LibraryRepository.CURRENT_TEXT_VERSION) {
                "Re-export snapshots after a parser format upgrade"
            }
            val chapters = snapshot.getValue("chapters").jsonArray.map { it.jsonObject }
            val cases = json.parseToJsonElement(File(root, "$key.cases.json").readText()).jsonObject
            require(cases.string("sourceSha256") == snapshot.string("sourceSha256")) { "Corpus changed: $key" }
            val documents = chapters.associate { it.getValue("index").jsonPrimitive.int to
                ChapterDocument(it.getValue("index").jsonPrimitive.int, it.string("title"), it.string("text")) }
            cases.getValue("queries").jsonArray.forEach { item ->
                val case = item.jsonObject
                val query = case.string("query")
                require(query.length in 1..512)
                val progressChapter = case["progressChapter"]?.jsonPrimitive?.int
                val allowed = if (progressChapter == null) ReadingScope.WholeBook else
                    ReadingScope.upto(progressChapter - 1, case.getValue("progressChar").jsonPrimitive.int)
                val range = parseChapterSearchBounds(case).resolve(chapters.size, allowed)
                val corpus = loadReadableCorpus(book.getValue("id").jsonPrimitive.long, chapters.size, range.scope,
                    { documents[it] }, { emptyList() }, range.firstIndex)
                check(corpus.complete) { "Incomplete corpus for ${case.string("id")}" }
                val evidence = case.getValue("evidence").jsonArray.map { it.jsonObject }
                evidence.forEach { gold ->
                    val chapter = gold.getValue("chapter").jsonPrimitive.int
                    val document = documents.getValue(chapter)
                    require(digest(document.body.toByteArray()) == gold.string("chapterSha256")) { "Realign changed chapter annotations" }
                    val start = gold.getValue("start").jsonPrimitive.int
                    val end = gold.getValue("end").jsonPrimitive.int
                    require(start >= 0 && end > start && end <= document.body.length)
                    require(document.body.substring(start, end) == gold.string("quote")) { "Gold range no longer matches" }
                    require(chapter in range.firstIndex..range.lastIndex && range.scope.allowsChunk(chapter, start, end))
                }
                val judgments = corpus.candidates.mapNotNull { candidate ->
                    val grade = evidence.filter { gold ->
                        if (gold.getValue("chapter").jsonPrimitive.int != candidate.chapterIndex) false else {
                            val start = gold.getValue("start").jsonPrimitive.int
                            val end = gold.getValue("end").jsonPrimitive.int
                            (minOf(end, candidate.endCharOffset) - maxOf(start, candidate.startCharOffset)).coerceAtLeast(0) >= (end - start) * .5
                        }
                    }.maxOfOrNull { it["grade"]?.jsonPrimitive?.int ?: 3 } ?: 0
                    if (grade > 0) candidate.key to grade else null
                }.toMap()
                require(evidence.isEmpty() || judgments.isNotEmpty()) { "No candidate overlaps gold for ${case.string("id")}" }
                val start = System.nanoTime()
                val recalled = Bm25LexicalRecall.rank(corpus.candidates, query, 64)
                val recallMetrics = rankingMetrics(recalled.map { it.key }, judgments, 64)
                val pipeline = RetrievalPipeline(RetrievalRecall { emptyList() }, RetrievalRecall { recalled })
                val result = pipeline.retrieve(RetrievalRequest(book.getValue("id").jsonPrimitive.long, query, range.scope,
                    topK = 8, recallDepth = 64, sort = RetrievalSort.RELEVANCE, firstChapterIndex = range.firstIndex,
                    maxChunksPerChapter = if (range.firstIndex == range.lastIndex &&
                        ("from_chapter" in case || "to_chapter" in case)) 8 else 2))
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                check(result.hits.all { it.chapterIndex in range.firstIndex..range.lastIndex &&
                    range.scope.allowsChunk(it.chapterIndex, it.startCharOffset, it.endCharOffset) })
                val metrics = rankingMetrics(result.hits.map { it.key }, judgments)
                results += buildJsonObject {
                    put("book", key); put("id", case.getValue("id")); put("query", query)
                    put("kind", case["kind"] ?: JsonPrimitive("unspecified"))
                    put("scoped", "from_chapter" in case || "to_chapter" in case)
                    put("labelStatus", cases["labelStatus"] ?: JsonPrimitive("draft"))
                    put("hasPositiveLabels", evidence.isNotEmpty()); put("recallAt8", metrics.recall)
                    put("ndcgAt8", metrics.ndcg); put("mrr", metrics.mrr); put("rankingMs", elapsed)
                    put("recallAt64", recallMetrics.recall)
                    put("returned", result.hits.size); put("relevantDocuments", metrics.relevantDocuments)
                    put("scopeViolations", 0)
                    putJsonArray("hits") { result.hits.forEach { add(buildJsonObject {
                        put("chapter", it.chapterIndex); put("start", it.startCharOffset); put("end", it.endCharOffset)
                        put("relevance", judgments[it.key] ?: 0)
                    }) } }
                }
            }
        }
        val positive = results.filter { it.getValue("hasPositiveLabels").jsonPrimitive.boolean }
        fun mean(key: String) = if (positive.isEmpty()) 0.0 else positive.map { it.getValue(key).jsonPrimitive.double }.average()
        val report = buildJsonObject {
            put("generatedAt", java.time.Instant.now().toString())
            put("mode", "lexical-only; no embedding or reranker calls; ranked anchors before neighbor expansion")
            put("labelCaveat", "Draft labels require human review. This is not a production hybrid-quality or mobile-latency claim.")
            put("cases", results.size); put("positiveCases", positive.size)
            put("recallAt8", mean("recallAt8")); put("ndcgAt8", mean("ndcgAt8")); put("mrr", mean("mrr"))
            put("recallAt64", mean("recallAt64"))
            put("thresholdCalibration", "Not attempted: no embedding or cross-encoder fixtures")
            put("scopeViolations", 0); put("results", JsonArray(results))
        }
        val encoded = json.encodeToString(JsonObject.serializer(), report)
        File(root, "reports").apply { mkdirs() }.let { directory ->
            File(directory, "lexical-${System.currentTimeMillis()}.json").writeText(encoded)
        }
        File(root, "lexical-baseline.json").writeText(encoded)
        println("Local lexical baseline: ${results.size} cases, Recall@8=${mean("recallAt8")}, nDCG@8=${mean("ndcgAt8")}, MRR@8=${mean("mrr")}, Recall@64=${mean("recallAt64")}; scope violations=0")
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}
