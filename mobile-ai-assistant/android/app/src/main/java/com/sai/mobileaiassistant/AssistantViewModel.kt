package com.sai.mobileaiassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sai.mobileaiassistant.data.AssistantRepository
import com.sai.mobileaiassistant.data.MessageRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AssistantViewModel(
    private val repository: MessageRepository = AssistantRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var sendJob: Job? = null

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

        _uiState.update {
            it.copy(
                input = "",
                messages = it.messages + userMessage,
                isLoading = true,
                error = null
            )
        }

        sendJob = viewModelScope.launch {
            try {
                val result = repository.sendMessage(prompt)

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Something went wrong. Please try again."
                    )
                }
            }
        }
    }

    fun clear() {
        sendJob?.cancel()
        sendJob = null
        _uiState.value = AssistantUiState()
    }
}
