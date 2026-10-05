# Mobile AI Assistant

Native Android conversational AI assistant built with Kotlin, Jetpack Compose, ViewModel/StateFlow, Room, Retrofit/OkHttp, FastAPI, and Anthropic Claude.

The Android client owns UI state and local conversation persistence. The FastAPI backend owns the Anthropic integration and keeps the model API key out of the APK.

## Architecture

```text
Jetpack Compose
      ↓
AssistantViewModel
      ↓
StateFlow<AssistantUiState>
      ↓
MessageRepository
      ↓
AssistantRepository
      ↓
Retrofit / OkHttp
      ↓
FastAPI
      ↓
Anthropic Claude