package com.mozhi.reader.feature.reader.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.stage.StageImage

/**
 * 名牌头像：原文称呼 → 定妆图文件。关掉「人物小像」或这本书没有定妆图时是空表。
 * 只有名牌读它，用 CompositionLocal 传，免得给每一种镜头的参数表都加一项。
 */
internal val LocalStagePortraits = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** 小像的直径：比印章大一圈，仍是名牌的一部分，不和对白抢视线。 */
internal val StagePortraitSize = 34.dp

/**
 * 说话人的小像：一枚收在墨圈里的圆像，角上压着原来的那方印。
 *
 * 定妆图的画风各不相同，直接摆上来会和水墨舞台打架，所以先把颜色收掉大半，像一张淡彩小品。
 * 尺寸在图片解码之前就定好，图只是慢慢显出来——名牌不会因为图到了而把对白往下顶。
 */
@Composable
internal fun StagePortraitMedallion(
    path: String,
    ring: Color,
    paper: Color,
    key: Any,
    animate: Boolean,
    size: Dp = StagePortraitSize,
    seal: @Composable () -> Unit
) {
    val bitmap = rememberStageImage(StageImage(path), maxEdge = 192)
    val shown = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key, bitmap != null) {
        if (bitmap != null && shown.value < 1f) shown.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }
    Box(Modifier.size(size + 5.dp)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(ring.copy(alpha = 0.10f))
                .border(1.2.dp, ring.copy(alpha = 0.78f), CircleShape).padding(2.dp).clip(CircleShape)
        ) {
            if (bitmap != null) Image(
                bitmap, contentDescription = null, contentScale = ContentScale.Crop,
                // 定妆图多是半身或全身，脸在上半截。
                alignment = Alignment.TopCenter,
                colorFilter = PortraitWash,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }
            )
            // 图盖在纸上：再罩一层很淡的纸色，压住过亮的底色。
            Box(Modifier.fillMaxSize().background(paper.copy(alpha = 0.10f)))
        }
        Box(Modifier.align(Alignment.BottomEnd).offset(x = 1.dp, y = 1.dp)) { seal() }
    }
}

/** 收掉六成颜色：淡彩，而不是照片。 */
private val PortraitWash = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.42f) })
