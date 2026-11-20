package com.mozhi.reader.core.importer.convert.mobi

/** 用平衡的字节片段树插回正文，避免大量 fragment 让整章反复复制，也允许插入位置回退。 */
internal class MobiBytePieces(private val source: ByteArray, start: Int, length: Int) {
    private sealed class Piece(val size: Int, val height: Int)
    private class Leaf(val start: Int, size: Int) : Piece(size, 1)
    private class Branch(val left: Piece, val right: Piece) : Piece(left.size + right.size, maxOf(left.height, right.height) + 1)
    private var root: Piece? = leaf(start, length)
    val size: Int get() = root?.size ?: 0

    private fun leaf(start: Int, length: Int): Piece? {
        range(start.toLong(), length.toLong(), source.size, "KF8 字节片段")
        return if (length == 0) null else Leaf(start, length)
    }

    fun insert(position: Int, start: Int, length: Int) {
        demand(position in 0..size && length >= 0 && length <= MobiBinary.MAX_TEXT_BYTES - size, "KF8 插入位置或长度越界")
        val new = leaf(start, length) ?: return
        val (before, after) = split(root, position)
        root = join(join(before, new), after)
    }

    fun bytes(): ByteArray {
        val result = ByteArray(size)
        var cursor = 0
        fun copy(piece: Piece?) {
            when (piece) {
                null -> Unit
                is Leaf -> { source.copyInto(result, cursor, piece.start, piece.start + piece.size); cursor += piece.size }
                is Branch -> { copy(piece.left); copy(piece.right) }
            }
        }
        copy(root)
        return result
    }

    private fun split(piece: Piece?, at: Int): Pair<Piece?, Piece?> {
        if (piece == null) return null to null
        if (at == 0) return null to piece
        if (at == piece.size) return piece to null
        return when (piece) {
            is Leaf -> Leaf(piece.start, at) to Leaf(piece.start + at, piece.size - at)
            is Branch -> if (at < piece.left.size) {
                val (before, after) = split(piece.left, at)
                before to join(after, piece.right)
            } else {
                val (before, after) = split(piece.right, at - piece.left.size)
                join(piece.left, before) to after
            }
        }
    }

    private fun join(left: Piece?, right: Piece?): Piece? {
        if (left == null) return right
        if (right == null) return left
        return when {
            left.height > right.height + 1 -> {
                val branch = left as Branch
                balance(branch.left, requireNotNull(join(branch.right, right)))
            }
            right.height > left.height + 1 -> {
                val branch = right as Branch
                balance(requireNotNull(join(left, branch.left)), branch.right)
            }
            else -> Branch(left, right)
        }
    }

    private fun balance(left: Piece, right: Piece): Piece {
        if (left.height > right.height + 1) {
            val branch = left as Branch
            if (branch.left.height >= branch.right.height) return Branch(branch.left, Branch(branch.right, right))
            val middle = branch.right as Branch
            return Branch(Branch(branch.left, middle.left), Branch(middle.right, right))
        }
        if (right.height > left.height + 1) {
            val branch = right as Branch
            if (branch.right.height >= branch.left.height) return Branch(Branch(left, branch.left), branch.right)
            val middle = branch.left as Branch
            return Branch(Branch(left, middle.left), Branch(middle.right, branch.right))
        }
        return Branch(left, right)
    }
}
