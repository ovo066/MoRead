package com.mozhi.reader.core.i18n

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * 跨层传递的界面文案：ViewModel、Worker 与仓库只携带资源编号和参数，
 * 到界面（或通知等真正显示的地方）才按当前语言解析。
 *
 * 不要把解析后的字符串存进长生命周期状态或数据库——切换语言后它不会跟着变。
 * [Raw] 只用于本来就不需要翻译的内容（书名、用户输入、服务端原样返回的错误）。
 */
sealed interface UiText {
    fun resolve(resources: Resources): String

    data class Raw(val value: String) : UiText {
        override fun resolve(resources: Resources): String = value
    }

    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(resources: Resources): String =
            if (args.isEmpty()) resources.getString(id)
            else resources.getString(id, *args.map { it.resolveArg(resources) }.toTypedArray())
    }

    data class Plural(
        @param:PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = listOf(count)
    ) : UiText {
        override fun resolve(resources: Resources): String =
            resources.getQuantityString(id, count, *args.map { it.resolveArg(resources) }.toTypedArray())
    }

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Resource(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
        fun raw(value: String): UiText = Raw(value)
    }
}

fun UiText.resolve(context: Context): String = resolve(context.resources)

/** 参数本身也可以是 [UiText]，便于拼「失败：{原因}」这类嵌套消息。 */
private fun Any.resolveArg(resources: Resources): Any = if (this is UiText) resolve(resources) else this
