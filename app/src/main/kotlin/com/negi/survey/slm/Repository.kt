package com.negi.survey.slm
import kotlinx.coroutines.flow.Flow
interface Repository {
    suspend fun request(prompt: String): Flow<String>
    fun buildPrompt(userPrompt: String): String
}
