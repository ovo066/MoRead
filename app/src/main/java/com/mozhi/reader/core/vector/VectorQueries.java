package com.mozhi.reader.core.vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.function.Predicate;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.query.ObjectWithScore;
import io.objectbox.query.Query;
import io.objectbox.query.QueryCondition;

/**
 * 向量检索门面（生成的 *_ 条件类只在这里出现，理由见 {@link VectorDb}）。
 * 返回的 score 是余弦距离，越小越相近，升序排列。
 *
 * ObjectBox 的近邻查询是「先找 N 个近邻、再套其他条件」：过滤条件（防剧透章节上限、
 * bookId、personaId）可能把候选集全部滤掉。这里自适应扩大候选数直到凑够 topK 或
 * 达到预算（上限 {@link #MAX_FETCH}）。小范围直接做精确余弦排序，避免章节筛选被全库近邻挤空；
 * 大范围仍是有界近似召回，不承诺穷举。
 */
public final class VectorQueries {

    private static final int MAX_FETCH = 4096;
    /** At 1024 dimensions this bounds copied vector payloads to about 2 MiB per narrow search. */
    private static final int MAX_EXACT_SCOPE_CHUNKS = 512;

    private VectorQueries() {
    }

    /**
     * 书内切片检索。maxChapterIndex（含）是防剧透硬上限，过滤在查询层完成，
     * 调用方传入用户当前进度章节即可。返回最多 topK 条。
     */
    public static List<ObjectWithScore<BookChunk>> searchChunks(
            BoxStore store,
            long bookId,
            float[] queryVector,
            int topK,
            int maxChapterIndex
    ) {
        return searchChunks(store, bookId, queryVector, topK, 0, maxChapterIndex);
    }

    public static List<ObjectWithScore<BookChunk>> searchChunks(
            BoxStore store, long bookId, float[] queryVector, int topK,
            int minChapterIndex, int maxChapterIndex
    ) {
        if (minChapterIndex > maxChapterIndex || topK <= 0) return java.util.Collections.emptyList();
        Box<BookChunk> box = store.boxFor(BookChunk.class);
        QueryCondition<BookChunk> scope = BookChunk_.bookId.equal(bookId)
                .and(BookChunk_.chapterIndex.between(Math.max(0, minChapterIndex), maxChapterIndex));
        Query<BookChunk> scoped = box.query(scope).build();
        try {
            if (scoped.count() <= MAX_EXACT_SCOPE_CHUNKS) {
                return rankExactChunks(scoped.find(), queryVector, topK);
            }
        } finally {
            scoped.close();
        }
        return adaptiveSearch(box.count(), topK, fetchCount -> box
                .query(
                        BookChunk_.embedding.nearestNeighbors(queryVector, fetchCount)
                                .and(scope)
                )
                .build());
    }

