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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    val personaSpeakingStyle: String = "",
    /**
     * 今日额度已用完，这一章不会再生成。没有这条，用户只能看到「连着好几章都不段评」，
     * 而预生成（提前 N 章）会把额度提前花在还没读到的章节上，更难自己想明白。
     */
    val dailyBudgetExhausted: Boolean = false
)

internal fun annotationChapterRange(chapterIndex: Int, ahead: Int, lastIndex: Int): IntRange =
    chapterIndex.coerceAtLeast(0)..minOf(chapterIndex + ahead.coerceIn(0, 5), lastIndex)

// The sole worker cannot race another attempt. A PENDING row is an interrupted process's
// checkpoint, so waiting ten minutes only leaves a freshly reopened chapter without notes.
internal fun ProactiveAnnotationJobEntity.canAttempt(): Boolean = status != "DONE" && attempts < 2

internal const val PROACTIVE_ANNOTATION_RETRY_DELAY_MS = 2_000L

internal fun annotationBudgetShare(remaining: Int, personas: Int): Int =
    if (remaining == Int.MAX_VALUE) Int.MAX_VALUE
    else ((remaining.coerceAtLeast(0).toLong() + personas.coerceAtLeast(1) - 1) / personas.coerceAtLeast(1)).toInt()

/** Only policy that changes generation for this book belongs in the cancellation identity. */
internal data class ProactiveBookPolicy(
    val enabled: Boolean,
    val personaIds: List<Long>,
    val limits: ProactiveAnnotationLimits,
    val voice: Boolean,
    val images: Boolean,
    val prompts: List<com.mozhi.reader.core.datastore.GlobalPromptPreset>
)

