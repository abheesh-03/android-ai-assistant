package com.sai.mobileaiassistant.data

import com.google.gson.JsonParseException
import com.sai.mobileaiassistant.ChatMessage
import com.sai.mobileaiassistant.MessageRole
import com.sai.mobileaiassistant.data.remote.ApiService
import com.sai.mobileaiassistant.data.remote.model.ChatRequest
import com.sai.mobileaiassistant.data.remote.model.ChatResponse
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AssistantRepositoryTest {

    private class FakeApiService(
        private val handler: suspend (ChatRequest) -> ChatResponse
    ) : ApiService {

        override suspend fun chat(
            request: ChatRequest
        ): ChatResponse {
            return handler(request)
        }
    }

    private fun errorBody() =
        "".toResponseBody(
            "application/json".toMediaType()
        )

    private fun singleUserMessage(
        content: String = "test input"
    ): List<ChatMessage> {
        return listOf(
            ChatMessage(
                id = "user-1",
                role = MessageRole.USER,
                content = content
            )
        )
    }

    private suspend fun assertMappedMessage(
        expectedMessage: String,
        api: ApiService
    ) {
        val repository = AssistantRepository(api)

        try {
            repository.sendMessages(
                singleUserMessage()
            )

            fail("Expected an exception but none was thrown")
        } catch (e: Exception) {
            assertEquals(
                expectedMessage,
                e.message
            )
        }
    }

    @Test
    fun `successful response returns response text`() =
        runTest {
            val api = FakeApiService {
                ChatResponse(
                    response = "Hello world",
                    requestId = "req-1",
                    latencyMs = 42
                )
            }

            val repository =
                AssistantRepository(api)

            val result =
                repository.sendMessages(
                    singleUserMessage("hi")
                )

            assertEquals(
                "Hello world",
                result
            )
        }

    @Test
    fun `conversation messages are mapped to backend roles in order`() =
        runTest {
            var capturedRequest: ChatRequest? = null

            val api = FakeApiService { request ->
                capturedRequest = request

                ChatResponse(
                    response = "answer",
                    requestId = "req-1",
                    latencyMs = 10
                )
            }

            val repository =
                AssistantRepository(api)

            repository.sendMessages(
                listOf(
                    ChatMessage(
                        id = "1",
                        role = MessageRole.USER,
                        content = "Explain coroutines"
                    ),
                    ChatMessage(
                        id = "2",
                        role = MessageRole.ASSISTANT,
                        content = "Coroutines support async work."
                    ),
                    ChatMessage(
                        id = "3",
                        role = MessageRole.USER,
                        content = "Show me an example"
                    )
                )
            )

            val messages =
                capturedRequest!!.messages

            assertEquals(
                3,
                messages.size
            )

            assertEquals(
                "user",
                messages[0].role
            )
            assertEquals(
                "Explain coroutines",
                messages[0].content
            )

            assertEquals(
                "assistant",
                messages[1].role
            )
            assertEquals(
                "Coroutines support async work.",
                messages[1].content
            )

            assertEquals(
                "user",
                messages[2].role
            )
            assertEquals(
                "Show me an example",
                messages[2].content
            )
        }

    @Test
    fun `repository sends only newest eleven context messages`() =
        runTest {
            var capturedRequest: ChatRequest? = null

            val api = FakeApiService { request ->
                capturedRequest = request

                ChatResponse(
                    response = "answer",
                    requestId = "req-1",
                    latencyMs = 10
                )
            }

            val repository =
                AssistantRepository(api)

            val conversation =
                (0..12).map { index ->
                    ChatMessage(
                        id = "message-$index",
                        role = if (index % 2 == 0) {
                            MessageRole.USER
                        } else {
                            MessageRole.ASSISTANT
                        },
                        content = "message $index"
                    )
                }

            repository.sendMessages(
                conversation
            )

            val messages =
                capturedRequest!!.messages

            assertEquals(
                11,
                messages.size
            )

            assertEquals(
                "message 2",
                messages.first().content
            )

            assertEquals(
                "message 12",
                messages.last().content
            )

            assertEquals(
                "user",
                messages.first().role
            )

            assertEquals(
                "user",
                messages.last().role
            )
        }

    @Test
    fun `HTTP 500 maps to service unavailable message`() =
        runTest {
            val api = FakeApiService {
                throw HttpException(
                    Response.error<ChatResponse>(
                        500,
                        errorBody()
                    )
                )
            }

            assertMappedMessage(
                "The AI service is temporarily unavailable. Please try again.",
                api
            )
        }

    @Test
    fun `HTTP 503 maps to service unavailable message`() =
        runTest {
            val api = FakeApiService {
                throw HttpException(
                    Response.error<ChatResponse>(
                        503,
                        errorBody()
                    )
                )
            }

            assertMappedMessage(
                "The AI service is temporarily unavailable. Please try again.",
                api
            )
        }

    @Test
    fun `HTTP 400 maps to generic error message`() =
        runTest {
            val api = FakeApiService {
                throw HttpException(
                    Response.error<ChatResponse>(
                        400,
                        errorBody()
                    )
                )
            }

            assertMappedMessage(
                "Something went wrong. Please try again.",
                api
            )
        }

    @Test
    fun `SocketTimeoutException maps to timeout message`() =
        runTest {
            val api = FakeApiService {
                throw SocketTimeoutException(
                    "connect timed out"
                )
            }

            assertMappedMessage(
                "The request took too long. Please try again.",
                api
            )
        }

    @Test
    fun `IOException maps to connection error message`() =
        runTest {
            val api = FakeApiService {
                throw IOException(
                    "Connection refused"
                )
            }

            assertMappedMessage(
                "Unable to connect. Check your network and try again.",
                api
            )
        }

    @Test
    fun `JsonParseException maps to invalid response message`() =
        runTest {
            val api = FakeApiService {
                throw JsonParseException(
                    "Unexpected token"
                )
            }

            assertMappedMessage(
                "Received an invalid response. Please try again.",
                api
            )
        }

    @Test
    fun `unexpected exception maps to generic error message`() =
        runTest {
            val api = FakeApiService {
                throw RuntimeException(
                    "something unexpected"
                )
            }

            assertMappedMessage(
                "Something went wrong. Please try again.",
                api
            )
        }

    @Test
    fun `cancellation is propagated instead of mapped to network error`() =
        runTest {
            val api = FakeApiService {
                throw CancellationException(
                    "cancelled"
                )
            }

            val repository =
                AssistantRepository(api)

            try {
                repository.sendMessages(
                    singleUserMessage()
                )

                fail(
                    "Expected CancellationException"
                )
            } catch (
                e: CancellationException
            ) {
                assertEquals(
                    "cancelled",
                    e.message
                )
            }
        }
}