    private static List<ObjectWithScore<BookChunk>> rankExactChunks(List<BookChunk> chunks, float[] query, int topK) {
        if (query == null || query.length != VectorDb.EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("Invalid query embedding dimensions");
        }
        double queryNorm = 0;
        for (float value : query) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite query embedding");
            queryNorm += (double) value * value;
        }
        if (queryNorm == 0) throw new IllegalArgumentException("Zero query embedding");
        List<ObjectWithScore<BookChunk>> result = new ArrayList<>();
        for (BookChunk chunk : chunks) {
            float[] vector = chunk.embedding;
            if (vector == null || vector.length != query.length) continue;
            double dot = 0, norm = 0;
            for (int i = 0; i < query.length; i++) {
                dot += (double) query[i] * vector[i];
                norm += (double) vector[i] * vector[i];
            }
            if (norm <= 0 || !Double.isFinite(norm) || !Double.isFinite(dot)) continue;
            double distance = Math.max(0.0, Math.min(2.0, 1.0 - dot / Math.sqrt(queryNorm * norm)));
            result.add(new ObjectWithScore<>(chunk, distance));
        }
        result.sort(Comparator.comparingDouble((ObjectWithScore<BookChunk> hit) -> hit.getScore())
                .thenComparingInt(hit -> hit.get().chapterIndex)
                .thenComparingInt(hit -> hit.get().chunkIndex)
                .thenComparingLong(hit -> hit.get().id));
        return result.size() > topK ? new ArrayList<>(result.subList(0, topK)) : result;
    }

    /** 角色记忆检索，按 personaId 隔离。返回最多 topK 条。 */
    public static List<ObjectWithScore<MemoryEntry>> searchMemories(
            BoxStore store,
            long personaId,
            float[] queryVector,
            int topK
    ) {
        return searchMemories(
                store, personaId, queryVector, topK, null, 0L,
                null, Integer.MAX_VALUE, Integer.MAX_VALUE
        );
    }

    /**
     * 带范围过滤的角色记忆检索。
     *
     * @param bookId 非 null 时只召回这本书产生的记忆（「跨书记忆」开关关闭时用）。
     *   注意跨书的全局记忆 bookId 为 null，收窄时它们同样不参与——这正是关闭该开关的语义。
     * @param maskId 当前生效的用户面具（0 = 未启用面具）。本人层记忆（maskId=0）永远可见；
     *   面具内的经历只在同一面具下可见，不同面具之间互相穿帮是绝对不能出现的。
     */
    public static List<ObjectWithScore<MemoryEntry>> searchMemories(
            BoxStore store,
            long personaId,
            float[] queryVector,
            int topK,
            Long bookId,
            long maskId
    ) {
        return searchMemories(
                store, personaId, queryVector, topK, bookId, maskId,
                bookId, Integer.MAX_VALUE, Integer.MAX_VALUE
        );
    }

    /**
     * Memory search with a provenance boundary for the currently open book. When protection is
     * active, legacy book memories without provenance fail closed; global and other-book memories
     * remain governed by the existing book/mask switches.
     */
    public static List<ObjectWithScore<MemoryEntry>> searchMemories(
            BoxStore store,
            long personaId,
            float[] queryVector,
            int topK,
            Long bookId,
            long maskId,
            Long scopeBookId,
            int maxChapterIndex,
            int maxCharOffset
    ) {
        Box<MemoryEntry> box = store.boxFor(MemoryEntry.class);
        Predicate<MemoryEntry> scopeFilter = entry -> {
            if (scopeBookId == null || maxChapterIndex == Integer.MAX_VALUE) return true;
            if (entry.bookId == null || entry.bookId.longValue() != scopeBookId.longValue()) return true;
            if (entry.sourceChapterIndex < 0) return false;
            return entry.sourceChapterIndex < maxChapterIndex ||
                    (entry.sourceChapterIndex == maxChapterIndex &&
                            entry.sourceCharOffset >= 0 && entry.sourceCharOffset <= maxCharOffset);
        };
        return adaptiveSearchFiltered(box.count(), topK, fetchCount -> {
            QueryCondition<MemoryEntry> condition =
                    MemoryEntry_.embedding.nearestNeighbors(queryVector, fetchCount)
                            .and(MemoryEntry_.personaId.equal(personaId));
            if (bookId != null) condition = condition.and(MemoryEntry_.bookId.equal(bookId));
            condition = condition.and(
                    maskId == 0L
                            ? MemoryEntry_.maskId.equal(0L)
                            : MemoryEntry_.maskId.equal(0L).or(MemoryEntry_.maskId.equal(maskId))
            );
            return box.query(condition).build();
        }, scopeFilter);
    }

    /** 记忆管理页用：按时间倒序分页列出某角色的全部记忆。 */
    public static List<MemoryEntry> listMemories(
            BoxStore store,
            long personaId,
            int offset,
            int limit
    ) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(MemoryEntry_.personaId.equal(personaId))
                .orderDesc(MemoryEntry_.createdAt)
                .build();
        try {
            return query.find(offset, limit);
        } finally {
            query.close();
        }
    }

    /** 删除单条记忆（记忆管理页的左滑删除）。返回 false 表示目标本来就不存在。 */
    public static boolean removeMemory(BoxStore store, long id) {
        return store.boxFor(MemoryEntry.class).remove(id);
    }

    /** 清空某角色的全部记忆；角色卡本身与它写下的批注笔记不受影响。 */
    public static void removeMemoriesForPersona(BoxStore store, long personaId) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(MemoryEntry_.personaId.equal(personaId))
                .build();
        try {
            query.remove();
        } finally {
            query.close();
        }
    }

    /** 已有切片的章节集合。写入按章原子（embedding 管线保证），出现即完整。 */
    public static int[] chaptersWithChunks(BoxStore store, long bookId) {
        Query<BookChunk> query = store.boxFor(BookChunk.class)
                .query(BookChunk_.bookId.equal(bookId))
                .build();
        try {
            return query.property(BookChunk_.chapterIndex).distinct().findInts();
        } finally {
            query.close();
        }
    }


    /** All persisted chunks in a chapter range, used by BM25 recall and neighbour expansion. */
    public static List<BookChunk> listChunks(BoxStore store, long bookId, int maxChapterIndex) {
        Query<BookChunk> query = store.boxFor(BookChunk.class)
                .query(
                        BookChunk_.bookId.equal(bookId)
                                .and(BookChunk_.chapterIndex.lessOrEqual(maxChapterIndex))
                )
                .build();
        try {
            List<BookChunk> chunks = query.find();
            chunks.sort(Comparator.comparingInt((BookChunk item) -> item.chapterIndex)
                    .thenComparingInt(item -> item.chunkIndex));
            return chunks;
        } finally {
            query.close();
        }
    }

    /** 角色的长期记忆条数（伴读页与角色编辑页的指标胶囊）。 */
    public static long countMemories(BoxStore store, long personaId) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(MemoryEntry_.personaId.equal(personaId))
                .build();
        try {
            return query.count();
        } finally {
            query.close();
        }
    }

    /** 同一会话水位的记忆是否已写入；Room 水位更新前崩溃时靠它避免重复固化。 */
    public static boolean hasMemoryBatch(
            BoxStore store,
            long conversationId,
            long sourceMessageId
    ) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(
                        MemoryEntry_.conversationId.equal(conversationId)
                                .and(MemoryEntry_.sourceMessageId.equal(sourceMessageId))
                )
                .build();
        try {
            return query.count() > 0;
        } finally {
            query.close();
        }
    }

    /** 会话历史被编辑/删除时清掉由它固化出的旧记忆，随后可按新历史重新固化。 */
    public static void removeMemoriesForConversation(BoxStore store, long conversationId) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(MemoryEntry_.conversationId.equal(conversationId))
                .build();
        try {
            query.remove();
        } finally {
            query.close();
        }
    }

    /** Permanent book deletion must also remove book-scoped memories without a conversation. */
    public static void removeMemoriesForBook(BoxStore store, long bookId) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(MemoryEntry_.bookId.equal(bookId)).build();
        try {
            query.remove();
        } finally {
            query.close();
        }
    }

    /** 删书时清掉该书全部切片。 */
    public static void removeChunksForBook(BoxStore store, long bookId) {
        Query<BookChunk> query = store.boxFor(BookChunk.class)
                .query(BookChunk_.bookId.equal(bookId))
                .build();
        try {
            query.remove();
        } finally {
            query.close();
        }
    }

    /** 更换 embedding 模型后清空全部切片（不同模型坐标系不可混用）；重建按需触发。 */
    public static void removeAllChunks(BoxStore store) {
        store.boxFor(BookChunk.class).removeAll();
    }

    private interface VectorQueryFactory<T> {
        Query<T> build(int fetchCount);
    }


    private static <T> List<ObjectWithScore<T>> adaptiveSearchFiltered(
            long totalCandidates,
            int topK,
            VectorQueryFactory<T> factory,
            Predicate<T> filter
    ) {
        int fetch = Math.max(topK * 4, 32);
        while (true) {
            Query<T> query = factory.build(fetch);
            List<ObjectWithScore<T>> raw;
            try {
                raw = query.findWithScores();
            } finally {
                query.close();
            }
            List<ObjectWithScore<T>> hits = new ArrayList<>();
            for (ObjectWithScore<T> hit : raw) {
                if (filter.test(hit.get())) hits.add(hit);
                if (hits.size() == topK) break;
            }
            boolean exhausted = fetch >= totalCandidates || fetch >= MAX_FETCH;
            if (hits.size() >= topK || exhausted) return hits;
            fetch = (int) Math.min((long) fetch * 4, MAX_FETCH);
        }
    }

    private static <T> List<ObjectWithScore<T>> adaptiveSearch(
            long totalCandidates,
            int topK,
            VectorQueryFactory<T> factory
    ) {
        int fetch = Math.max(topK * 4, 32);
        while (true) {
            Query<T> query = factory.build(fetch);
            List<ObjectWithScore<T>> hits;
            try {
                hits = query.findWithScores();
            } finally {
                query.close();
            }
            boolean exhausted = fetch >= totalCandidates || fetch >= MAX_FETCH;
            if (hits.size() >= topK || exhausted) {
                return hits.size() > topK
                        ? new ArrayList<>(hits.subList(0, topK))
                        : hits;
            }
            fetch = (int) Math.min((long) fetch * 4, MAX_FETCH);
        }
    }
}
