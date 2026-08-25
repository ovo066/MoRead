package com.mozhi.reader.core.datastore

/**
 * 伴读记忆的范围开关（Memory 2.0 第 5 节）。
 *
 * 长期记忆默认开启，但跨书能力默认关闭。一本书内的事实、人物和阅读上下文必须
 * 保持在本书作用域，只有用户明确开启跨书能力后才允许跨书召回。
 *
 * 关闭 ≠ 删除：既有记忆一律保留，只是不参与检索，重新打开即恢复。
 */
data class CompanionMemorySettings(
    /** 总开关。关＝不固化、不召回、不注入画像（会话内的前情提要是另一套机制，不受此控）。 */
    val longTermEnabled: Boolean = true,
    /** 跨书记忆。关＝召回收窄到当前书，画像里也不写「一起读过哪些书」。 */
    val crossBookEnabled: Boolean = false,
    /** 跨书对话检索。关＝recall_memory 不再命中其他书会话沉淀下来的记忆。 */
    val crossBookChatSearchEnabled: Boolean = false
)
