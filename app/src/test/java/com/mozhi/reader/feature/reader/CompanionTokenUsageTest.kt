package com.mozhi.reader.feature.reader

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.ui.theme.MoReadTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CompanionTokenUsageTest {
    @get:Rule val compose = createComposeRule()
    private val message = MessageEntity(1, 1, "assistant", "回复", createdAt = 1, inputTokens = 100, outputTokens = 20, generationTimeMs = 2000)

    @Test fun togglingOnlyChangesVisibilityAndMultiBubbleDoesNotRepeatUsage() {
        var show by mutableStateOf(false)
        compose.setContent { MoReadTheme {
            CompositionLocalProvider(LocalShowCompanionTokenUsage provides show) {
                Column {
                    val palette = readerPalette(ReaderSettings(), false)
                    CompanionChatBubble(ChatEntry.Bubble("a", CompanionBubblePart.Text("第一段"), false, message, isLastMessagePart = false), palette, "伴读", null)
                    CompanionChatBubble(ChatEntry.Bubble("b", CompanionBubblePart.Text("第二段"), false, message), palette, "伴读", null)
                }
            }
        } }
        val label = "↑ 100 · ↓ 20 · 10.0 tok/s"
        compose.onNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { show = true }
        compose.onAllNodesWithText(label).assertCountEquals(1)
        compose.runOnIdle { show = false }
        compose.onNodeWithText(label).assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("第二段").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("第二段").assertExists()
    }

    @Test fun missingUsageAndDurationAreNotInvented() {
        assertEquals("暂无用量", companionTokenUsageLabel(message.copy(inputTokens = null, outputTokens = null)))
        assertEquals("↑ 100 · ↓ — · — tok/s", companionTokenUsageLabel(message.copy(outputTokens = null)))
        assertEquals("↑ 100 · ↓ 20 · — tok/s", companionTokenUsageLabel(message.copy(generationTimeMs = null)))
    }
}
