package com.sai.mobileaiassistant.data

import com.sai.mobileaiassistant.ChatMessage

interface MessageRepository {
    suspend fun sendMessages(messages: List<ChatMessage>): String
}
