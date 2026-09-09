package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.ProactiveAnnotationJobEntity
import com.mozhi.reader.core.datastore.CompanionAutonomySettings
import com.mozhi.reader.core.datastore.ProactiveAnnotationLimits
import com.mozhi.reader.core.datastore.ProactiveAnnotationQuota
import com.mozhi.reader.core.datastore.ProactiveAnnotationTiming
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.di.ApplicationScope
import com.mozhi.reader.core.library.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** No annotation text/quote/chapter title is included in completion events. */
data class ProactiveAnnotationBatchResult(
    val bookId: Long,
    val chapterIndex: Int,
    val personaName: String,
    val avatarPath: String?,
    val createdCount: Int,
    val annotationIds: List<Long>,
    val personaId: Long? = null,
    val personaPersonality: String = "",
    val personaSpeakingStyle: String = ""
)

internal fun annotationChapterRange(chapterIndex: Int, ahead: Int, lastIndex: Int): IntRange =
    chapterIndex.coerceAtLeast(0)..minOf(chapterIndex + ahead.coerceIn(0, 5), lastIndex)

internal fun ProactiveAnnotationJobEntity.canAttempt(now: Long): Boolean =
    status != "DONE" && attempts < 2 && (status != "PENDING" || now - updatedAt >= 10 * 60_000)

/** Only policy that changes generation for this book belongs in the cancellation identity. */
internal data class ProactiveBookPolicy(
    val enabled: Boolean,
    val personaId: Long?,
    val limits: ProactiveAnnotationLimits,
    val voice: Boolean,
    val images: Boolean
)

internal fun CompanionAutonomySettings.policyFor(bookId: Long, personaId: Long?) = ProactiveBookPolicy(
    proactiveAnnotationsEnabled, personaId, annotationLimitsFor(bookId).normalized(),
    annotationVoiceActive, annotationImageActive
)

