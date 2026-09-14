package com.mozhi.reader.feature.settings

import com.mozhi.reader.core.library.BookTextCompactionOutcome
import com.mozhi.reader.core.library.BookTextCompactionResult
import com.mozhi.reader.core.storage.StorageCategory
import com.mozhi.reader.core.storage.StorageCategoryUsage
import com.mozhi.reader.core.storage.StorageTextCompactionResult
import org.junit.Assert.*
import org.junit.Test

class StorageDistributionTest {
    @Test fun tinyCategoriesAreGroupedWithoutDroppingBytesOrDuplicatingCategories() {
        val categories = StorageCategory.entries.mapIndexed { index, category -> StorageCategoryUsage(category, index * 100L) }
        val compact = storageChartSlices(categories)
        val full = storageChartSlices(categories, expanded = true)
        assertEquals(6, compact.size)
        assertEquals(categories.sumOf { it.bytes }, compact.sumOf { it.bytes })
        assertEquals(full.sumOf { it.bytes }, compact.sumOf { it.bytes })
        assertEquals(full.flatMap { it.categories }.map { it.category }.toSet(),
            compact.flatMap { it.categories }.map { it.category }.toSet())
        assertEquals(full.first(), compact.first())
        assertTrue(full.zipWithNext().all { (a, b) -> a.bytes >= b.bytes })
    }

    @Test fun pieHitTestingUsesTheSameByteProportionsAsTheBars() {
        val slices = storageChartSlices(listOf(
            StorageCategoryUsage(StorageCategory.TEXT, 5),
            StorageCategoryUsage(StorageCategory.SPEECH, 25),
            StorageCategoryUsage(StorageCategory.VECTOR, 70)
        ))
        assertEquals(StorageCategory.VECTOR.name, storageSliceAt(slices, 0.699)?.key)
        assertEquals(StorageCategory.SPEECH.name, storageSliceAt(slices, 0.7)?.key)
        assertEquals(StorageCategory.TEXT.name, storageSliceAt(slices, 0.999)?.key)
        assertNull(storageSliceAt(emptyList(), 0.0))
        assertNull(storageSliceAt(slices, Double.NaN))
        assertEquals("70.0%", storagePercentage(70, 100))
        assertEquals("<0.1%", storagePercentage(1, 10_000))
    }

    @Test fun zeroByteCompactionExplainsAlreadyCompressedMissingAndFailedCases() {
        fun report(vararg outcomes: BookTextCompactionOutcome) = StorageTextCompactionResult(
            outcomes.map { BookTextCompactionResult(it, 100, 100) })
        assertTrue(textCompactionMessage(report(BookTextCompactionOutcome.ALREADY_COMPRESSED)).contains("已经压缩"))
        assertTrue(textCompactionMessage(report(BookTextCompactionOutcome.MISSING)).contains("没有可压缩"))
        assertTrue(textCompactionMessage(report(BookTextCompactionOutcome.NO_GAIN)).contains("不会更小"))
        assertTrue(textCompactionMessage(StorageTextCompactionResult(emptyList(), failedBooks = 1)).contains("未完成"))
        assertFalse(textCompactionMessage(report(BookTextCompactionOutcome.ALREADY_COMPRESSED)).contains("释放 0"))
    }
}
