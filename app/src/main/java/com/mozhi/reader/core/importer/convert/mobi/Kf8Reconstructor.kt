package com.mozhi.reader.core.importer.convert.mobi

internal data class MobiPart(val bytes: ByteArray, val title: String?, val inToc: Boolean)
internal data class Kf8Content(val parts: List<MobiPart>, val flows: List<ByteArray>)

internal class Kf8Reconstructor(private val pdb: PalmDatabase, private val header: MobiHeader, private val checkActive: () -> Unit) {
    private data class Skeleton(val fragments: Int, val start: Int, val length: Int)
    private data class Fragment(val insert: Int, val part: Int, val sequence: Int, val start: Int, val length: Int)

    fun reconstruct(text: ByteArray): Kf8Content {
        val flows = readFlows(text)
        val flow = flows.first()
        val index = MobiIndexReader(pdb, header.base, checkActive)
        val skeletonIndex = header.skeletons ?: unsupported("KF8 缺少 skeleton 索引")
        val skeletons = index.read(skeletonIndex).entries.map { row ->
            val bounds = row.values(6, 2)
            Skeleton(row.values(1, 1)[0], bounds[0], bounds[1])
        }
        demand(skeletons.isNotEmpty() && skeletons.size <= 10_000, "KF8 部分数量无效")
        val fragments = header.fragments?.let(index::read)?.entries.orEmpty().map { row ->
            val bounds = row.values(6, 2)
            val insert = row.label.toIntOrNull() ?: corrupt("KF8 fragment 插入位置无效")
            Fragment(insert, row.values(3, 1)[0], row.values(4, 1)[0], bounds[0], bounds[1])
        }
        demand(fragments.all { it.part in skeletons.indices }, "KF8 fragment 所属部分越界")
        val byPart = fragments.groupBy { it.part }
        val titles = mutableMapOf<Int, String>()
        header.ncx?.let { relative ->
            val ncx = index.read(relative)
            ncx.entries.forEach { row ->
                val position = row.values(6, 2)
                demand(position[0] in fragments.indices, "KF8 NCX fid 越界")
                val fragment = fragments[position[0]]
                demand(position[1] <= fragment.length, "KF8 NCX off 越界")
                titles.putIfAbsent(fragment.part, ncx.title(row))
            }
        }
        var total = 0L
        var previousEnd = 0L
        val parts = skeletons.mapIndexed { part, skeleton ->
            checkActive()
            range(skeleton.start.toLong(), skeleton.length.toLong(), flow.size, "KF8 skeleton")
            demand(skeleton.start >= previousEnd, "KF8 skeleton 数据重叠或乱序")
            val owned = byPart[part].orEmpty().sortedBy { it.sequence }
            demand(owned.size == skeleton.fragments && owned.map { it.sequence }.distinct().size == owned.size,
                "KF8 fragment 数量或顺序不一致")
            val pool = skeleton.start.toLong() + skeleton.length
            val next = skeletons.getOrNull(part + 1)?.start?.toLong() ?: flow.size.toLong()
            var consumed = 0L
            val localStarts = owned.all { it.start == 0 }
            val output = MobiBytePieces(flow, skeleton.start, skeleton.length)
            total += skeleton.length
            demand(total <= MobiBinary.MAX_TEXT_BYTES, "KF8 重组正文过大")
            owned.forEach { fragment ->
                checkActive()
                // 有的索引把每个片段自身的起点记作 0；片段数据依次紧随 skeleton。
                // 非零起点按片段区偏移解释，并核对连续性，不能重复读取前一片段而串章。
                demand(localStarts || fragment.start.toLong() == consumed, "KF8 fragment 数据不连续")
                val source = pool + consumed
                range(source, fragment.length.toLong(), flow.size, "KF8 fragment 正文")
                demand(source + fragment.length <= next, "KF8 fragment 跨越下一部分")
                // 坐标属于整个 flow 0；减去本部分起点后，在已重组文档中插入。
                val at = fragment.insert.toLong() - skeleton.start
                demand(at in 0..output.size.toLong(), "KF8 fragment 插入位置越界")
                total += fragment.length
                demand(total <= MobiBinary.MAX_TEXT_BYTES, "KF8 重组正文过大")
                output.insert(at.toInt(), source.toInt(), fragment.length)
                consumed += fragment.length
            }
            previousEnd = pool + consumed
            MobiPart(output.bytes(), titles[part], part in titles)
        }
        return Kf8Content(parts, flows)
    }

    private fun readFlows(text: ByteArray): List<ByteArray> {
        val relative = header.fdst
        if (relative == null || relative == 0) {
            demand(header.flowCount == null || header.flowCount == 1, "KF8 多 flow 缺少 FDST")
            return listOf(text)
        }
        val fdst = pdb.record(pdb.absolute(header.base, relative), 1024 * 1024)
        demand(fdst.magic(0, "FDST"), "KF8 FDST 标记缺失")
        val length = fdst.uint(4, "FDST 头长度")
        val count = fdst.uint(8, "FDST flow 数量")
        demand(length >= 12 && count in 1..4096, "FDST 头无效")
        demand(header.flowCount == null || header.flowCount == count, "FDST flow 数量不一致")
        range(length.toLong(), count.toLong() * 8, fdst.size, "FDST 表")
        var previous = 0
        return (0 until count).map { i ->
            val start = fdst.uint(length + i * 8, "FDST 起点")
            val end = fdst.uint(length + i * 8 + 4, "FDST 终点")
            demand(start == previous && end >= start && end <= text.size, "FDST flow 范围不连续或越界")
            previous = end
            text.copyOfRange(start, end)
        }.also { demand(previous == text.size, "FDST 未覆盖全部正文") }
    }
}
