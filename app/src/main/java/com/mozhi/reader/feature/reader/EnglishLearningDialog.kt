package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.mozhi.reader.R
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.dictionary.LearningLanguage
import com.mozhi.reader.core.dictionary.TranslationTarget
import com.mozhi.reader.core.dictionary.WordAnnotationMode
import com.mozhi.reader.ui.components.*

/** Reading aids only. Dictionary lookup lives in the selection toolbar. */
@Composable
internal fun EnglishLearningDialog(bookId: Long, settings: ReaderSettings, palette: ReaderPalette,
    onDismiss: () -> Unit, viewModel: EnglishLearningViewModel = hiltViewModel(), onOpenBilingual: (() -> Unit)? = null) {
    var vocabulary by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val pageStates = rememberSaveableStateHolder()
    val language = LearningLanguage.fromCode(settings.learningLanguages[bookId])
    val target = TranslationTarget.fromCode(settings.translationTarget)
    val languageChoices = learningLanguageChoices()
    val targetChoices = translationTargetChoices()
    ReaderToolDialog(onDismiss = onDismiss, immersiveOnPhone = true) {
        BackHandler(enabled = vocabulary) { vocabulary = false }
        pageStates.SaveableStateProvider(if (vocabulary) "vocabulary" else "aids") {
            if (vocabulary) VocabularyPage(bookId, palette, onBack = { vocabulary = false }, viewModel = viewModel, panelBack = true)
            else ReaderToolPage(title = stringResource(R.string.language_aids_title), onBack = onDismiss, applyTopInset = false) {
                item {
                    MoReadSection(title = stringResource(R.string.language_aids_section)) {
                        val current = languageChoices.first { it.value == language }
                        MoReadChoiceRow(title = stringResource(R.string.language_book_language), value = current.label,
                            badge = current.badge, tint = current.tint, subtitle = stringResource(R.string.language_book_language_hint),
                            onClick = { picking = "language" }, modifier = Modifier.testTag("learning-language"))
                        MoReadRowDivider()
                        MoReadSwitchRow(title = stringResource(R.string.language_gloss_title), subtitle = stringResource(R.string.language_gloss_summary),
                            checked = settings.englishLearningEnabled, onCheckedChange = viewModel::setEnabled)
                        MoReadRowDivider()
                        MoReadBlock {
                            Column {
                                val modeLabels = WordAnnotationMode.entries.associateWith { annotationModeLabel(it) }
                                MoReadSegmented(WordAnnotationMode.entries, settings.wordAnnotationMode, viewModel::setAnnotationMode,
                                    label = { modeLabels.getValue(it) })
                                Text(stringResource(R.string.language_gloss_mode_hint), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        MoReadRowDivider()
                        MoReadSwitchRow(title = stringResource(R.string.language_bionic_title), subtitle = stringResource(R.string.language_bionic_summary),
                            checked = settings.englishBionicEnabled, onCheckedChange = viewModel::setBionic)
                    }
                }
                item {
                    MoReadSection(title = stringResource(R.string.language_translation_section)) {
                        val current = targetChoices.first { it.value == target }
                        MoReadChoiceRow(title = stringResource(R.string.language_translation_target), value = current.label,
                            badge = current.badge, tint = current.tint, onClick = { picking = "target" },
                            modifier = Modifier.testTag("translation-target"))
                        onOpenBilingual?.let { open ->
                            MoReadRowDivider()
                            MoReadRow(title = stringResource(R.string.language_bilingual_open),
                                subtitle = stringResource(if (bookId in settings.bilingualBooks) R.string.language_bilingual_on else R.string.language_bilingual_off),
                                onClick = open, modifier = Modifier.testTag("open-bilingual"))
                        }
                    }
                }
                item { MoReadSection { MoReadRow(title = stringResource(R.string.settings_vocabulary), onClick = { vocabulary = true }) } }
            }
        }
    }
    when (picking) {
        "language" -> MoReadChoiceDialog(stringResource(R.string.language_book_language), languageChoices, language,
            onSelect = { viewModel.setLearningLanguage(bookId, it) }, onDismiss = { picking = null })
        "target" -> MoReadChoiceDialog(stringResource(R.string.language_translation_target), targetChoices, target,
            onSelect = viewModel::setTranslationTarget, onDismiss = { picking = null })
    }
}

/** 语言徽标：用该语言自己的文字写出简称，比国旗更准确（一种语言不只属于一个国家）。 */
private val LANGUAGE_BADGES = mapOf(
    LearningLanguage.AUTO to "A", LearningLanguage.EN to "En", LearningLanguage.JA to "あ", LearningLanguage.KO to "한",
    LearningLanguage.FR to "Fr", LearningLanguage.DE to "De", LearningLanguage.ES to "Es", LearningLanguage.IT to "It",
    LearningLanguage.PT to "Pt", LearningLanguage.RU to "Ру"
)
private val LANGUAGE_TINTS = mapOf(
    LearningLanguage.EN to Color(0xFF3F6FB5), LearningLanguage.JA to Color(0xFFC4473A), LearningLanguage.KO to Color(0xFF2F7F8F),
    LearningLanguage.FR to Color(0xFF5560B0), LearningLanguage.DE to Color(0xFFB0812A), LearningLanguage.ES to Color(0xFFC0602E),
    LearningLanguage.IT to Color(0xFF3E8A5B), LearningLanguage.PT to Color(0xFF2E7D4F), LearningLanguage.RU to Color(0xFF7A5BA8)
)

@Composable
internal fun learningLanguageChoices(): List<MoReadChoice<LearningLanguage>> = LearningLanguage.entries.map { language ->
    MoReadChoice(language, learningLanguageLabel(language),
        supporting = if (language == LearningLanguage.AUTO) stringResource(R.string.learning_language_auto_hint) else null,
        badge = LANGUAGE_BADGES[language], tint = LANGUAGE_TINTS[language])
}

@Composable
private fun translationTargetChoices(): List<MoReadChoice<TranslationTarget>> = TranslationTarget.entries.map { target ->
    MoReadChoice(target, translationTargetLabel(target), badge = when (target) {
        TranslationTarget.ZH_HANS -> stringResource(R.string.translation_target_zh_hans_badge)
        TranslationTarget.ZH_HANT -> stringResource(R.string.translation_target_zh_hant_badge)
        TranslationTarget.EN -> "En"
    }, tint = if (target == TranslationTarget.EN) LANGUAGE_TINTS[LearningLanguage.EN] else Color(0xFFB5453A))
}

@Composable
internal fun learningLanguageLabel(language: LearningLanguage): String = stringResource(when (language) {
    LearningLanguage.AUTO -> R.string.learning_language_auto
    LearningLanguage.EN -> R.string.learning_language_en
    LearningLanguage.JA -> R.string.learning_language_ja
    LearningLanguage.KO -> R.string.learning_language_ko
    LearningLanguage.FR -> R.string.learning_language_fr
    LearningLanguage.DE -> R.string.learning_language_de
    LearningLanguage.ES -> R.string.learning_language_es
    LearningLanguage.IT -> R.string.learning_language_it
    LearningLanguage.PT -> R.string.learning_language_pt
    LearningLanguage.RU -> R.string.learning_language_ru
})

@Composable
private fun translationTargetLabel(target: TranslationTarget): String = stringResource(when (target) {
    TranslationTarget.ZH_HANS -> R.string.translation_target_zh_hans
    TranslationTarget.ZH_HANT -> R.string.translation_target_zh_hant
    TranslationTarget.EN -> R.string.translation_target_en
})

@Composable
private fun annotationModeLabel(mode: WordAnnotationMode): String = stringResource(when (mode) {
    WordAnnotationMode.INLINE -> R.string.language_gloss_mode_inline
    WordAnnotationMode.POPUP -> R.string.language_gloss_mode_popup
    WordAnnotationMode.OFF -> R.string.language_gloss_mode_off
})
