package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.ui.theme.MoReadTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnotationEmptySendUiTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun emptyComposerInvitesCurrentCompanionAndRequiresAnAvailablePersona() {
        val annotation = AnnotationEntity(id = 4, bookId = 1, chapterIndex = 2, startCharOffset = 0, endCharOffset = 12,
            selectedText = "雨停了，灯塔的光依然亮着。", createdAt = 1)
        val persona = PersonaEntity(id = 3, name = "知秋", personality = "", isRoleplay = true, createdAt = 0)
        var personas by mutableStateOf(listOf(persona))
        var sent: Triple<Long, String, Long?>? = null
        compose.setContent { MoReadTheme {
            val palette = readerPalette(ReaderSettings(), false)
            ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                AnnotationDiscussionSheet(listOf(annotation), emptyList(), null, null, personas, emptyList(), palette, {},
                    { target, text, id -> sent = Triple(target.id, text, id) }, { _, _, _ -> }, {}, {}, {}, {},
                    defaultRespondPersonaId = 3)
            }
        } }
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(Triple(4L, "", 3L), sent)
        compose.runOnIdle {
            val root = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File("build/reports/ui-qa/annotation-empty-send.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            personas = emptyList()
        }
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    /** 忘记点名是段评「发出去没人回」的唯一成因：预选角色必须随文字一起发出去。 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun typedTextKeepsThePreselectedPersonaAndCanBeOptedOut() {
        val annotation = AnnotationEntity(id = 7, bookId = 1, chapterIndex = 0, startCharOffset = 0, endCharOffset = 6,
            selectedText = "灯塔的光。", createdAt = 1)
        val persona = PersonaEntity(id = 5, name = "知秋", personality = "", isRoleplay = true, createdAt = 0)
        var sent: Triple<Long, String, Long?>? = null
        var remembered: Long? = null
        compose.setContent { MoReadTheme {
            val palette = readerPalette(ReaderSettings(), false)
            ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                AnnotationDiscussionSheet(listOf(annotation), emptyList(), null, null, listOf(persona), emptyList(), palette, {},
                    { target, text, id -> sent = Triple(target.id, text, id) }, { _, _, _ -> }, {}, {}, {}, {},
                    defaultRespondPersonaId = 5, onRememberRespondPersona = { remembered = it })
            }
        } }

        compose.onNodeWithText("写下你的想法…").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("这段写得真好")
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(Triple(7L, "这段写得真好", 5L), sent)

        // 再点一次选中的胶囊 = 这条只记想法，不叫人回复；取消不写回记忆。
        compose.onNodeWithText("知秋").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("先自己记一笔")
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(Triple(7L, "先自己记一笔", null), sent)
        assertNull(remembered)
    }
}
