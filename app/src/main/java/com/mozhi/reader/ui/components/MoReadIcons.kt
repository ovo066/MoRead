package com.mozhi.reader.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 项目自绘的内联矢量图标。Material Icons 里找不到合适形制时才加到这里——
 * 一律纯 path、24×24 视口、单色（由 [androidx.compose.material3.Icon] 的 tint 上色）。
 */
object MoReadIcons {

    /**
     * 发送用的纸飞机，机头朝右上 45°。
     *
     * Material 的 `Send` 是朝右的实心块，此前靠 `rotate(-90f)` 硬掰成朝上，
     * 掰出来的重心和视觉中心都不在按钮中心。这里直接按 45° 画：
     * 机头 (21.5, 2.5)、两翼末端 (2.5, 10) 与 (14, 21.5) 关于机身轴线对称，
     * 尾部凹口 (13.02, 10.98) 落在轴线上，凹进去的那一口就是纸飞机的折痕。
     */
    val PaperPlane: ImageVector by lazy {
        ImageVector.Builder(
            name = "MoRead.PaperPlane",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(21.5f, 2.5f)
                lineTo(2.5f, 10f)
                lineTo(13.02f, 10.98f)
                lineTo(14f, 21.5f)
                close()
            }
        }.build()
    }

    /** 流式生成中的「停止」：实心圆角方块，比 StopCircle 在实心圆底上更清爽（不叠两层圆）。 */
    val StopSquare: ImageVector by lazy {
        ImageVector.Builder(
            name = "MoRead.StopSquare",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 6f)
                lineTo(16f, 6f)
                curveTo(17.1f, 6f, 18f, 6.9f, 18f, 8f)
                lineTo(18f, 16f)
                curveTo(18f, 17.1f, 17.1f, 18f, 16f, 18f)
                lineTo(8f, 18f)
                curveTo(6.9f, 18f, 6f, 17.1f, 6f, 16f)
                lineTo(6f, 8f)
                curveTo(6f, 6.9f, 6.9f, 6f, 8f, 6f)
                close()
            }
        }.build()
    }
}
