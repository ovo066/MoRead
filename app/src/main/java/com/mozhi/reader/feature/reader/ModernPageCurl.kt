package com.mozhi.reader.feature.reader

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import com.mozhi.reader.feature.reader.render.SpreadGeometry
import kotlin.math.PI
import kotlin.math.abs

/** Per-pixel cylinder inversion on HWUI's GPU; textures stay resident throughout a gesture. */
@RequiresApi(33)
internal class ModernPageCurl {
    private var shader: RuntimeShader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var frontTexture: PageTexture? = null
    private var underTexture: PageTexture? = null
    private class PageTexture(val bitmap: Bitmap, val width: Float, val height: Float) {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(width / bitmap.width, height / bitmap.height) })
        }
    }
    fun clear() { paint.shader = null; shader = null; frontTexture = null; underTexture = null }

    /**
     * One page: [front] is the turning sheet (the current page going forward, the previous page
     * coming back) and folds its grab corner onto the virtual fingertip ([curlX], [curlY]).
     */
    fun drawSingle(canvas: Canvas, front: Bitmap, under: Bitmap, curlX: Float, curlY: Float, downY: Float,
        width: Float, height: Float, background: Int, backTextOpacity: Float = .18f, radiusScale: Float = 1f) {
        if (front.isRecycled || under.isRecycled || width <= 0 || height <= 0) return
        val fold = modernCurlFold(curlX, curlY, downY, width, height, radiusScale)
        val saved = canvas.save()
        try {
            canvas.clipRect(0f, 0f, width, height)
            canvas.drawColor(background or 0xff000000.toInt())
            destination.set(0f, 0f, width, height)
            if (fold.progress <= 0f || fold.progress >= 1f) {
                paint.shader = null
                canvas.drawBitmap(if (fold.progress <= 0f) front else under, null, destination, paint)
                return
            }
            val effect = prepare(front, under, width, height, background, backTextOpacity)
            effect.setInputShader("image", frontTexture!!.shader)
            effect.setInputShader("backImage", frontTexture!!.shader)
            effect.setInputShader("underImage", underTexture!!.shader)
            effect.setFloatUniform("curlNormal", fold.normalX, fold.normalY)
            effect.setFloatUniform("curlFold", fold.fold)
            effect.setFloatUniform("curlRadius", fold.radius)
            effect.setFloatUniform("curlShadow", fold.shadow)
            effect.setFloatUniform("curlShadowSpread", fold.shadowSpread)
            effect.setFloatUniform("doublePageMode", 0f)
            paint.shader = effect
            canvas.drawRect(destination, paint)
        } finally { canvas.restoreToCount(saved) }
    }

    /** Two-page spread: one leaf hinges on the gutter, driven by the relative drag [offset]. */
    fun drawSpread(canvas: Canvas, direction: PageTurnDirection, front: Bitmap, under: Bitmap,
        offset: Float, touchY: Float, width: Float, height: Float, background: Int, spread: SpreadGeometry,
        backTextOpacity: Float = .18f, radiusScale: Float = 1f,
        startX: Float = width, startY: Float = height / 2f) {
        if (front.isRecycled || under.isRecycled || width <= 0 || height <= 0) return
        val next = direction == PageTurnDirection.NEXT
        val travel = spread.leafWidth
        if (travel <= 0f) return
        val progress = (if (next) -offset / travel else offset / travel).coerceIn(0f, 1f)
        val current = if (next) front else under
        val target = if (next) under else front
        val saved = canvas.save()
        try {
            canvas.clipRect(0f, 0f, width, height)
            canvas.drawColor(background or 0xff000000.toInt())
            destination.set(0f, 0f, width, height)
            if (progress <= 0f || progress >= 1f) {
                paint.shader = null
                canvas.drawBitmap(if (progress <= 0f) current else target, null, destination, paint)
                return
            }
            val effect = prepare(front, under, width, height, background, backTextOpacity)
            val currentShader = if (next) frontTexture!!.shader else underTexture!!.shader
            val targetShader = if (next) underTexture!!.shader else frontTexture!!.shader
            effect.setInputShader("image", currentShader)
            effect.setInputShader("backImage", targetShader)
            effect.setInputShader("underImage", targetShader)
            val radius = .04f * radiusScale.coerceIn(.6f, 1.8f)
            val leafWidth = spread.leafWidth
            effect.setFloatUniform("doublePageCurlRadius", radius)
            effect.setFloatUniform("doublePageFoldPosition", leafWidth / height * (1f - progress) - progress * PI.toFloat() * radius / 2f)
            effect.setFloatUniform("doublePageProgress", progress)
            effect.setFloatUniform("doublePageDirection", if (next) 1f else -1f)
            effect.setFloatUniform("doublePageGrabMaterialX", ((abs(startX - width / 2f) - spread.gutterPx / 2f) / leafWidth).coerceIn(.05f, 1f))
            effect.setFloatUniform("doublePageGrabV", startY / height)
            // The leaf returns to a level spine at the end, avoiding a last-frame corner snap.
            val finish = ((progress - .75f) / .25f).coerceIn(0f, 1f)
            val tilt = 1f - finish * finish * (3f - 2f * finish)
            effect.setFloatUniform("doublePageCurrentV", (startY + (touchY - startY) * tilt).coerceIn(0f, height) / height)
            effect.setFloatUniform("doublePageMode", 1f)
            effect.setFloatUniform("leafGeometry", spread.leafWidth, spread.gutterPx)
            paint.shader = effect
            canvas.drawRect(destination, paint)
        } finally { canvas.restoreToCount(saved) }
    }

    private fun prepare(front: Bitmap, under: Bitmap, width: Float, height: Float,
        background: Int, backTextOpacity: Float): RuntimeShader {
        if (frontTexture?.let { it.bitmap === front && it.width == width && it.height == height } != true)
            frontTexture = PageTexture(front, width, height)
        if (underTexture?.let { it.bitmap === under && it.width == width && it.height == height } != true)
            underTexture = PageTexture(under, width, height)
        val effect = shader ?: RuntimeShader(MODERN_PAGE_CURL_SHADER).also { shader = it }
        effect.setFloatUniform("resolution", width, height)
        effect.setFloatUniform("backfaceOverlay", Color.red(background) / 255f,
            Color.green(background) / 255f, Color.blue(background) / 255f, 1f - backTextOpacity.coerceIn(0f, 1f))
        return effect
    }
}
