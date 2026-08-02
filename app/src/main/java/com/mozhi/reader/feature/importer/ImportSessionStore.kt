package com.mozhi.reader.feature.importer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class TxtRuleOption(
    val id: Long,
    val name: String,
    val example: String,
    val enabledByDefault: Boolean
)

data class TxtImportPreview(
    val sessionId: String,
    val sourceName: String,
    val suggestedTitle: String,
    val charsetName: String,
    val selectedRuleId: Long?,
    val selectedRuleName: String,
    val usedFallback: Boolean,
    val rules: List<TxtRuleOption>,
    val chapterTitles: List<String>,
    val totalCharacters: Int
)

data class TxtImportSession(
    val id: String,
    val sourceName: String,
    val suggestedTitle: String,
    val charsetName: String,
    val text: String,
    val rules: List<TxtTocRule>,
    val splitResult: TxtSplitResult
) {
    fun toPreview(): TxtImportPreview = TxtImportPreview(
        sessionId = id,
        sourceName = sourceName,
        suggestedTitle = suggestedTitle,
        charsetName = charsetName,
        selectedRuleId = splitResult.rule?.id,
        selectedRuleName = splitResult.rule?.name ?: "按字数自动分节",
        usedFallback = splitResult.usedFallback,
        rules = rules.map { rule ->
            TxtRuleOption(
                id = rule.id,
                name = rule.name,
                example = rule.example,
                enabledByDefault = rule.enable
            )
        },
        chapterTitles = splitResult.chapters.map(TxtChapter::title),
        totalCharacters = splitResult.chapters.sumOf(TxtChapter::charCount)
    )
}

@Serializable
private data class StoredTxtImportSession(
    val id: String,
    val sourceName: String,
    val suggestedTitle: String,
    val charsetName: String,
    val selectedRule: TxtTocRule?
)

@Singleton
class ImportSessionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val chapterSplitter: TxtChapterSplitter,
    private val ruleLoader: TxtTocRuleLoader
) {
    private val sessions = ConcurrentHashMap<String, TxtImportSession>()
    private val directory = File(context.filesDir, "txt_import_sessions").apply { mkdirs() }
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    init {
        cleanupExpiredSessions()
    }

    @Synchronized
    fun create(
        sourceName: String,
        suggestedTitle: String,
        charsetName: String,
        text: String,
        rules: List<TxtTocRule>,
        splitResult: TxtSplitResult
    ): TxtImportSession {
        val sessionId = UUID.randomUUID().toString()
        textFile(sessionId).writeText(text, StandardCharsets.UTF_8)
        val session = TxtImportSession(
            id = sessionId,
            sourceName = sourceName,
            suggestedTitle = suggestedTitle,
            charsetName = charsetName,
            text = text,
            rules = rules,
            splitResult = splitResult
        )
        sessions[session.id] = session
        persist(session)
        return session
    }

    @Synchronized
    fun get(sessionId: String): TxtImportSession? =
        sessions[sessionId] ?: load(sessionId)?.also { sessions[sessionId] = it }

    @Synchronized
    fun update(session: TxtImportSession) {
        sessions[session.id] = session
        persist(session)
    }

    @Synchronized
    fun remove(sessionId: String) {
        sessions.remove(sessionId)
        metadataFileOrNull(sessionId)?.delete()
        textFileOrNull(sessionId)?.delete()
    }

    private fun persist(session: TxtImportSession) {
        val metadata = StoredTxtImportSession(
            id = session.id,
            sourceName = session.sourceName,
            suggestedTitle = session.suggestedTitle,
            charsetName = session.charsetName,
            selectedRule = session.splitResult.rule
        )
        val destination = metadataFile(session.id)
        val temporary = File(directory, "${session.id}.json.tmp")
        temporary.writeText(json.encodeToString(metadata), StandardCharsets.UTF_8)
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun load(sessionId: String): TxtImportSession? = runCatching {
        val metadataFile = requireNotNull(metadataFileOrNull(sessionId))
        val sourceFile = requireNotNull(textFileOrNull(sessionId))
        if (!metadataFile.isFile || !sourceFile.isFile) return@runCatching null

        val metadata = json.decodeFromString<StoredTxtImportSession>(
            metadataFile.readText(StandardCharsets.UTF_8)
        )
        val text = sourceFile.readText(StandardCharsets.UTF_8)
        val splitResult = metadata.selectedRule
            ?.let { chapterSplitter.splitWithRule(text, it) }
            ?: chapterSplitter.chooseBest(text, emptyList())

        TxtImportSession(
            id = metadata.id,
            sourceName = metadata.sourceName,
            suggestedTitle = metadata.suggestedTitle,
            charsetName = metadata.charsetName,
            text = text,
            rules = ruleLoader.rules,
            splitResult = splitResult
        )
    }.getOrNull()

    private fun metadataFile(sessionId: String): File =
        File(directory, "${UUID.fromString(sessionId)}.json")

    private fun textFile(sessionId: String): File =
        File(directory, "${UUID.fromString(sessionId)}.txt")

    private fun metadataFileOrNull(sessionId: String): File? =
        runCatching { metadataFile(sessionId) }.getOrNull()

    private fun textFileOrNull(sessionId: String): File? =
        runCatching { textFile(sessionId) }.getOrNull()

    private fun cleanupExpiredSessions() {
        val cutoff = System.currentTimeMillis() - SESSION_RETENTION_MS
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    private companion object {
        const val SESSION_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
