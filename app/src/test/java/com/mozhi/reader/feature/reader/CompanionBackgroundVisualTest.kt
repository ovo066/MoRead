package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.ReaderImageAsset
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.feature.companion.*
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.MoReadTheme
import com.mozhi.reader.ui.theme.ThemeMode
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DelicateCoilApi::class)
class CompanionBackgroundVisualTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val files = TemporaryFolder()
    private lateinit var root: View
    private var theme by mutableStateOf(ThemeMode.LIGHT)
    private var paper = Color.Unspecified
    private var sidebarInk = Color.Unspecified
    @Volatile private var imageError: Throwable? = null

    @Before fun configureImages() {
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ApplicationProvider.getApplicationContext())
            // Robolectric on Windows cannot use ImageDecoder's native file binding.
            // Still decode real PNGs through Coil, using its BitmapFactory implementation.
            .components { add(BitmapFactoryDecoder.Factory()) }
            .eventListener(object : EventListener() {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    imageError = result.throwable
                }
            }).build())
    }

    @After fun resetImages() = SingletonImageLoader.reset()

    @Test fun readerPhoneKeepsWallpaperAcrossThemeAndAppearanceChanges() = checkWallpaper(library = false)

    @Test @Config(qualifiers = "w1400dp-h960dp-mdpi")
    fun readerTabletWallpaperFillsChatPaneIncludingColumnMargins() = checkWallpaper(library = false, tablet = true)

    @Test fun libraryPhoneKeepsWallpaperAcrossThemeAndAppearanceChanges() = checkWallpaper(library = true)

    @Test @Config(qualifiers = "w1400dp-h960dp-mdpi")
    fun libraryTabletWallpaperFillsChatPaneIncludingColumnMargins() = checkWallpaper(library = true, tablet = true)

    @Test @Config(qualifiers = "w1400dp-h960dp-mdpi")
    fun embeddedReaderWallpaperFillsSidePane() = checkWallpaper(library = false, embedded = true)

    private fun checkWallpaper(library: Boolean, tablet: Boolean = false, embedded: Boolean = false) {
        val imageColor = Color(0xFF739C9C)
        val otherColor = Color(0xFFC4866B)
        val first = wallpaper("first.png", imageColor)
        val second = wallpaper("second.png", otherColor)
        val images = listOf(ReaderImageAsset("first", "海边", first.absolutePath), ReaderImageAsset("second", "暮色", second.absolutePath))
        var appearance = PersonaChatAppearance(backgroundImageId = "first", backgroundDim = 0f)
        val persona = PersonaEntity(id = 3, name = "知秋", personality = "慢慢读，也慢慢聊。", isRoleplay = false, createdAt = 1,
            greeting = "雨停之后，我们可以从窗边那封旧信聊起。你觉得林舟为什么迟迟没有回信？")
        val conversation = ConversationEntity(id = 7, bookId = if (library) null else 1, personaId = 3,
            title = "雨夜里的灯塔与那封迟来的信", type = if (library) "LIBRARY_COMPANION" else "COMPANION", createdAt = 1)
        val rows = listOf(
            MessageEntity(id = 1, conversationId = 7, role = "user", content = "这封旧信对林舟意味着什么？", createdAt = 1),
            MessageEntity(id = 2, conversationId = 7, role = "assistant", content = "信像是一个迟到的回应，把他与过去重新连在一起。我们可以回到他放下信、起身开门的那一段，再读一读这个转折。", createdAt = 2)
        )
        val readerState = MutableStateFlow(CompanionChatUiState(personas = listOf(persona), activePersona = persona,
            conversationId = 7, conversations = listOf(conversation), messages = rows, isLoadingMessages = false,
            appearance = appearance, backgroundImagePath = first.absolutePath))
        val catalog = MutableStateFlow(LibraryChatCatalog(personas = listOf(persona.copy(chatAppearanceJson = PersonaChatAppearanceCodec.encode(appearance))),
            activePersonaId = 3, settings = ReaderSettings(imageLibrary = images)))
        val reader = mockk<ReaderCompanionViewModel>(relaxed = true)
        every { reader.uiState } returns readerState
        every { reader.chatContext } returns MutableStateFlow(CompanionChatContext("雨夜里的灯塔"))
        every { reader.events } returns emptyFlow()
        val media = mockk<ReaderSelectionMediaViewModel>(relaxed = true)
        val libraryModel = mockk<LibraryCompanionViewModel>(relaxed = true)
        every { libraryModel.session } returns MutableStateFlow(LibraryChatSession(conversation = conversation))
        every { libraryModel.catalog } returns catalog
        every { libraryModel.messages } returns MutableStateFlow(LibraryChatMessages(rows = rows, conversationId = 7))
        every { libraryModel.conversations } returns MutableStateFlow(listOf(conversation))
        every { libraryModel.events } returns emptyFlow()
        compose.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            MoReadTheme(AppearanceSettings(themeMode = theme)) {
                val palette = companionChatPalette()
                val ink = MaterialTheme.colorScheme.onSurface
                SideEffect { paper = palette.background; sidebarInk = ink }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    Box(if (embedded) Modifier.width(440.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        if (library) LibraryCompanionScreen({}, {}, {}, libraryModel)
                        else CompanionChatPane(1, {}, reader, media, embedded = embedded)
                    }
                }
            }
        }
        fun update(dim: Float, imageId: String?) {
            compose.runOnIdle {
                appearance = appearance.copy(backgroundImageId = imageId, backgroundDim = dim)
                readerState.value = readerState.value.copy(appearance = appearance,
                    backgroundImagePath = images.firstOrNull { it.id == imageId }?.filePath)
                catalog.value = catalog.value.copy(personas = listOf(persona.copy(chatAppearanceJson = PersonaChatAppearanceCodec.encode(appearance))))
            }
        }
        val name = if (library) "library" else if (embedded) "embedded" else "reader"
        val size = if (tablet || embedded) "tablet" else "phone"
        compose.onNodeWithText("这封旧信对林舟意味着什么？").assertIsDisplayed()
        if (tablet) compose.onNodeWithTag("companion-history-sidebar").assertIsDisplayed()
        ThemeMode.entries.filter { it == ThemeMode.LIGHT || it == ThemeMode.DARK }.forEach { mode ->
            compose.runOnIdle { theme = mode }
            update(0f, "first")
            assertWallpaper(imageColor, tablet, embedded, "$name-$size-${mode.name.lowercase()}-image")
            if (tablet) assertSidebarInk(if (library) "书库伴读" else "阅读伴读")
            update(0.35f, "first")
            compose.waitForIdle()
            assertWallpaper(paper.copy(alpha = 0.35f).compositeOver(imageColor), tablet, embedded,
                "$name-$size-${mode.name.lowercase()}-dimmed")
        }
        update(0f, "second")
        assertWallpaper(otherColor, tablet, embedded, "$name-$size-changed")
        update(0f, null)
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = snapshot()
            assertTrue("Clearing the selection must restore the default backdrop", !closeColor(bitmap.getPixel(root.width - 4, root.height / 2), otherColor.toArgb()))
            bitmap.recycle()
        }
    }

    private fun wallpaper(name: String, color: Color): File = files.newFile(name).also { file ->
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color.toArgb())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun snapshot(): Bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }

    private fun assertWallpaper(expected: Color, tablet: Boolean, embedded: Boolean, name: String) {
        // Check actual rendered pixels: a present AsyncImage node can still be fully occluded.
        compose.waitUntil(timeoutMillis = 15_000) {
            imageError?.let { throw AssertionError("Wallpaper decoding failed", it) }
            var visible = false
            compose.runOnIdle {
                val bitmap = snapshot()
                val left = if (tablet) 264 else if (embedded) root.width - 440 else 0
                visible = listOf(left + 4, root.width - 4).all { x ->
                    closeColor(bitmap.getPixel(x, root.height / 2), expected.toArgb())
                }
                val file = File("build/reports/ui-qa/companion-background-$name.png").apply { parentFile?.mkdirs() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            visible
        }
    }

    private fun closeColor(actual: Int, expected: Int): Boolean = listOf(0, 8, 16).all { shift ->
        abs(((actual shr shift) and 255) - ((expected shr shift) and 255)) <= 3
    }

    private fun assertSidebarInk(title: String) {
        val bounds = compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("companion-history-sidebar")))
            .fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = snapshot()
            val inkPixels = (bounds.top.toInt() until bounds.bottom.toInt()).sumOf { y ->
                (bounds.left.toInt() until bounds.right.toInt()).count { x -> closeColor(bitmap.getPixel(x, y), sidebarInk.toArgb()) }
            }
            bitmap.recycle()
            assertTrue("History title must use the current theme's foreground color", inkPixels > 20)
        }
    }
}
