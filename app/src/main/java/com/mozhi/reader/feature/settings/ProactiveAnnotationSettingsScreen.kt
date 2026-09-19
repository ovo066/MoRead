package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.datastore.AnnotationContextMode
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import com.mozhi.reader.core.datastore.BookProactiveAnnotationLimits
import com.mozhi.reader.core.datastore.ProactiveAnnotationLimitSteps
import com.mozhi.reader.core.datastore.ProactiveAnnotationLimits
import com.mozhi.reader.core.datastore.ProactiveAnnotationNotice
import com.mozhi.reader.core.datastore.ProactiveAnnotationTiming
import com.mozhi.reader.ui.components.MoReadBlock
import com.mozhi.reader.ui.components.MoReadRow
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.components.MoReadSegmented
import com.mozhi.reader.ui.components.MoReadSlider
import com.mozhi.reader.ui.components.MoReadSwitchRow
import com.mozhi.reader.ui.components.MoReadValueRow
import com.mozhi.reader.ui.components.PersonaAvatarImage

/**
 * 随读段评的数量与频控。全局与单本书共用同一页：[bookId] 非空即「本书」变体，
 * 顶部多一个「本书单独设置」开关，关掉时只读地摊出全局值（和详情页「本书主题」同一套语义）。
 */
@Composable
fun ProactiveAnnotationSettingsScreen(
    bookId: Long?,
    onBack: () -> Unit,
    onOpenPrompts: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val usedToday by viewModel.todayAnnotationCount.collectAsStateWithLifecycle()
    ProactiveAnnotationSettingsContent(
        bookId, state, onBack, viewModel::setAnnotationLimits,
        viewModel::setBookAnnotationLimits, viewModel::setProactiveAnnotations,
        viewModel::setAnnotationNotice, viewModel::setAnnotationPersonas, onOpenPrompts, viewModel::toggleAnnotationPersona,
        usedToday
    )
}

