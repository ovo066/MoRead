package com.mozhi.reader.core.text

import com.mozhi.reader.core.datastore.ChineseConversionMode
import javax.inject.Inject
import javax.inject.Singleton
import openccjava.OpenCC
import openccjava.OpenccConfig

@Singleton
class ChineseTextConverter @Inject constructor() {
    fun convert(text: String, mode: ChineseConversionMode): String = when (mode) {
        ChineseConversionMode.OFF -> text
        ChineseConversionMode.TW2SP -> OpenCC.convert(text, OpenccConfig.TW2SP)
        ChineseConversionMode.S2TWP -> OpenCC.convert(text, OpenccConfig.S2TWP)
    }

    fun retarget(
        text: String,
        from: ChineseConversionMode,
        to: ChineseConversionMode
    ): String = when (to) {
        ChineseConversionMode.TW2SP -> convert(text, ChineseConversionMode.TW2SP)
        ChineseConversionMode.S2TWP -> convert(text, ChineseConversionMode.S2TWP)
        ChineseConversionMode.OFF -> when (from) {
            ChineseConversionMode.OFF -> text
            ChineseConversionMode.TW2SP -> convert(text, ChineseConversionMode.S2TWP)
            ChineseConversionMode.S2TWP -> convert(text, ChineseConversionMode.TW2SP)
        }
    }

    fun warmUp() {
        convert("滑鼠", ChineseConversionMode.TW2SP)
        convert("鼠标", ChineseConversionMode.S2TWP)
    }
}
