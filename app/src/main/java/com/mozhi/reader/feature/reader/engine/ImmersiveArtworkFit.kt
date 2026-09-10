package com.mozhi.reader.feature.reader.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 沉浸页（封面、卷首整页插画）上一张图的落位，坐标相对可用区左上角。 */
data class ImmersiveArtworkRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

/**
 * 封面与整页插画的沉浸式落位。
 *
 * 出版商标记为 cover / duokan-page-fullscreen 的页面只有一张图，读者期待的是「整屏就是这张图」：
 * 两轴居中、尽量占满，而不是像正文插图那样贴着内容框左上角按宽度铺开、下面留一大块空白。
 * 图片长宽比与屏幕接近时铺满裁边（去掉细窄的黑边），差得远时完整容纳——封面上的书名、作者
 * 不能被裁掉。这里是纯几何，排版（滚动条带）与绘制（翻页位图）共用，保证两种阅读模式一致。
 */
object ImmersiveArtworkFit {
    /** 图片与可用区长宽比相差不超过此比例才铺满裁边；再大就会切掉封面上的文字。 */
    const val FILL_ASPECT_TOLERANCE = 0.12f

    /** A small CSS-sized illustration is not a full-page cover merely because it is alone. */
    fun fillsContentAxis(artwork: InlineImagePlacement, width: Float, height: Float): Boolean =
        artwork.width >= width * 0.9f || artwork.height >= height * 0.9f

    fun fit(
        imageWidth: Float,
        imageHeight: Float,
        availableWidth: Float,
        availableHeight: Float,
        allowFill: Boolean = true
    ): ImmersiveArtworkRect {
        val w = imageWidth.coerceAtLeast(1f)
        val h = imageHeight.coerceAtLeast(1f)
        val aw = availableWidth.coerceAtLeast(1f)
        val ah = availableHeight.coerceAtLeast(1f)
        val imageAspect = w / h
        val viewAspect = aw / ah
        val fill = allowFill && abs(imageAspect - viewAspect) / viewAspect <= FILL_ASPECT_TOLERANCE
        val scale = if (fill) max(aw / w, ah / h) else min(aw / w, ah / h)
        val width = w * scale
        val height = h * scale
        return ImmersiveArtworkRect(
            left = (aw - width) / 2f,
            top = (ah - height) / 2f,
            width = width,
            height = height
        )
    }

    /**
     * 整页只有一张图、没有可读正文时返回这张图；否则为 null。兼容旧引擎的 [TextLine.inlineImage]
     * 与新盒模型引擎的定位图片列表——后者此前没有走沉浸分支，封面因此按正文插图贴左上角画出。
     */
    fun singleArtwork(lines: List<TextLine>): InlineImagePlacement? {
        val line = lines.singleOrNull() ?: return null
        if (line.text.replace(IMAGE_TOKEN, "").isNotBlank()) return null
        line.inlineImage?.let { return it }
        val positioned = (line.inlineImages + line.inlineGlyphImages).singleOrNull() ?: return null
        return InlineImagePlacement(
            imagePath = positioned.imagePath,
            width = positioned.width,
            height = positioned.height,
            altText = positioned.altText
        )
    }

    private const val IMAGE_TOKEN = "［图片］"
}
