package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.mozhi.reader.core.datastore.PageTurnAnimation
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise real Skia pixels: mocked Canvas calls cannot detect a shadow or a clipping gap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageTurnCompositorTest {
    private val bitmaps = mutableListOf<Bitmap>()
    private val regionCache = mutableMapOf<Pose, Regions>()

    @After
    fun recycleBitmaps() {
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Test
    fun simulationAddsNoShadowsOrTintInAnyThemeOrDirection() {
        for (theme in THEMES) {
            val paper = theme.background or Color.BLACK
            val page = bitmap(paper)
            for (pose in POSES) for (direction in PageTurnDirection.entries) {
                val frame = render(pose, theme, page, page, direction = direction)
                assertUnshadedPaper("$theme $pose $direction", frame, paper, regions(pose))
                frame.recycle()
            }
        }
    }

    @Test
    fun frontAndRevealedPageStayInTheirOwnRegionsWithoutShading() {
        val front = bitmap(Color.GREEN)
        val under = bitmap(Color.BLUE)
        for (pose in POSES) {
            val frame = render(pose, Theme(Color.WHITE), front, under)
            val pixels = pixels(frame)
            val regions = regions(pose)
            for (index in regions.front) {
                assertEquals("$pose shaded or moved the front at $index", Color.GREEN, pixels[index])
            }
            for (index in regions.under) {
                assertEquals("$pose shaded or covered the revealed page at $index", Color.BLUE, pixels[index])
            }
            frame.recycle()
        }
    }

    @Test
    fun backKeepsMirroredFrontPixelsAtHalfOpacityOverOpaquePaper() {
        val front = patternedPage()
        val source = pixels(front)
        val under = bitmap(Color.YELLOW)
        var mirroredSamples = 0
        for (theme in THEMES) {
            // Blend the original texture onto solid paper before reflection as an independent
            // reference: only the ink fades, never the sheet or the page beneath it.
            val faded = bitmap(theme.background or Color.BLACK)
            Canvas(faded).drawBitmap(front, 0f, 0f, Paint().apply { alpha = 128 })
            val expectedSource = pixels(faded)
            for (pose in POSES) for (direction in PageTurnDirection.entries) {
                val frame = render(pose, theme, front, under, direction)
                val pixels = pixels(frame)
                val regions = regions(pose)
                val g = regions.geometry
                // Reflect a point across the line through the two Bezier controls. This geometric
                // oracle is independent of the compositor's Android Matrix implementation.
                val nx = g.control2Y - g.control1Y
                val ny = g.control1X - g.control2X
                val lengthSquared = nx * nx + ny * ny
                for (index in regions.back) {
                    val x = index % WIDTH + 0.5f
                    val y = index / WIDTH + 0.5f
                    val distance = ((x - g.control1X) * nx + (y - g.control1Y) * ny) / lengthSquared
                    val sx = (x - 2f * distance * nx).toInt()
                    val sy = (y - 2f * distance * ny).toInt()
                    if (sx !in 3 until WIDTH - 3 || sy !in 3 until HEIGHT - 3) continue
                    val sourceIndex = sy * WIDTH + sx
                    // Ignore texture boundaries, where bilinear sampling correctly blends ink/paper.
                    if (!isInterior(source, sx, sy, source[sourceIndex])) continue
                    val expected = expectedSource[sourceIndex]
                    val actual = pixels[index]
                    // Skia's filtered transform can round fractional-alpha RGB one level
                    // differently from the untransformed reference, even within a solid patch.
                    assertTrue(
                        "$theme $pose $direction did not fade reflected ink at $index from ($sx, $sy)",
                        abs(Color.red(expected) - Color.red(actual)) <= 1 &&
                            abs(Color.green(expected) - Color.green(actual)) <= 1 &&
                            abs(Color.blue(expected) - Color.blue(actual)) <= 1
                    )
                    assertEquals("The back sheet must remain opaque", 255, Color.alpha(actual))
                    mirroredSamples++
                }
                frame.recycle()
            }
            faded.recycle()
        }
        assertTrue("The back must retain a substantial reflected texture", mirroredSamples > 1000)
    }

    @Test
    fun translucentSnapshotsAndThemesNeverRetainPreviousFramePixels() {
        val front = bitmap(0x8055AA77.toInt())
        val under = bitmap(Color.TRANSPARENT)
        val theme = Theme(0x40334455)
        for (pose in POSES) for (direction in PageTurnDirection.entries) {
            val first = render(pose, theme, front, under, direction, initial = Color.MAGENTA)
            val second = render(pose, theme, front, under, direction, initial = Color.CYAN)
            assertTrue("$pose $direction depends on the preceding frame", first.sameAs(second))
            assertTrue("$pose left transparent paper", pixels(first).all { Color.alpha(it) == 255 })
            first.recycle()
            second.recycle()
        }
    }

    @Test
    fun thePageUnderneathCannotBleedThroughTheBack() {
        val front = bitmap(0x8055AA77.toInt())
        val firstUnder = bitmap(Color.BLUE)
        val secondUnder = bitmap(Color.RED)
        for (pose in POSES) {
            val first = render(pose, Theme(0x40334455), front, firstUnder)
            val second = render(pose, Theme(0x40334455), front, secondUnder)
            val a = pixels(first)
            val b = pixels(second)
            for (index in regions(pose).back) {
                assertEquals("$pose lets the revealed page bleed through at $index", a[index], b[index])
                assertEquals(255, Color.alpha(a[index]))
            }
            first.recycle()
            second.recycle()
        }
    }

    @Test
    fun consecutiveAndReversedTurnsDoNotCarryPixelsOrCanvasState() {
        val front = patternedPage()
        val under = bitmap(Color.BLUE)
        val reused = bitmap(Color.MAGENTA)
        val canvas = Canvas(reused)
        val compositor = PageTurnCompositor()
        for (pose in POSES + POSES.reversed()) for (direction in PageTurnDirection.entries) {
            draw(compositor, canvas, pose, THEMES.first(), front, under, direction)
            val fresh = render(pose, THEMES.first(), front, under, direction)
            assertTrue("$pose $direction carried pixels from the preceding turn", fresh.sameAs(reused))
            assertEquals("The compositor must restore the caller's Canvas", 1, canvas.saveCount)
            fresh.recycle()
        }
    }

    @Test
    fun scaledPageSnapshotsUseTheSameReflectionAsViewportSizedPages() {
        val small = patternedPage(WIDTH / 2, HEIGHT / 2)
        val full = patternedPage()
        val under = bitmap(Color.BLUE)
        for (pose in POSES) {
            val reference = render(pose, Theme(Color.WHITE), full, under)
            val actual = render(pose, Theme(Color.WHITE), small, under)
            val expected = pixels(reference)
            val pixels = pixels(actual)
            for (index in regions(pose).back) {
                val x = index % WIDTH
                val y = index / WIDTH
                if (isInterior(expected, x, y, expected[index])) {
                    assertEquals("$pose did not scale the reflected snapshot at $index", expected[index], pixels[index])
                }
            }
            reference.recycle()
            actual.recycle()
        }
    }

    @Test
    fun simulationFallbackDoesNotReintroduceTheCoverShadow() {
        val paper = bitmap(Color.WHITE)
        for (direction in PageTurnDirection.entries) {
            val frame = render(Pose(WIDTH / 2f, Float.NaN, false), Theme(Color.WHITE), paper, paper, direction)
            assertPixels("Degenerate simulation must remain shadow-free", frame, Color.WHITE)
        }
    }

    @Test
    fun coverRetainsItsOwnShadowAndSlideStaysUnchanged() {
        val paper = bitmap(Color.WHITE)
        val pose = Pose(WIDTH / 2f, HEIGHT / 2f, false)
        val cover = render(pose, Theme(Color.WHITE), paper, paper, animation = PageTurnAnimation.COVER)
        val slide = render(pose, Theme(Color.WHITE), paper, paper, animation = PageTurnAnimation.SLIDE)
        assertTrue("Only simulation shadows are being removed", cover.getPixel(WIDTH / 2 + 2, HEIGHT / 2) != Color.WHITE)
        assertPixels("Sliding pages must remain unshaded", slide, Color.WHITE)
    }

    @Test
    fun actualTextStillAppearsOnTheFoldedBack() {
        val previewDirectory = System.getProperty("moread.pageTurnPreviewDir")?.let { File(it).apply { mkdirs() } }
        for ((themeIndex, theme) in THEMES.take(2).withIndex()) {
            val front = textPage(theme, "CURRENT PAGE")
            val under = textPage(theme, "NEXT PAGE")
            val blank = bitmap(theme.background)
            for ((poseIndex, pose) in POSES.take(4).withIndex()) {
                val printed = render(pose, theme, front, under)
                val empty = render(pose, theme, blank, under)
                val ink = pixels(printed)
                val paper = pixels(empty)
                assertTrue("$pose must keep the mirrored text", regions(pose).back.count { ink[it] != paper[it] } > 10)
                previewDirectory?.let { dir ->
                    File(dir, "theme-$themeIndex-pose-$poseIndex.png").outputStream().use {
                        printed.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                printed.recycle()
                empty.recycle()
            }
        }
    }

    private fun render(
        pose: Pose,
        theme: Theme,
        front: Bitmap,
        under: Bitmap,
        direction: PageTurnDirection = PageTurnDirection.NEXT,
        initial: Int = Color.MAGENTA,
        animation: PageTurnAnimation = PageTurnAnimation.SIMULATION
    ): Bitmap = bitmap(initial).also {
        draw(PageTurnCompositor(), Canvas(it), pose, theme, front, under, direction, animation)
    }

    private fun draw(
        compositor: PageTurnCompositor,
        canvas: Canvas,
        pose: Pose,
        theme: Theme,
        front: Bitmap,
        under: Bitmap,
        direction: PageTurnDirection,
        animation: PageTurnAnimation = PageTurnAnimation.SIMULATION
    ) = compositor.draw(
        canvas = canvas, animation = animation, direction = direction,
        front = front, under = under, touchX = pose.x, touchY = pose.y,
        startX = WIDTH.toFloat(), cornerAtTop = pose.top,
        width = WIDTH.toFloat(), height = HEIGHT.toFloat(),
        backgroundColor = theme.background
    )

    private fun bitmap(color: Int, width: Int = WIDTH, height: Int = HEIGHT): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(color)
            bitmaps += it
        }

    private fun patternedPage(width: Int = WIDTH, height: Int = HEIGHT): Bitmap = bitmap(Color.WHITE, width, height).also {
        val canvas = Canvas(it)
        val paint = Paint()
        for (row in 0..3) for (column in 0..3) {
            paint.color = intArrayOf(Color.RED, Color.BLACK, Color.CYAN, Color.GREEN)[(row + column) % 4]
            canvas.drawRect(column * width / 4f, row * height / 4f, (column + 1) * width / 4f, (row + 1) * height / 4f, paint)
        }
    }

    private fun textPage(theme: Theme, title: String): Bitmap = bitmap(theme.background).also {
        val canvas = Canvas(it)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (theme.dark) Color.rgb(224, 222, 214) else Color.rgb(32, 30, 24)
            textSize = 20f
        }
        canvas.drawText(title, 22f, 36f, paint)
        val body = "The story follows the light again."
        // Fill the printable area, including the bottom rows sampled by a corner fold.
        paint.textScaleX = (WIDTH - 44f) / paint.measureText(body)
        for (line in 0..20) canvas.drawText(body, 22f, 84f + line * 29f, paint)
        paint.textScaleX = 1f
        canvas.drawText("MoRead   9 / 86", 22f, HEIGHT - 24f, paint)
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun assertUnshadedPaper(message: String, bitmap: Bitmap, expected: Int, regions: Regions) {
        val actual = pixels(bitmap)
        // Native Skia's antialiased bitmap/clip boundaries can round RGB by up to 2 levels.
        // Allow that only for a small boundary population; surface interiors stay bit-exact.
        val largeDifference = actual.indexOfFirst {
            Color.alpha(it) != 255 || abs(Color.red(it) - Color.red(expected)) > 2 ||
                abs(Color.green(it) - Color.green(expected)) > 2 ||
                abs(Color.blue(it) - Color.blue(expected)) > 2
        }
        assertEquals("$message added shading at $largeDifference", -1, largeDifference)
        assertTrue("$message tinted a broad area", actual.count { it != expected } < actual.size / 100)
        val g = regions.geometry
        val nx = g.control2Y - g.control1Y
        val ny = g.control1X - g.control2X
        val lengthSquared = nx * nx + ny * ny
        val backInterior = regions.back.filter { index ->
            val x = index % WIDTH + 0.5f
            val y = index / WIDTH + 0.5f
            val distance = ((x - g.control1X) * nx + (y - g.control1Y) * ny) / lengthSquared
            val sx = x - 2f * distance * nx
            val sy = y - 2f * distance * ny
            // The reflected bitmap's own rectangle is antialiased as well as the fold clip.
            abs(sx) > 3f && abs(sx - WIDTH) > 3f && abs(sy) > 3f && abs(sy - HEIGHT) > 3f
        }
        val interiorDifference = (regions.front + regions.under + backInterior.toIntArray())
            .firstOrNull { actual[it] != expected }
        assertTrue("$message tinted a surface interior at $interiorDifference", interiorDifference == null)
    }

    private fun assertPixels(message: String, bitmap: Bitmap, expected: Int) {
        val pixels = pixels(bitmap)
        val index = pixels.indexOfFirst { it != expected }
        val actual = pixels.getOrNull(index)
        assertTrue("$message; first different pixel=$index expected=$expected actual=$actual", index == -1)
    }

    private fun regions(pose: Pose): Regions = regionCache.getOrPut(pose) {
        val g = PageFoldGeometry().apply { updateFromTouch(WIDTH.toFloat(), HEIGHT.toFloat(), pose.x, pose.y, pose.top) }
        val fold = mask(Path().apply {
            moveTo(g.start1X, g.start1Y)
            quadTo(g.control1X, g.control1Y, g.end1X, g.end1Y)
            lineTo(g.touchX, g.touchY)
            lineTo(g.end2X, g.end2Y)
            quadTo(g.control2X, g.control2Y, g.start2X, g.start2Y)
            lineTo(g.cornerX, g.cornerY)
            close()
        })
        val front = mutableListOf<Int>()
        val under = mutableListOf<Int>()
        val back = mutableListOf<Int>()
        val dx = g.vertex2X - g.vertex1X
        val dy = g.vertex2Y - g.vertex1Y
        val lineLength = hypot(dx, dy)
        fun side(x: Float, y: Float) = dx * (y - g.vertex1Y) - dy * (x - g.vertex1X)
        val touchSide = side(g.touchX, g.touchY)
        for (y in 3 until HEIGHT - 3 step 3) for (x in 3 until WIDTH - 3 step 3) {
            val index = y * WIDTH + x
            if (!isInterior(fold, x, y, fold[index])) continue
            if (fold[index] == Color.TRANSPARENT) {
                front += index
            } else {
                val pointSide = side(x + 0.5f, y + 0.5f)
                if (abs(pointSide) / lineLength <= 3f) continue
                // The crease is a segment, not an infinite line: the outer halves of the
                // curves past either vertex belong to the revealed page, even on this side.
                val along = ((x + 0.5f - g.vertex1X) * dx + (y + 0.5f - g.vertex1Y) * dy) / lineLength
                if (abs(along) <= 3f || abs(along - lineLength) <= 3f) continue
                if (pointSide * touchSide > 0f && along in 0f..lineLength) {
                    back += index
                } else {
                    under += index
                }
            }
        }
        Regions(g, front.toIntArray(), under.toIntArray(), back.toIntArray())
    }

    private fun mask(path: Path): IntArray {
        val bitmap = bitmap(Color.TRANSPARENT)
        Canvas(bitmap).drawPath(path, Paint().apply { color = Color.WHITE })
        return pixels(bitmap).also { bitmap.recycle() }
    }

    private fun isInterior(pixels: IntArray, x: Int, y: Int, color: Int): Boolean =
        (-2..2).all { dy -> (-2..2).all { dx -> pixels[(y + dy) * WIDTH + x + dx] == color } }

    private data class Pose(val x: Float, val y: Float, val top: Boolean)
    private data class Theme(val background: Int, val dark: Boolean = false)
    private data class Regions(
        val geometry: PageFoldGeometry,
        val front: IntArray,
        val under: IntArray,
        val back: IntArray
    )

    private companion object {
        const val WIDTH = 360
        const val HEIGHT = 720
        val THEMES = listOf(Theme(0xFFF6F1E8.toInt()), Theme(0xFF242627.toInt(), true), Theme(0x40B3CC88))
        val POSES = listOf(
            Pose(210f, HEIGHT - 0.1f, false), Pose(160f, 570f, false),
            Pose(210f, 0.1f, true), Pose(160f, 150f, true),
            Pose(25f, 405f, false), Pose(25f, 315f, true)
        ) + listOf(true, false).flatMap { top ->
            listOf(0.005f, 0.02f, 0.1f, 0.25f, 0.5f, 0.75f, 0.95f, 1f).map { progress ->
                val drop = 1f + HEIGHT * 0.3f * (1f - progress)
                Pose(WIDTH * (1f - 2f * progress), if (top) drop else HEIGHT - drop, top)
            }
        }
    }
}
