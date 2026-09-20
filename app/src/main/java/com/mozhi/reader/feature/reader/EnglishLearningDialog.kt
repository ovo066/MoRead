package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.dictionary.WordAnnotationMode
import com.mozhi.reader.ui.components.*

/** Reading aids only. Dictionary lookup lives in the selection toolbar. */
@Composable
internal fun EnglishLearningDialog(bookId: Long, settings: ReaderSettings, palette: ReaderPalette,
    onDismiss: () -> Unit, viewModel: EnglishLearningViewModel = hiltViewModel()) {
    var vocabulary by rememberSaveable { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()
    ReaderToolDialog(onDismiss = onDismiss, immersiveOnPhone = true) {
        BackHandler(enabled = vocabulary) { vocabulary = false }
        pageStates.SaveableStateProvider(if (vocabulary) "vocabulary" else "aids") {
            if (vocabulary) VocabularyPage(bookId, palette, onBack = { vocabulary = false }, viewModel = viewModel, panelBack = true)
            else ReaderToolPage(title = "阅读辅助", onBack = onDismiss, applyTopInset = false) {
                item {
                    MoReadSection(title = "英文阅读") {
                        MoReadSwitchRow(title = "英文生词标注", subtitle = "查词使用长按划线菜单；划线标注可点击查看释义",
                            checked = settings.englishLearningEnabled, onCheckedChange = viewModel::setEnabled)
                        MoReadRowDivider()
                        MoReadBlock {
                            Column {
                                MoReadSegmented(WordAnnotationMode.entries, settings.wordAnnotationMode, viewModel::setAnnotationMode, label = { it.label })
                                Text("标注生词本中未掌握的英文词。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        MoReadRowDivider()
                        MoReadSwitchRow(title = "英文仿生阅读", subtitle = "加粗单词前半部分", checked = settings.englishBionicEnabled, onCheckedChange = viewModel::setBionic)
                    }
                }
                item { MoReadSection { MoReadRow(title = "生词本", onClick = { vocabulary = true }) } }
            }
        }
    }
}
