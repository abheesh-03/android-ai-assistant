package com.sai.mobileaiassistant.data.remote.model

data class ChatMessageRequest(
    val role: String,
    val content: String
)

data class ChatRequest(
    val messages: List<ChatMessageRequest>
)
