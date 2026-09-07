package com.mozhi.reader.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.datastore.BookProactiveAnnotationLimits
import com.mozhi.reader.core.datastore.ProactiveAnnotationLimitSteps
import com.mozhi.reader.core.datastore.ProactiveAnnotationLimits
import com.mozhi.reader.ui.components.MoReadBlock
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.components.MoReadSlider
import com.mozhi.reader.ui.components.MoReadSwitchRow
import com.mozhi.reader.ui.components.MoReadValueRow

/**
 * 随读段评的数量与频控。全局与单本书共用同一页：[bookId] 非空即「本书」变体，
 * 顶部多一个「本书单独设置」开关，关掉时只读地摊出全局值（和详情页「本书主题」同一套语义）。
 */
@Composable
fun ProactiveAnnotationSettingsScreen(
    bookId: Long?,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val global = state.autonomy.annotationLimits
    val override = bookId?.let { state.autonomy.annotationLimitsByBook[it] }
    val perBookActive = override?.enabled == true
    val editing = if (bookId == null) global else override?.limits ?: global

    fun commit(next: ProactiveAnnotationLimits) {
        if (bookId == null) {
            viewModel.setAnnotationLimits(next)
        } else {
            viewModel.setBookAnnotationLimits(bookId, BookProactiveAnnotationLimits(true, next))
        }
    }

    MoReadSecondaryPage(
        title = if (bookId == null) "段评数量与频率" else "本书随读段评",
        subtitle = if (bookId == null) null else "只影响这一本书",
        onBack = onBack
    ) {
        if (bookId != null) {
            item {
                MoReadSection(
                    title = "适用范围",
                    footer = "关闭时这本书跟随全局默认；打开后下面的数值只对这本书生效。"
                ) {
                    MoReadSwitchRow(
                        icon = Icons.Outlined.BorderColor,
                        title = "本书单独设置",
                        subtitle = if (perBookActive) editing.summary() else "当前跟随全局：${global.summary()}",
                        checked = perBookActive,
                        onCheckedChange = { enabled ->
                            viewModel.setBookAnnotationLimits(
                                bookId,
                                if (enabled) BookProactiveAnnotationLimits(true, editing) else null
                            )
                        }
                    )
                }
            }
        }
        item {
            MoReadSection(
                title = "每章条数",
                footer = "下限只是写给模型的请求：本章确实没有值得回应的地方时，它仍然可以少给几条。" +
                    "上限选「不限制」＝由模型自己决定写几条，可能明显多花 API 额度。"
            ) {
                LimitSlider(
                    label = "下限",
                    value = editing.minPerChapter,
                    from = 0,
                    to = if (editing.chapterUnlimited) {
                        ProactiveAnnotationLimits.MAX_PER_CHAPTER
                    } else {
                        editing.maxPerChapter
                    },
                    allowUnlimited = false,
                    enabled = bookId == null || perBookActive,
                    onValueChange = { commit(editing.copy(minPerChapter = it).normalized()) }
                )
                MoReadRowDivider()
                LimitSlider(
                    label = "上限",
                    value = editing.maxPerChapter,
                    from = 1,
                    to = ProactiveAnnotationLimits.MAX_PER_CHAPTER,
                    allowUnlimited = true,
                    enabled = bookId == null || perBookActive,
                    onValueChange = { commit(editing.copy(maxPerChapter = it).normalized()) }
                )
            }
        }
        item {
            MoReadSection(
                title = "每日上限",
                footer = "按自然日计，跨天自动归零。每章仍然只生成一次，回头重读不会重复出批注。"
            ) {
                LimitSlider(
                    label = "段评",
                    value = editing.dailyMax,
                    from = 1,
                    to = ProactiveAnnotationLimits.MAX_DAILY,
                    allowUnlimited = true,
                    enabled = bookId == null || perBookActive,
                    onValueChange = { commit(editing.copy(dailyMax = it).normalized()) }
                )
                MoReadRowDivider()
                LimitSlider(
                    label = "语音",
                    value = editing.dailyVoiceMax,
                    from = 0,
                    to = ProactiveAnnotationLimits.MAX_DAILY,
                    allowUnlimited = true,
                    enabled = bookId == null || perBookActive,
                    onValueChange = { commit(editing.copy(dailyVoiceMax = it).normalized()) }
                )
                MoReadRowDivider()
                LimitSlider(
                    label = "插图",
                    value = editing.dailyImageMax,
                    from = 0,
                    to = ProactiveAnnotationLimits.MAX_DAILY,
                    allowUnlimited = true,
                    enabled = bookId == null || perBookActive,
                    onValueChange = { commit(editing.copy(dailyImageMax = it).normalized()) }
                )
            }
        }
        item {
            MoReadSection(title = "当前生效") {
                MoReadValueRow(
                    title = if (bookId == null) "全局默认" else "这本书",
                    value = if (bookId == null || perBookActive) editing.summary() else global.summary()
                )
            }
        }
    }
}

@Composable
private fun LimitSlider(
    label: String,
    value: Int,
    from: Int,
    to: Int,
    allowUnlimited: Boolean,
    enabled: Boolean,
    onValueChange: (Int) -> Unit
) {
    val steps = ProactiveAnnotationLimitSteps.steps(from, to, allowUnlimited)
    val index = ProactiveAnnotationLimitSteps.indexOf(steps, value)
    MoReadBlock {
        MoReadSlider(
            label = label,
            valueText = ProactiveAnnotationLimitSteps.label(
                ProactiveAnnotationLimitSteps.valueAt(steps, index)
            ),
            value = index.toFloat(),
            range = 0f..(steps.size - 1).coerceAtLeast(1).toFloat(),
            step = 1f,
            onValueChange = { next ->
                if (enabled) onValueChange(ProactiveAnnotationLimitSteps.valueAt(steps, next.toInt()))
            }
        )
    }
}