internal fun CompanionAutonomySettings.policyFor(bookId: Long, personaId: Long?) = ProactiveBookPolicy(
    proactiveAnnotationsEnabled, annotationPersonasFor(personaId), annotationLimitsFor(bookId).normalized(),
    annotationVoiceActive, annotationImageActive, annotationPrompts.filter { it.enabled }
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
    private val retries = mutableMapOf<Trigger, Job>()
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
    private var exhaustedNoticeDay = Long.MIN_VALUE
    private val exhaustedNoticeBooks = mutableSetOf<Long>()
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
                            retries.remove(it)?.cancel()
                            active = it
                            activeVersion = policyVersion to readerVersion
                        }
                    } ?: break
                    try { run(trigger) }
                    catch (_: CancellationException) {
                        // Only our own scope dying may stop the worker; a job-local cancellation
                        // (request timeout, cancelled child) must not silence annotations for the
                        // rest of the process.
                        currentCoroutineContext().ensureActive()
                    }
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
        if (observedPolicy?.enabled != resolved.enabled || observedPolicy?.personaIds != resolved.personaIds) noticeVersion++
        observedPolicy = resolved
        policyVersion++
        pending.clear()
        retries.values.forEach { it.cancel() }; retries.clear()
        if (resolved.enabled) listOfNotNull(lastEntered, lastCompleted).filter { it.timing == resolved.limits.timing }
        else emptyList()
    }

    fun setReaderBook(bookId: Long?) {
        synchronized(lock) {
            if (readerBook != bookId) {
                pending.clear(); readerVersion++
                retries.values.forEach { it.cancel() }; retries.clear()
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
            if (observedPolicy?.limits?.timing == trigger.timing) {
                pending.removeAll { !wanted(it) }
                retries.keys.filter { !wanted(it) }.forEach { retries.remove(it)?.cancel() }
            }
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
                if (trigger.timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY) {
                    // Async repository reads may finish out of order. Only the latest entry owns
                    // the window; replace old lookahead instead of dropping the new chapter.
                    if (lastEntered != trigger) return@synchronized
                    pending.clear()
                }
                for (index in annotationChapterRange(trigger.chapterIndex, ahead, last)) {
                    val next = trigger.copy(chapterIndex = index)
                    if ((next != active || activeVersion != version) && pending.size < 6) pending.add(next)
                }
            }
            wake.trySend(Unit)
        }
    }

    /** Called under lock. Entry-mode work follows the current chapter and its lookahead. */
    private fun wanted(trigger: Trigger): Boolean {
        if (readerBook != trigger.bookId) return false
        if (trigger.timing != ProactiveAnnotationTiming.ON_CHAPTER_ENTRY) return true
        val entered = lastEntered ?: return false
        val ahead = observedPolicy?.limits?.aheadChapters ?: 0
        return trigger.chapterIndex in annotationChapterRange(entered.chapterIndex, ahead, Int.MAX_VALUE)
    }

    private fun retry(trigger: Trigger, version: Pair<Long, Long>) {
        synchronized(lock) {
            if (version != (policyVersion to readerVersion) || !wanted(trigger) || trigger in retries) return
            retries[trigger] = applicationScope.launch {
                delay(PROACTIVE_ANNOTATION_RETRY_DELAY_MS)
                synchronized(lock) {
                    if (version != (policyVersion to readerVersion) || !wanted(trigger)) return@launch
                    retries.remove(trigger)
                    if (pending.size < 6) addPending(trigger)
                }
                wake.trySend(Unit)
            }
        }
    }

    /** Called under lock; retries and resumed lookahead never jump ahead of the visible chapter. */
    private fun addPending(trigger: Trigger) {
        pending.add(trigger)
        if (trigger.timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY) {
            val ordered = pending.sortedBy { it.chapterIndex }
            pending.clear()
            pending.addAll(ordered)
        }
    }

    private fun shouldYield(trigger: Trigger): Boolean = synchronized(lock) {
        trigger.timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY &&
            lastEntered?.let { it != trigger && it in pending } == true
    }

    /**
     * 每本书每天最多提示一次；提示本身不读正文、不调模型，也不会因为额度用完而反复打扰。
     */
    private suspend fun announceDailyBudgetExhausted(
        trigger: Trigger,
        personaId: Long,
        noticeEpoch: Long,
        version: Pair<Long, Long>
    ) {
        val today = java.time.LocalDate.now().toEpochDay()
        val autonomy = settings.companionAutonomySettings.first()
        if (!autonomy.noticeActive) return
        if (personaId !in autonomy.annotationPersonasFor(settings.activePersonaId.first())) return
        val persona = personas.getPersona(personaId) ?: return
        synchronized(lock) {
            if (version.second != readerVersion || noticeEpoch != noticeVersion || readerBook != trigger.bookId) return
            // Lookahead may spend the budget while the current chapter still has notes.
            // Keep the once-a-day notice for the chapter the reader actually enters.
            if (trigger.timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY && trigger != lastEntered) return
            if (exhaustedNoticeDay != today) {
                exhaustedNoticeDay = today
                exhaustedNoticeBooks.clear()
            }
            if (!exhaustedNoticeBooks.add(trigger.bookId)) return
            mutableResults.tryEmit(
                ProactiveAnnotationBatchResult(
                    bookId = trigger.bookId, chapterIndex = trigger.chapterIndex,
                    personaName = persona.name, avatarPath = persona.avatarPath,
                    createdCount = 0, annotationIds = emptyList(), personaId = personaId,
                    personaPersonality = persona.personality, personaSpeakingStyle = persona.speakingStyle,
                    dailyBudgetExhausted = true
                )
            )
        }
    }

    private suspend fun run(trigger: Trigger) {
        val policy = settings.companionAutonomySettings.first().policyFor(trigger.bookId, settings.activePersonaId.first())
        for ((index, personaId) in policy.personaIds.withIndex()) {
            if (synchronized(lock) { observedPolicy != policy || !wanted(trigger) }) break
            runForPersona(trigger, personaId, policy.personaIds.size - index)
        }
    }

    private suspend fun runForPersona(trigger: Trigger, personaId: Long, remainingPersonas: Int) {
        val version = synchronized(lock) {
            if (!wanted(trigger)) return
            policyVersion to readerVersion
        }
        val noticeEpoch = synchronized(lock) { noticeVersion }
        fun readerContextValid(): Boolean = synchronized(lock) {
            version == (policyVersion to readerVersion) && wanted(trigger)
        }
        val initial = settings.companionAutonomySettings.first().policyFor(trigger.bookId, settings.activePersonaId.first())
        if (!initial.enabled || initial.limits.timing != trigger.timing) return
        val dao = database.proactiveAnnotationJobDao()
        fun startOfDay(): Long = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Exhausted global budgets must not repeatedly load/hash a chapter or touch its PAUSED ledger.
        val budget = quota.reserve(initial.limits.copy(maxPerChapter = ProactiveAnnotationLimits.UNLIMITED), requestVoice = false, requestImages = false)
        if (!budget.accepted || budget.maxAnnotations <= 0) {
            announceDailyBudgetExhausted(trigger, personaId, noticeEpoch, version)
            return
        }
        val dailyRemaining = if (initial.limits.dailyUnlimited) Int.MAX_VALUE else (initial.limits.dailyMax - dao.createdSince(startOfDay())).coerceAtLeast(0)
        if (dailyRemaining <= 0) {
            announceDailyBudgetExhausted(trigger, personaId, noticeEpoch, version)
            return
        }
        val share = annotationBudgetShare(minOf(dailyRemaining, budget.maxAnnotations), remainingPersonas)
        if (!readerContextValid()) return
        if (personaId !in initial.personaIds) return
        val persona = personas.getPersona(personaId) ?: return
        val chapter = library.getChapter(trigger.bookId, trigger.chapterIndex) ?: return
        val body = library.readChapterText(trigger.bookId, chapter)
        if (body.isBlank() || library.getBook(trigger.bookId) == null) return
        val revision = ProactiveAnnotationParagraphs.revision(body)
        if (!readerContextValid()) return
        val now = System.currentTimeMillis()
        val old = dao.find(trigger.bookId, trigger.chapterIndex, personaId, revision)
        if (old != null && !old.canAttempt()) return
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
        var yielded = false
        suspend fun allowance(preferCurrent: Boolean = true): com.mozhi.reader.core.datastore.ProactiveAnnotationAllowance? {
            if (!valid()) return null
            if (preferCurrent && shouldYield(trigger)) {
                yielded = true
                return null
            }
            val limits = initial.limits
            val chapterCap = if (limits.chapterUnlimited) Int.MAX_VALUE else limits.maxPerChapter
            if (done.size >= chapterCap) return null
            if (ids.size >= share) return null
            if (!limits.dailyUnlimited && dao.createdSince(startOfDay()) >= limits.dailyMax) return null
            return quota.reserve(limits, initial.voice && persona.voiceId.isNotBlank(), initial.images)
                .takeIf { it.accepted && it.maxAnnotations > 0 && readerContextValid() }
        }
        var outcome = ProactiveAnnotationGenerationResult(failed = false, stopped = true)
        try {
            val limits = initial.limits
            outcome = service.generateForChapter(
                request = ProactiveAnnotationRequest(trigger.bookId, trigger.chapterIndex, body, persona,
                    if (limits.chapterUnlimited) Int.MAX_VALUE else limits.maxPerChapter, done.toSet(), initial.prompts,
                    contextBudgetChars = limits.context.budgetChars),
                allowance = { allowance() },
                recordMedia = { voices, images ->
                    check(valid()) { "generation_context_changed_before_media_charge" }
                    quota.recordCreated(0, voices, images)
                },
                commit = { row, end, illustration ->
                    // Finish a paid paragraph before yielding to a newly entered chapter.
                    if (end in done || allowance(preferCurrent = false) == null) false
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
            // Scope shutdown still propagates. A request-local timeout must finish the ledger
            // and schedule its bounded retry without requiring another chapter-entry event.
            currentCoroutineContext().ensureActive()
            outcome = ProactiveAnnotationGenerationResult(failed = true, stopped = false)
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
        if (job.status == "FAILED" && job.canAttempt() && readerContextValid()) retry(trigger, version)
        if (job.status == "PAUSED" && yielded && readerContextValid()) {
            synchronized(lock) { if (readerContextValid() && pending.size < 6) addPending(trigger) }
            wake.trySend(Unit)
        }
        // Quota/timing/media/notice-copy edits don't invalidate a count notice for already committed rows.
        // Disabled master, persona change or any reader lifecycle transition still suppress publication.
        val autonomy = settings.companionAutonomySettings.first()
        if (ids.isNotEmpty() && autonomy.noticeActive && personaId in autonomy.annotationPersonasFor(settings.activePersonaId.first())) {
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
