package com.mozhi.reader.ai.agent

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 人物关系图的确定性力导向布局（Fruchterman–Reingold）。
 *
 * 不用随机数：初始位置按度数排序后均匀摆在圆上，固定迭代次数，同一份数据永远得到同一张图，
 * 历史消息重开、全屏放大与导出看到的都是同一个布局。返回值归一化到 [0, 1]。
 */
object RelationLayout {
    private const val ITERATIONS = 240

    fun layout(nodes: List<String>, edges: List<Pair<String, String>>): Map<String, Pair<Float, Float>> {
        if (nodes.isEmpty()) return emptyMap()
        if (nodes.size == 1) return mapOf(nodes.single() to (0.5f to 0.5f))
        val index = nodes.withIndex().associate { it.value to it.index }
        val links = edges.mapNotNull { (a, b) ->
            val i = index[a] ?: return@mapNotNull null
            val j = index[b] ?: return@mapNotNull null
            if (i == j) null else i to j
        }
        val degree = IntArray(nodes.size)
        links.forEach { (i, j) -> degree[i]++; degree[j]++ }
        // 度数高的人物排在前面，均匀分布在圆周上；同度数按原顺序，保证可复现。
        val order = nodes.indices.sortedWith(compareByDescending<Int> { degree[it] }.thenBy { it })
        val x = DoubleArray(nodes.size)
        val y = DoubleArray(nodes.size)
        order.forEachIndexed { slot, node ->
            val angle = 2 * PI * slot / nodes.size
            val radius = if (slot == 0 && degree[node] > 1) 0.0 else 1.0
            x[node] = radius * cos(angle)
            y[node] = radius * sin(angle)
        }
        val area = 4.0
        val k = sqrt(area / nodes.size)
        var temperature = 0.3
        val dx = DoubleArray(nodes.size)
        val dy = DoubleArray(nodes.size)
        repeat(ITERATIONS) {
            dx.fill(0.0)
            dy.fill(0.0)
            for (i in nodes.indices) for (j in i + 1 until nodes.size) {
                var ddx = x[i] - x[j]
                var ddy = y[i] - y[j]
                var distance = sqrt(ddx * ddx + ddy * ddy)
                if (distance < 1e-6) {
                    // 重合时沿固定方向推开，不引入随机性。
                    ddx = 0.01 * (i - j); ddy = 0.01; distance = sqrt(ddx * ddx + ddy * ddy)
                }
                val force = k * k / distance
                dx[i] += ddx / distance * force; dy[i] += ddy / distance * force
                dx[j] -= ddx / distance * force; dy[j] -= ddy / distance * force
            }
            links.forEach { (i, j) ->
                val ddx = x[i] - x[j]
                val ddy = y[i] - y[j]
                val distance = max(sqrt(ddx * ddx + ddy * ddy), 1e-6)
                val force = distance * distance / k
                dx[i] -= ddx / distance * force; dy[i] -= ddy / distance * force
                dx[j] += ddx / distance * force; dy[j] += ddy / distance * force
            }
            for (i in nodes.indices) {
                // 轻微向心力：孤立节点不会飘到画布外。
                dx[i] -= x[i] * 0.05; dy[i] -= y[i] * 0.05
                val length = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 1e-9)
                val step = min(length, temperature)
                x[i] += dx[i] / length * step
                y[i] += dy[i] / length * step
            }
            temperature = max(temperature * 0.97, 0.005)
        }
        val minX = x.min(); val maxX = x.max(); val minY = y.min(); val maxY = y.max()
        val spanX = max(maxX - minX, 1e-6)
        val spanY = max(maxY - minY, 1e-6)
        return nodes.indices.associate { i ->
            nodes[i] to (((x[i] - minX) / spanX).toFloat() to ((y[i] - minY) / spanY).toFloat())
        }
    }
}