/** One worker for the entire process. Queue is bounded to six chapters of the visible book. */
@Singleton
class ProactiveAnnotationScheduler @Inject constructor(
    private val database: MoReadDatabase,
    private val library: LibraryRepository,
    private val personas: PersonaRepository,
    private val settings: ReaderSettingsRepository,
    private val quota: ProactiveAnnotationQuota,
    private val service: ProactiveAnnotationService,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private data class Trigger(val bookId: Long, val chapterIndex: Int, val timing: ProactiveAnnotationTiming)
    private val lock = Any()
    private val pending = linkedSetOf<Trigger>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var readerBook: Long? = null
    private var active: Trigger? = null
    private var activeVersion: Pair<Long, Long>? = null
    private var policyVersion = 0L
    private var readerVersion = 0L
    private var noticeVersion = 0L
    private var observedPolicy: ProactiveBookPolicy? = null
    private var lastEntered: Trigger? = null
    private var lastCompleted: Trigger? = null
    private val commits = ProactiveAnnotationCommitStore(database)
    private val mutableResults = MutableSharedFlow<ProactiveAnnotationBatchResult>(extraBufferCapacity = 8)
    val results = mutableResults.asSharedFlow()

    init {
        applicationScope.launch {
            combine(settings.companionAutonomySettings, settings.activePersonaId) { autonomy, persona ->
                autonomy to persona
            }.collect { (autonomy, persona) ->
                applyPolicy(autonomy, persona).forEach(::enqueue)
            }
        }
        applicationScope.launch {
            for (ignored in wake) {
                while (true) {
                    val trigger = synchronized(lock) {
                        pending.firstOrNull()?.also {
                            pending.remove(it)
                            active = it
                            activeVersion = policyVersion to readerVersion
                        }
                    } ?: break
                    try { run(trigger) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Worker survives repository errors; interrupted jobs are resumable. */ }
                    finally { synchronized(lock) { active = null; activeVersion = null } }
                }
            }
        }
    }

    /** Also called by enqueue: a late collector cannot invalidate a just-enqueued new policy. */
    private fun applyPolicy(autonomy: CompanionAutonomySettings, personaId: Long?): List<Trigger> = synchronized(lock) {
        val bookId = readerBook ?: return@synchronized emptyList()
        val resolved = autonomy.policyFor(bookId, personaId)
        if (observedPolicy == resolved) return@synchronized emptyList()
        if (observedPolicy?.enabled != resolved.enabled || observedPolicy?.personaId != resolved.personaId) noticeVersion++
        observedPolicy = resolved
        policyVersion++
        pending.clear()
        if (resolved.enabled) listOfNotNull(lastEntered, lastCompleted).filter { it.timing == resolved.limits.timing }
        else emptyList()
    }

    fun setReaderBook(bookId: Long?) {
        synchronized(lock) {
            if (readerBook != bookId) {
                pending.clear(); readerVersion++
                lastEntered = null; lastCompleted = null; observedPolicy = null
            }
            readerBook = bookId
        }
    }

    fun clearReaderBook(bookId: Long) {
        synchronized(lock) {
            if (readerBook == bookId) setReaderBook(null)
        }
    }

    fun onChapterEntered(bookId: Long, chapterIndex: Int) {
        val trigger = Trigger(bookId, chapterIndex, ProactiveAnnotationTiming.ON_CHAPTER_ENTRY)
        synchronized(lock) {
            if (readerBook != bookId) return
            lastEntered = trigger // Remember even with master off or AFTER timing.
        }
        enqueue(trigger)
    }

    fun onChapterCompleted(bookId: Long, chapterIndex: Int) {
        val trigger = Trigger(bookId, chapterIndex, ProactiveAnnotationTiming.AFTER_CHAPTER_COMPLETE)
        synchronized(lock) {
            if (readerBook != bookId) return
            lastCompleted = trigger
        }
        enqueue(trigger)
    }

    private fun enqueue(trigger: Trigger) {
        val readerEpoch = synchronized(lock) { readerVersion }
        applicationScope.launch {
            val autonomy = settings.companionAutonomySettings.first()
            val personaId = settings.activePersonaId.first()
            applyPolicy(autonomy, personaId).filter { it != trigger }.forEach(::enqueue)
            val policy = autonomy.policyFor(trigger.bookId, personaId)
            if (!policy.enabled || policy.limits.timing != trigger.timing) return@launch
            val version = synchronized(lock) { policyVersion to readerVersion }
            val reportedLast = (library.getBook(trigger.bookId)?.totalChapters ?: return@launch) - 1
            val last = maxOf(reportedLast, trigger.chapterIndex)
            val ahead = if (trigger.timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY) policy.limits.aheadChapters else 0
            synchronized(lock) {
                if (readerBook != trigger.bookId || readerEpoch != readerVersion ||
                    version != (policyVersion to readerVersion) || observedPolicy != policy) return@synchronized
                for (index in annotationChapterRange(trigger.chapterIndex, ahead, last)) {
                    val next = trigger.copy(chapterIndex = index)
                    if ((next != active || activeVersion != version) && pending.size < 6) pending.add(next)
                }
            }
            wake.trySend(Unit)
        }
    }

    private suspend fun run(trigger: Trigger) {
        val version = synchronized(lock) {
            if (readerBook != trigger.bookId) return
            policyVersion to readerVersion
        }
        val noticeEpoch = synchronized(lock) { noticeVersion }
        fun readerContextValid(): Boolean = synchronized(lock) {
            version == (policyVersion to readerVersion) && readerBook == trigger.bookId
        }
        val initial = settings.companionAutonomySettings.first().policyFor(trigger.bookId, settings.activePersonaId.first())
        if (!initial.enabled || initial.limits.timing != trigger.timing) return
        val dao = database.proactiveAnnotationJobDao()
        fun startOfDay(): Long = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Exhausted global budgets must not repeatedly load/hash a chapter or touch its PAUSED ledger.
        val budget = quota.reserve(initial.limits, requestVoice = false, requestImages = false)
        if (!budget.accepted || budget.maxAnnotations <= 0) return
        if (!initial.limits.dailyUnlimited && dao.createdSince(startOfDay()) >= initial.limits.dailyMax) return
        if (!readerContextValid()) return
        val personaId = initial.personaId ?: return
        val persona = personas.getPersona(personaId) ?: return
        val chapter = library.getChapter(trigger.bookId, trigger.chapterIndex) ?: return
        val body = library.readChapterText(trigger.bookId, chapter)
        if (body.isBlank() || library.getBook(trigger.bookId) == null) return
        val revision = ProactiveAnnotationParagraphs.revision(body)
        if (!readerContextValid()) return
        val now = System.currentTimeMillis()
        val old = dao.find(trigger.bookId, trigger.chapterIndex, personaId, revision)
        if (old != null && !old.canAttempt(now)) return
        // attempts counts completed FAILED generation rounds, not lifecycle/policy/quota interruptions.
        var job = (old ?: ProactiveAnnotationJobEntity(
            bookId = trigger.bookId, chapterIndex = trigger.chapterIndex, personaId = personaId,
            sourceRevision = revision, createdAt = now, updatedAt = now
        )).copy(status = "PENDING", updatedAt = now, failureReason = null)
        if (old == null) {
            val id = dao.insert(job)
            if (id == -1L) return
            job = job.copy(id = id)
        } else dao.update(job)
        val done = runCatching {
            Json.decodeFromString<List<Int>>(job.doneParagraphEnds).toMutableSet().also { ends -> require(ends.all { it >= 0 }) }
        }.getOrElse {
            dao.update(job.copy(status = "FAILED", attempts = 2, failureReason = "invalid_paragraph_ledger"))
            return
        }
        val ids = mutableListOf<Long>()
        suspend fun valid(): Boolean {
            if (!readerContextValid()) return false
            val currentPolicy = settings.companionAutonomySettings.first().policyFor(trigger.bookId, settings.activePersonaId.first())
            if (currentPolicy != initial) return false
            if (library.getBook(trigger.bookId) == null || personas.getPersona(personaId) == null) return false
            val current = library.getChapter(trigger.bookId, trigger.chapterIndex) ?: return false
            return ProactiveAnnotationParagraphs.revision(library.readChapterText(trigger.bookId, current)) == revision && readerContextValid()
        }
        suspend fun allowance(): com.mozhi.reader.core.datastore.ProactiveAnnotationAllowance? {
            if (!valid()) return null
            val limits = initial.limits
            val chapterCap = if (limits.chapterUnlimited) ProactiveAnnotationLimits.MAX_PER_CHAPTER else limits.maxPerChapter
            if (done.size >= chapterCap) return null
            if (!limits.dailyUnlimited && dao.createdSince(startOfDay()) >= limits.dailyMax) return null
            return quota.reserve(limits, initial.voice && persona.voiceId.isNotBlank(), initial.images)
                .takeIf { it.accepted && it.maxAnnotations > 0 && readerContextValid() }
        }
        var outcome = ProactiveAnnotationGenerationResult(failed = false, stopped = true)
        try {
            val limits = initial.limits
            outcome = service.generateForChapter(
                request = ProactiveAnnotationRequest(trigger.bookId, trigger.chapterIndex, body, persona,
                    if (limits.chapterUnlimited) ProactiveAnnotationLimits.MAX_PER_CHAPTER else limits.maxPerChapter, done.toSet()),
                allowance = { allowance() },
                recordMedia = { voices, images ->
                    check(valid()) { "generation_context_changed_before_media_charge" }
                    quota.recordCreated(0, voices, images)
                },
                commit = { row, end, illustration ->
                    if (end in done || allowance() == null) false
                    else {
                        // Source validation, SHA, DataStore and JSON encoding are outside SQLite's write lock.
                        val updated = job.copy(doneParagraphEnds = Json.encodeToString((done + end).sorted()), updatedAt = System.currentTimeMillis())
                        val committed = commits.commit(row, updated, illustration,
                            if (limits.dailyUnlimited) Int.MAX_VALUE else limits.dailyMax, startOfDay(), ::readerContextValid)
                        if (committed == null) false else {
                            ids += committed.annotationId
                            job = committed.job
                            done += end
                            // DB origin counts cover failed DataStore bookkeeping; never undo committed successes.
                            runCatching { quota.recordCreated(1, 0, 0) }
                            true
                        }
                    }
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled // PENDING restarts after timeout without consuming a generation failure.
        } catch (_: Exception) {
            outcome = ProactiveAnnotationGenerationResult(failed = true, stopped = false)
        }
        val interrupted = outcome.stopped || !valid()
        val completed = !outcome.failed && !outcome.stopped
        job = when {
            completed -> job.copy(status = "DONE", failureReason = null)
            interrupted -> job.copy(status = "PAUSED", failureReason = "policy_lifecycle_or_quota")
            else -> job.copy(status = "FAILED", attempts = job.attempts + 1, failureReason = "generation_failed")
        }
        dao.update(job.copy(updatedAt = System.currentTimeMillis()))
        // Quota/timing/media/notice-copy edits don't invalidate a count notice for already committed rows.
        // Disabled master, persona change or any reader lifecycle transition still suppress publication.
        val autonomy = settings.companionAutonomySettings.first()
        if (ids.isNotEmpty() && autonomy.noticeActive && settings.activePersonaId.first() == personaId) {
            synchronized(lock) {
                if (version.second == readerVersion && noticeEpoch == noticeVersion && readerBook == trigger.bookId) {
                    mutableResults.tryEmit(ProactiveAnnotationBatchResult(trigger.bookId, trigger.chapterIndex,
                        persona.name, persona.avatarPath, ids.size, ids.toList(), personaId,
                        persona.personality, persona.speakingStyle))
                }
            }
        }
    }
}
