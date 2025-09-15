package com.negi.surveynav.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SurveyConfig(
    val prompt: PromptConfig,
    val graph: GraphConfig
)

@Serializable
data class PromptConfig(
    val template: String,
    val language: String? = null,
    val rules: List<String> = emptyList()
)

@Serializable
data class GraphConfig(
    val startId: String,
    val nodes: List<NodeDTO>
)

@Serializable
data class NodeDTO(
    val id: String,
    val type: String,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
)
