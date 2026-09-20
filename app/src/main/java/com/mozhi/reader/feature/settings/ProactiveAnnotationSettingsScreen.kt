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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

    var page by rememberSaveable(bookId) { mutableStateOf("overview") }
    var help by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable(bookId) { mutableStateOf(false) }
    val pages = rememberSaveableStateHolder()
    val editable = bookId == null || perBookActive
    val title = when (page) {
        "personas" -> "参与段评的伴读"
        "timing" -> "生成方式"
        "context" -> "前文与上下文"
        "limits" -> "数量与额度"
        "notice" -> "伴读弹幕"
        else -> if (bookId == null) "随读段评" else "本书随读段评"
    }
    BackHandler(page != "overview") { page = "overview" }
    pages.SaveableStateProvider(page) {
        MoReadSecondaryPage(
            title = title,
            onBack = { if (page == "overview") onBack() else page = "overview" },
            listState = rememberLazyListState(),
            actions = { IconButton(onClick = { help = true }) { Icon(Icons.Outlined.Info, "段评说明") } }
        ) {
            if (!state.isLoaded) return@MoReadSecondaryPage
            if (page == "overview") {
                if (bookId == null) item {
                    MoReadSection(title = "总开关") {
                        MoReadSwitchRow(icon = Icons.Outlined.BorderColor, title = "随读段评",
                            subtitle = "阅读时自动留下批注 · 使用已配置的 AI",
                            checked = state.autonomy.proactiveAnnotationsEnabled, onCheckedChange = onSetEnabled)
                    }
                } else item {
                    MoReadSection(title = "适用范围") {
                        MoReadSwitchRow(icon = Icons.Outlined.BorderColor, title = "本书单独设置",
                            subtitle = if (perBookActive) "仅影响本书的生成方式与额度" else "跟随全局设置",
                            checked = perBookActive, onCheckedChange = { enabled ->
                                onSetBookLimits(bookId, if (enabled) BookProactiveAnnotationLimits(true, editing) else null)
                            })
                    }
                }
                item {
                    MoReadSection(title = "生成与用量") {
                        MoReadRow(title = "生成方式", subtitle = if (editing.timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY)
                            "进入章节预生成 · 额外提前 ${editing.aheadChapters} 章" else "读完后生成上一章", onClick = { page = "timing" })
                        MoReadRowDivider()
                        MoReadRow(title = "数量与额度", subtitle = editing.summary(), onClick = { page = "limits" })
                        MoReadRowDivider()
                        MoReadValueRow(title = "今日已生成", value = if (editing.dailyUnlimited) "$usedToday 条"
                            else "$usedToday / ${editing.dailyMax} 条" + if (usedToday >= editing.dailyMax) " · 明天恢复" else "")
                    }
                }
                if (bookId == null) item {
                    MoReadSection(title = "伴读与表达") {
                        val selected = state.autonomy.annotationPersonaIds
                        MoReadRow(icon = Icons.Outlined.Groups, title = "参与段评的伴读",
                            subtitle = if (selected.isEmpty()) "跟随当前伴读" else "已选择 ${selected.size} 位伴读",
                            onClick = { page = "personas" })
                        MoReadRowDivider()
                        MoReadRow(icon = Icons.Outlined.EditNote, title = "主动段评提示词",
                            subtitle = "${state.autonomy.annotationPrompts.count { it.enabled }} 条启用", onClick = onOpenPrompts)
                        MoReadRowDivider()
                        MoReadRow(title = "伴读弹幕", subtitle = noticeLabel(state.autonomy.annotationNotice), onClick = { page = "notice" })
                    }
                }
                item {
                    MoReadSection(title = "更多设置") {
                        MoReadRow(title = "前文与上下文", subtitle = "${editing.context.mode.label} · 每次 ${editing.context.budgetChars} 字符",
                            onClick = { page = "context" })
                    }
                }
            }
            if (page == "personas" && bookId == null) item {
                val selected = state.autonomy.annotationPersonaIds
                MoReadSection(title = "选择伴读", footer = "可多选；每日额度由所有伴读共享。") {
                    MoReadRow(title = "跟随当前伴读", subtitle = state.personas.firstOrNull { it.id == state.activePersonaId }?.name ?: "尚未选择伴读",
                        onClick = { onSetPersonas(emptySet()) }, trailing = { RadioButton(selected.isEmpty(), { onSetPersonas(emptySet()) }) })
                    state.personas.forEach { persona ->
                        MoReadRowDivider()
                        MoReadRow(title = persona.name, onClick = { onTogglePersona(persona.id) }, trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PersonaAvatarImage(persona.name, persona.avatarPath, Modifier.size(32.dp))
                                Checkbox(persona.id in selected, { onTogglePersona(persona.id) })
                            }
                        })
                    }
                }
            }
            if (page != "overview" && !editable && page in setOf("timing", "context", "limits")) item {
                MoReadSection(title = "跟随全局") {
                    MoReadRow(title = "开启本书单独设置后可修改", onClick = { page = "overview" })
                }
            }
            if (page == "timing") {
                item {
                    MoReadSection(title = "何时生成", footer = "每条段评会调用一次模型。") {
                        ProactiveAnnotationTiming.entries.forEach { timing ->
                            MoReadRow(title = if (timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY) "进入章节预生成" else "读完后生成上一章",
                                subtitle = if (timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY) "读到对应段落时显示" else "只读取已读正文",
                                onClick = { if (editable) commit(editing.copy(timing = timing)) }, trailing = {
                                    RadioButton(editing.timing == timing, { if (editable) commit(editing.copy(timing = timing)) }, enabled = editable)
                                })
                        }
                    }
                }
                if (editing.timing == ProactiveAnnotationTiming.ON_CHAPTER_ENTRY) item {
                    MoReadSection(title = "提前生成") {
                        MoReadRow(title = "额外提前 ${editing.aheadChapters} 章", subtitle = if (advanced) "收起设置" else "展开设置",
                            onClick = { advanced = !advanced })
                        if (advanced) {
                            LimitSlider("额外提前", editing.aheadChapters, 0, 5, false, editable, "章") {
                                commit(editing.copy(aheadChapters = it))
                            }
                            MoReadValueRow(title = "提前范围", value = "0 = 仅本章")
                        }
                    }
                }
            }
            if (page == "context") item {
                MoReadSection(title = "阅读资料预算", footer = "只检索目标段落之前的正文。") {
                    MoReadBlock {
                        MoReadSegmented(options = AnnotationContextMode.entries.toList(), selected = editing.context.mode,
                            onSelect = { commit(editing.copy(context = editing.context.copy(mode = it))) }, enabled = editable, label = { it.label })
                    }
                    MoReadValueRow(title = "阅读资料上限", value = "每次 ${editing.context.budgetChars} 字符")
                    if (editing.context.mode == AnnotationContextMode.CUSTOM) MoReadBlock {
                        MoReadSlider(label = "自定义上限", valueText = editing.context.budgetChars.toString(), value = editing.context.budgetChars.toFloat(),
                            range = ProactiveAnnotationContextSettings.MIN_CHARS.toFloat()..ProactiveAnnotationContextSettings.MAX_CHARS.toFloat(), step = 1_000f,
                            onValueChange = { if (editable) commit(editing.copy(context = editing.context.copy(customChars = it.toInt()).normalized())) })
                    }
                }
            }
            if (page == "limits") {
                item {
                    MoReadSection(title = "每章条数", footer = "每位伴读分别生效；下限是期望数量。") {
                        LimitSlider("下限", editing.minPerChapter, 0,
                            if (editing.chapterUnlimited) ProactiveAnnotationLimits.MAX_PER_CHAPTER else editing.maxPerChapter, false, editable) {
                            commit(editing.copy(minPerChapter = it).normalized())
                        }
                        MoReadRowDivider()
                        LimitSlider("上限", editing.maxPerChapter, 1, ProactiveAnnotationLimits.MAX_PER_CHAPTER, true, editable) {
                            commit(editing.copy(maxPerChapter = it).normalized())
                        }
                    }
                }
                item {
                    MoReadSection(title = "每日上限", footer = "按自然日归零；达到上限后暂停生成。") {
                        LimitSlider("段评", editing.dailyMax, 1, ProactiveAnnotationLimits.MAX_DAILY, true, editable) { commit(editing.copy(dailyMax = it).normalized()) }
                        MoReadValueRow(title = "今日已生成", value = "$usedToday 条")
                        MoReadRowDivider()
                        LimitSlider("语音", editing.dailyVoiceMax, 0, ProactiveAnnotationLimits.MAX_DAILY, true, editable) { commit(editing.copy(dailyVoiceMax = it).normalized()) }
                        MoReadRowDivider()
                        LimitSlider("插图", editing.dailyImageMax, 0, ProactiveAnnotationLimits.MAX_DAILY, true, editable) { commit(editing.copy(dailyImageMax = it).normalized()) }
                    }
                }
            }
            if (page == "notice" && bookId == null) item {
                MoReadSection(title = "提示方式", footer = "5 秒自动收起，仅提示已读到的段评。") {
                    ProactiveAnnotationNotice.entries.forEach { mode ->
                        MoReadRow(title = noticeLabel(mode), subtitle = when (mode) {
                            ProactiveAnnotationNotice.OFF -> "静默写入"
                            ProactiveAnnotationNotice.BUILT_IN -> "不额外调用模型"
                            ProactiveAnnotationNotice.FAST_MODEL -> "每次额外一次快速模型调用"
                        }, onClick = { onSetNotice(mode) }, trailing = { RadioButton(state.autonomy.annotationNotice == mode, { onSetNotice(mode) }) })
                    }
                    if (state.autonomy.annotationNotice != ProactiveAnnotationNotice.OFF) MoReadBlock {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            PersonaAvatarImage("知墨", null, Modifier.size(30.dp))
                            Text(if (state.autonomy.annotationNotice == ProactiveAnnotationNotice.FAST_MODEL) "还挺有意思，你怎么看？" else "知墨 写了 3 条段评",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("段评说明") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(when (page) {
                "timing" -> "预生成会提前读取未读段落，但每条段评仅看到该段之前的内容。读到之前不会出现在聊天、工具或通知里。\n\n每条段评一次模型调用；使用主动段评分配，未配置时使用 cheap。额外提前的章节会提前消耗额度。"
                "context" -> "预算包含本章正文、前文片段和梗概，不含角色设定与写作提示词。按字符计算，实际发送量可能更少。\n\n本地检索不增加模型调用，提高预算会增加单次发送量。"
                "limits" -> "下限是给模型的请求，无合适内容时可以少生成。不限制上限时会逐个处理候选段落，仍受每日额度约束。\n\n同一角色与正文版本完成后不重复生成。提高上限不会给已完成章节补生成。失败最多两轮，切换设置或离开不占失败次数。"
                "notice" -> "快速模型根据角色性格说一句共读感想，不发送正文、段评或聊天记录。失败或超时回落为内置短句。"
                "personas" -> "每章条数对每位伴读分别生效；每日额度由所有伴读共享，并为后续伴读预留份额。未单独选择时跟随当前伴读。"
                else -> "开启随读段评会使用你配置的 AI 服务并消耗 API 额度。\n\n生成方式决定何时开始；数量与额度控制调用量；前文与上下文控制每次发送的阅读资料量。\n\n本书设置只覆盖本书的生成方式、上下文与额度，伴读和提示词使用全局设置。"
            })
        }
    }, confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
}

private fun noticeLabel(mode: ProactiveAnnotationNotice): String = when (mode) {
    ProactiveAnnotationNotice.OFF -> "不提示"
    ProactiveAnnotationNotice.BUILT_IN -> "内置提示"
    ProactiveAnnotationNotice.FAST_MODEL -> "快速模型 · 角色互动"
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
