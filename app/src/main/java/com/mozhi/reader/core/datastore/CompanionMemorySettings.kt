package com.mozhi.reader.core.datastore

/**
 * 伴读记忆的范围开关（Memory 2.0 第 5 节）。
 *
 * 三个开关都默认开：记忆是被动能力，不会主动打扰用户，与「主动性功能默认关」的原则
 * 不冲突；而现状本就是跨书召回，默认关闭等于把已有行为悄悄砍掉。
 *
 * 关闭 ≠ 删除：既有记忆一律保留，只是不参与检索，重新打开即恢复。
 */
data class CompanionMemorySettings(
    /** 总开关。关＝不固化、不召回、不注入画像（会话内的前情提要是另一套机制，不受此控）。 */
    val longTermEnabled: Boolean = true,
    /** 跨书记忆。关＝召回收窄到当前书，画像里也不写「一起读过哪些书」。 */
    val crossBookEnabled: Boolean = true,
    /** 跨书对话检索。关＝recall_memory 不再命中其他书会话沉淀下来的记忆。 */
    val crossBookChatSearchEnabled: Boolean = true
)
