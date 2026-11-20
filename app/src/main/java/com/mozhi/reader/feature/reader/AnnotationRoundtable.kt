package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.PersonaEntity

/** 「大家聊聊」一轮最多几个角色开口：再多就成了刷屏，读者反而插不上话。 */
internal const val ROUNDTABLE_SEATS = 3

/** 读者不说话，角色们最多连着聊几轮。读者每说一句，这个数重新算。 */
internal const val ROUNDTABLE_MAX_ROUNDS = 2

/**
 * 这一轮谁开口、按什么顺序：已经在这条讨论里说过话的角色优先入座，其次是读者此刻点名的角色，
 * 再按列表顺序补到 [ROUNDTABLE_SEATS] 个。刚说完的那位排到最后——同一个人不接自己的话。
 */
internal fun roundtableSpeakers(
    annotations: List<AnnotationEntity>,
    replies: List<AnnotationReplyEntity>,
    personas: List<PersonaEntity>,
    preferred: Long?
): List<Long> {
    val known = personas.mapTo(LinkedHashSet()) { it.id }
    val spoken = (annotations.filter { it.note.isNotBlank() }.mapNotNull { it.personaId } + replies.mapNotNull { it.personaId })
        .filter { it in known }
    val seats = (spoken.distinct() + listOfNotNull(preferred?.takeIf { it in known }) + known).distinct().take(ROUNDTABLE_SEATS)
    val last = spoken.lastOrNull()?.takeIf { it in seats && seats.size > 1 } ?: return seats
    return seats - last + last
}

/** 读者上一次发言之后，角色们已经连着说了几条（楼主层不算）。 */
internal fun personaRunLength(replies: List<AnnotationReplyEntity>): Int =
    replies.takeLastWhile { it.personaId != null }.size

/** 还能不能再来一轮：至少两个人才叫「聊」，且角色们没有自顾自聊满 [ROUNDTABLE_MAX_ROUNDS] 轮。 */
internal fun roundtableAllowed(speakers: Int, run: Int): Boolean =
    speakers >= 2 && run < speakers * ROUNDTABLE_MAX_ROUNDS
