package com.negi.surveynav.config

import android.content.Context
import kotlinx.serialization.json.Json

object SurveyConfigLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    fun fromAssets(context: Context, fileName: String = "survey_config.json"): SurveyConfig {
        val text = context.assets.open(fileName).bufferedReader().use { it.readText() }
        return json.decodeFromString(SurveyConfig.serializer(), text)
    }
}
