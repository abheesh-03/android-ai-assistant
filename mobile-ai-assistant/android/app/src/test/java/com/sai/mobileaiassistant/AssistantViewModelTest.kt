package com.sai.mobileaiassistant

import com.sai.mobileaiassistant.data.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private class FakeSuccessRepository(
        private val answer: String
    ) : MessageRepository {
        override suspend fun sendMessage(message: String): String = answer
    }

    private class FakeFailureRepository(
        private val errorMessage: String
    ) : MessageRepository {
        override suspend fun sendMessage(message: String): String {
            throw Exception(errorMessage)
        }
    }

    private class FakeSlowRepository : MessageRepository {
        private val deferred = CompletableDeferred<String>()

        fun complete(value: String) {
            deferred.complete(value)
        }

        override suspend fun sendMessage(message: String): String {
            return deferred.await()
        }
    }

    private class CountingSlowRepository : MessageRepository {
        var callCount = 0
            private set

        private val deferred = CompletableDeferred<String>()

        override suspend fun sendMessage(message: String): String {
            callCount++
            return deferred.await()
        }

        fun complete(value: String) {
            deferred.complete(value)
        }
    }

    private class CancellableRepository : MessageRepository {
        var wasCancelled = false
            private set

        private val deferred = CompletableDeferred<String>()

        override suspend fun sendMessage(message: String): String {
            return try {
                deferred.await()
            } catch (e: CancellationException) {
                wasCancelled = true
                throw e
            }
        }
    }

    @Test
    fun `initial state contains an empty conversation`() {
        val vm = AssistantViewModel(FakeSuccessRepository(""))

        assertEquals("", vm.uiState.value.input)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertFalse(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `input update changes uiState input`() {
        val vm = AssistantViewModel(FakeSuccessRepository(""))

        vm.onInputChange("hello")

        assertEquals("hello", vm.uiState.value.input)
    }

    @Test
    fun `sending valid prompt adds user and assistant messages in order`() {
        val vm = AssistantViewModel(FakeSuccessRepository("AI reply"))

        vm.onInputChange("Hello")
        vm.send()

        val messages = vm.uiState.value.messages

        assertEquals(2, messages.size)

        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("Hello", messages[0].content)

        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals("AI reply", messages[1].content)
    }

    @Test
    fun `input clears after submission`() {
        val vm = AssistantViewModel(FakeSuccessRepository("answer"))

        vm.onInputChange("question")
        vm.send()

        assertEquals("", vm.uiState.value.input)
    }

    @Test
    fun `successful send finishes with loading false and no error`() {
        val vm = AssistantViewModel(FakeSuccessRepository("answer"))

        vm.onInputChange("question")
        vm.send()

        assertFalse(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `send shows user message while assistant request is still in flight`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repo = FakeSlowRepository()
        val vm = AssistantViewModel(repo)

        vm.onInputChange("question")
        vm.send()

        assertTrue(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.messages.size)
        assertEquals(MessageRole.USER, vm.uiState.value.messages.single().role)
        assertEquals("question", vm.uiState.value.messages.single().content)

        runCurrent()

        repo.complete("the answer")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(2, vm.uiState.value.messages.size)
        assertEquals(
            MessageRole.ASSISTANT,
            vm.uiState.value.messages[1].role
        )
        assertEquals(
            "the answer",
            vm.uiState.value.messages[1].content
        )
    }

    @Test
    fun `duplicate send while request is active does not call repository twice`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repo = CountingSlowRepository()
        val vm = AssistantViewModel(repo)

        vm.onInputChange("first question")
        vm.send()

        runCurrent()

        vm.onInputChange("second question")
        vm.send()

        runCurrent()

        assertEquals(1, repo.callCount)
        assertEquals(1, vm.uiState.value.messages.size)

        repo.complete("answer")
        advanceUntilIdle()
    }

    @Test
    fun `blank prompt is ignored`() {
        val vm = AssistantViewModel(FakeSuccessRepository("should not appear"))

        vm.onInputChange("   ")
        vm.send()

        assertTrue(vm.uiState.value.messages.isEmpty())
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `failed send keeps user message and exposes error`() {
        val vm = AssistantViewModel(
            FakeFailureRepository(
                "Unable to connect. Check your network and try again."
            )
        )

        vm.onInputChange("question")
        vm.send()

        val state = vm.uiState.value

        assertEquals(1, state.messages.size)
        assertEquals(MessageRole.USER, state.messages.single().role)
        assertEquals("question", state.messages.single().content)
        assertEquals(
            "Unable to connect. Check your network and try again.",
            state.error
        )
        assertFalse(state.isLoading)
    }

    @Test
    fun `clear removes conversation and resets state`() {
        val vm = AssistantViewModel(FakeSuccessRepository("answer"))

        vm.onInputChange("question")
        vm.send()
        vm.clear()

        assertEquals(AssistantUiState(), vm.uiState.value)
    }

    @Test
    fun `clear cancels in flight request without exposing cancellation as error`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repo = CancellableRepository()
        val vm = AssistantViewModel(repo)

        vm.onInputChange("question")
        vm.send()

        runCurrent()

        assertTrue(vm.uiState.value.isLoading)

        vm.clear()
        advanceUntilIdle()

        assertTrue(repo.wasCancelled)
        assertEquals(AssistantUiState(), vm.uiState.value)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `changing input does not modify existing conversation messages`() {
        val vm = AssistantViewModel(FakeSuccessRepository("AI reply"))

        vm.onInputChange("first question")
        vm.send()

        val messagesBeforeInputChange = vm.uiState.value.messages

        vm.onInputChange("next question")

        assertEquals(messagesBeforeInputChange, vm.uiState.value.messages)
        assertEquals("next question", vm.uiState.value.input)
    }
}