@Composable
internal fun ProactiveAnnotationSettingsContent(
    bookId: Long?,
    state: SettingsUiState,
    onBack: () -> Unit,
    onSetLimits: (ProactiveAnnotationLimits) -> Unit,
    onSetBookLimits: (Long, BookProactiveAnnotationLimits?) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetNotice: (ProactiveAnnotationNotice) -> Unit,
    onSetPersonas: (Set<Long>) -> Unit = {},
    onOpenPrompts: () -> Unit = {},
    onTogglePersona: (Long) -> Unit = {},
    usedToday: Int = 0
) {
    val global = state.autonomy.annotationLimits
    val override = bookId?.let { state.autonomy.annotationLimitsByBook[it] }
    val perBookActive = override?.enabled == true
    val editing = if (bookId == null || !perBookActive) global else override.limits

    fun commit(next: ProactiveAnnotationLimits) {
        if (bookId == null) {
            onSetLimits(next)
        } else {
            onSetBookLimits(bookId, BookProactiveAnnotationLimits(true, next))
        }
    }

    MoReadSecondaryPage(
        title = if (bookId == null) "随读段评" else "本书随读段评",
        subtitle = if (bookId == null) null else "只影响这一本书",
        onBack = onBack
    ) {
        // A cold entry shows the stable page shell, never fake default switch/slider values.
        // Normal settings navigation shares the already-loaded parent ViewModel.
        if (!state.isLoaded) return@MoReadSecondaryPage
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
                            onSetBookLimits(
                                bookId,
                                if (enabled) BookProactiveAnnotationLimits(true, editing) else null
                            )
                        }
                    )
                }
            }
        }
        if (bookId == null) {
            item {
                MoReadSection(title = "总开关", footer = "默认关闭。开启后会消耗 API 额度。") {
                    MoReadSwitchRow(icon = Icons.Outlined.BorderColor, title = "随读段评",
                        subtitle = "按下面的方式自动留下批注",
                        checked = state.autonomy.proactiveAnnotationsEnabled,
                        onCheckedChange = onSetEnabled)
                }
            }
        }
        if (bookId == null) {
            item {
                val selected = state.autonomy.annotationPersonaIds
                MoReadSection(title = "参与段评的伴读", icon = Icons.Outlined.Groups,
                    footer = "每章条数对每位伴读分别生效，每日额度由所有伴读共享，并为后续伴读预留份额。未单独选择时跟随当前伴读。") {
                    MoReadRow(title = "跟随当前伴读", subtitle = state.personas.firstOrNull { it.id == state.activePersonaId }?.name ?: "尚未选择伴读",
                        onClick = { onSetPersonas(emptySet()) }, trailing = {
                            RadioButton(selected = selected.isEmpty(), onClick = { onSetPersonas(emptySet()) })
                        })
                    state.personas.forEach { persona ->
                        MoReadRowDivider()
                        MoReadRow(title = persona.name, subtitle = if (persona.id in selected) "已参与主动段评" else "可与其他伴读一起参与",
                            onClick = { onTogglePersona(persona.id) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PersonaAvatarImage(persona.name, persona.avatarPath, Modifier.size(32.dp))
                                    Checkbox(checked = persona.id in selected, onCheckedChange = {
                                        onTogglePersona(persona.id)
                                    })
                                }
                            })
                    }
                }
            }
            item {
                MoReadSection(title = "写作方式") {
                    MoReadRow(icon = Icons.Outlined.EditNote, title = "主动段评提示词",
                        subtitle = "${state.autonomy.annotationPrompts.count { it.enabled }} / ${state.autonomy.annotationPrompts.size} 条启用 · 可编辑口吻与注入位置",
                        onClick = onOpenPrompts)
                }
            }
        }
        item {
            MoReadSection(title = "生成方式", footer = if (editing.timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY) {
                "预生成会让 AI 提前读到你还没读到的段落；段评按段落逐条生成、只看到该段之前的正文，" +
                    "且在你读到之前不会出现在聊天、工具或通知里。每条段评一次模型调用；使用主动段评分配，未配置时使用 cheap。提前 N 章会预先消耗 N 章的额度。"
            } else "读完一章后为刚读完的章节生成，不读取未读正文。每条段评一次模型调用，未单独分配时使用 cheap。") {
                MoReadBlock {
                    // 跟随全局时整组只读：静默吞掉点击会让用户以为已经改了生成方式。
                    MoReadSegmented(options = ProactiveAnnotationTiming.entries.toList(), selected = editing.timing,
                        onSelect = { commit(editing.copy(timing = it)) },
                        enabled = bookId == null || perBookActive,
                        label = { if (it == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY) "进入章节预生成" else "读完后生成上一章" })
                }
                if (editing.timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY) {
                    MoReadRowDivider()
                    LimitSlider(label = "额外提前", value = editing.aheadChapters, from = 0, to = 5,
                        allowUnlimited = false, enabled = bookId == null || perBookActive,
                        unit = "章", onValueChange = { commit(editing.copy(aheadChapters = it)) })
                    MoReadValueRow(title = "提前范围", value = "0 = 仅本章")
                }
            }
        }
        if (bookId == null) {
            item {
                MoReadSection(title = "伴读弹幕", footer = "5 秒自动收起，「查看」只指向已读到的段评。" +
                    "快速模型按角色性格和语气说一句共读感想，不发送正文、段评或聊天记录，也不暗示未读剧情。" +
                    "每次额外 1 次调用，失败或超时回落为自然短句，不播报条数。") {
                    ProactiveAnnotationNotice.entries.forEachIndexed { index, mode ->
                        if (index > 0) MoReadRowDivider()
                        MoReadRow(title = when (mode) {
                            ProactiveAnnotationNotice.OFF -> "不提示"
                            ProactiveAnnotationNotice.BUILT_IN -> "内置提示（默认）"
                            ProactiveAnnotationNotice.FAST_MODEL -> "快速模型 · 角色互动"
                        }, subtitle = when (mode) {
                            ProactiveAnnotationNotice.OFF -> "段评静默写入，不做完成提示"
                            ProactiveAnnotationNotice.BUILT_IN -> "头像胶囊只说条数，不额外调用模型"
                            ProactiveAnnotationNotice.FAST_MODEL -> "像角色边读边说话，例如：还挺有意思，你怎么看？"
                        }, onClick = { onSetNotice(mode) }, trailing = {
                            RadioButton(selected = state.autonomy.annotationNotice == mode,
                                onClick = { onSetNotice(mode) })
                        })
                    }
                    if (state.autonomy.annotationNotice != ProactiveAnnotationNotice.OFF) {
                        MoReadBlock {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                PersonaAvatarImage("知墨", null, Modifier.size(30.dp))
                                Text(
                                    if (state.autonomy.annotationNotice == ProactiveAnnotationNotice.FAST_MODEL)
                                        "还挺有意思，你怎么看？" else "知墨 写了 3 条段评",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            MoReadSection(
                title = "前文与上下文",
                footer = "每条段评自动查找前文章节的相关原文和已有梗概，最多读取到目标段落。" +
                    "预算包含本章正文、前文片段和梗概，不含角色设定与写作提示词；按字符计算，实际用量可能更少。" +
                    "本地检索不增加模型调用，提高预算会增加单次发送量。"
            ) {
                MoReadBlock {
                    MoReadSegmented(options = AnnotationContextMode.entries.toList(), selected = editing.context.mode,
                        onSelect = { commit(editing.copy(context = editing.context.copy(mode = it))) },
                        enabled = bookId == null || perBookActive,
                        label = { it.label })
                }
                MoReadValueRow(title = "阅读资料上限", value = "每次 "+ editing.context.budgetChars + " 字符")
                if (editing.context.mode == AnnotationContextMode.CUSTOM) {
                    MoReadBlock {
                        MoReadSlider(label = "自定义上限", valueText = editing.context.budgetChars.toString(),
                            value = editing.context.budgetChars.toFloat(),
                            range = ProactiveAnnotationContextSettings.MIN_CHARS.toFloat()..ProactiveAnnotationContextSettings.MAX_CHARS.toFloat(),
                            step = 1_000f,
                            onValueChange = { if (bookId == null || perBookActive) {
                                commit(editing.copy(context = editing.context.copy(customChars = it.toInt()).normalized()))
                            } })
                    }
                }
            }
        }
        item {
            MoReadSection(
                title = "每章条数",
                footer = "下限只是写给模型的请求：本章确实没有值得回应的地方时，它仍然可以少给几条。" +
                    "上限选「不限制」时会逐个处理候选段落；长章会有更多调用，仍受每日上限约束。超长段落会分段处理。"
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
                footer = "按自然日计，跨天自动归零。同一角色与正文版本完成后不重复生成；提高数量上限不会为已完成章节补生成。失败最多两轮，切换设置或离开不占失败次数，已完成段落不重复。"
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
                // 用完后段评会静默停下；这一行是用户唯一能自己看出原因的地方。
                MoReadValueRow(
                    title = "今日已生成",
                    value = if (editing.dailyUnlimited) "$usedToday 条"
                    else "$usedToday / ${editing.dailyMax} 条" +
                        if (usedToday >= editing.dailyMax) "（已用完，明天恢复）" else ""
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
    unit: String = "条",
    onValueChange: (Int) -> Unit
) {
    val steps = ProactiveAnnotationLimitSteps.steps(from, to, allowUnlimited)
    val index = ProactiveAnnotationLimitSteps.indexOf(steps, value)
    MoReadBlock {
        MoReadSlider(
            label = label,
            valueText = ProactiveAnnotationLimitSteps.label(
                ProactiveAnnotationLimitSteps.valueAt(steps, index), unit
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
