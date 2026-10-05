package com.sai.mobileaiassistant.data

import com.google.gson.JsonParseException
import com.sai.mobileaiassistant.ChatMessage
import com.sai.mobileaiassistant.MessageRole
import com.sai.mobileaiassistant.data.remote.ApiService
import com.sai.mobileaiassistant.data.remote.RetrofitClient
import com.sai.mobileaiassistant.data.remote.model.ChatMessageRequest
import com.sai.mobileaiassistant.data.remote.model.ChatRequest
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

class AssistantRepository(
    private val api: ApiService = RetrofitClient.api
) : MessageRepository {

    override suspend fun sendMessages(
        messages: List<ChatMessage>
    ): String {
        val context = messages
            .takeLast(MAX_CONTEXT_MESSAGES)
            .map { message ->
                ChatMessageRequest(
                    role = when (message.role) {
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "assistant"
                    },
                    content = message.content
                )
            }

        return try {
            api.chat(
                ChatRequest(
                    messages = context
                )
            ).response
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw NetworkException(
                "The request took too long. Please try again."
            )
        } catch (e: IOException) {
            throw NetworkException(
                "Unable to connect. Check your network and try again."
            )
        } catch (e: HttpException) {
            val msg = if (e.code() in 500..599) {
                "The AI service is temporarily unavailable. Please try again."
            } else {
                "Something went wrong. Please try again."
            }

            throw NetworkException(msg)
        } catch (e: JsonParseException) {
            throw NetworkException(
                "Received an invalid response. Please try again."
            )
        } catch (e: Exception) {
            throw NetworkException(
                "Something went wrong. Please try again."
            )
        }
    }

    private companion object {
        const val MAX_CONTEXT_MESSAGES = 11
    }
}

private class NetworkException(
    message: String
) : Exception(message)
