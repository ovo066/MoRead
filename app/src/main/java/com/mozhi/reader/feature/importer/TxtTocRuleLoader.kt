package com.mozhi.reader.feature.importer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class TxtTocRuleLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    val rules: List<TxtTocRule> by lazy {
        context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
            json.decodeFromString<List<TxtTocRule>>(reader.readText())
                .sortedBy(TxtTocRule::serialNumber)
        }
    }

    private companion object {
        const val ASSET_PATH = "txtTocRule.json"
    }
}
