package com.sai.mobileaiassistant

data class AssistantUiState(
    val input: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
