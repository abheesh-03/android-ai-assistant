package com.sai.mobileaiassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sai.mobileaiassistant.data.AssistantRepository
import com.sai.mobileaiassistant.data.MessageRepository
import com.sai.mobileaiassistant.data.local.MessageHistoryStore
import com.sai.mobileaiassistant.data.local.NoOpMessageHistoryStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AssistantViewModel(
    private val repository: MessageRepository = AssistantRepository(),
    private val historyStore: MessageHistoryStore = NoOpMessageHistoryStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var sendJob: Job? = null
    private var restoreJob: Job? = null
    private var clearJob: Job? = null

    init {
        restoreJob = viewModelScope.launch {
            try {
                val persistedMessages =
                    historyStore.loadMessages()

                _uiState.update { current ->
                    if (current.messages.isEmpty()) {
                        current.copy(
                            messages = persistedMessages
                        )
                    } else {
                        current
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Local persistence failure should not make
                // the network assistant unusable.
            }
        }
    }

    fun onInputChange(input: String) {
        _uiState.update {
            it.copy(
                input = input,
                error = null
            )
        }
    }

    fun send() {
        val currentState = _uiState.value

        if (currentState.isLoading) {
            return
        }

        val prompt = currentState.input.trim()

        if (prompt.isEmpty()) {
            return
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = prompt
        )

        val conversationForRequest =
            currentState.messages + userMessage

        _uiState.update {
            it.copy(
                input = "",
                messages = conversationForRequest,
                isLoading = true,
                error = null
            )
        }

        sendJob = viewModelScope.launch {
            try {
                clearJob?.join()

                persistSafely(userMessage)

                val result = repository.sendMessages(
                    conversationForRequest
                )

                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = result
                )

                _uiState.update {
                    it.copy(
                        messages = it.messages + assistantMessage,
                        isLoading = false,
                        error = null
                    )
                }

                persistSafely(assistantMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message
                            ?: "Something went wrong. Please try again."
                    )
                }
            }
        }
    }

    fun clear() {
        restoreJob?.cancel()
        restoreJob = null

        sendJob?.cancel()
        sendJob = null

        _uiState.value = AssistantUiState()

        clearJob = viewModelScope.launch {
            try {
                historyStore.clearMessages()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the UI usable even if local cleanup fails.
            }
        }
    }

    private suspend fun persistSafely(
        message: ChatMessage
    ) {
        try {
            historyStore.saveMessage(message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Network chat can still continue if local
            // persistence temporarily fails.
        }
    }
}
