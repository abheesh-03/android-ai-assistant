package com.sai.mobileaiassistant.data.local

import com.sai.mobileaiassistant.ChatMessage
import com.sai.mobileaiassistant.MessageRole

interface MessageHistoryStore {
    suspend fun loadMessages(): List<ChatMessage>
    suspend fun saveMessage(message: ChatMessage)
    suspend fun clearMessages()
}

object NoOpMessageHistoryStore : MessageHistoryStore {
    override suspend fun loadMessages(): List<ChatMessage> = emptyList()

    override suspend fun saveMessage(message: ChatMessage) = Unit

    override suspend fun clearMessages() = Unit
}

class RoomMessageHistoryStore(
    private val dao: MessageDao
) : MessageHistoryStore {

    override suspend fun loadMessages(): List<ChatMessage> {
        return dao.getMessages().mapNotNull { entity ->
            val role = MessageRole.entries.firstOrNull {
                it.name == entity.role
            } ?: return@mapNotNull null

            ChatMessage(
                id = entity.id,
                role = role,
                content = entity.content
            )
        }
    }

    override suspend fun saveMessage(message: ChatMessage) {
        dao.insertMessage(
            MessageEntity(
                id = message.id,
                role = message.role.name,
                content = message.content,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun clearMessages() {
        dao.clearMessages()
    }
}
