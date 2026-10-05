package com.sai.mobileaiassistant

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sai.mobileaiassistant.ui.theme.MobileAIAssistantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MobileAIAssistantTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AssistantScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun AssistantScreen(
    modifier: Modifier = Modifier,
    viewModel: AssistantViewModel = viewModel()
) {
    val context = LocalContext.current
    val localNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(
                context,
                localNetworkPermission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(localNetworkPermission)
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var shouldAutoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItem =
                layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1

            totalItems == 0 || lastVisibleItem >= totalItems - 2
        }.collect { nearBottom ->
            shouldAutoScroll = nearBottom
        }
    }

    LaunchedEffect(uiState.messages.size, uiState.isLoading) {
        if (shouldAutoScroll) {
            val itemCount =
                uiState.messages.size + if (uiState.isLoading) 1 else 0

            if (itemCount > 0) {
                listState.animateScrollToItem(itemCount - 1)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "AI Assistant",
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    text = "Ask anything",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = viewModel::clear,
                enabled = uiState.messages.isNotEmpty() || uiState.isLoading
            ) {
                Text("New chat")
            }
        }

        if (uiState.messages.isEmpty() && !uiState.isLoading) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Start a conversation",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Ask a question and your conversation will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { message -> message.id }
                ) { message ->
                    MessageBubble(message)
                }

                if (uiState.isLoading) {
                    item(key = "loading") {
                        AssistantLoadingBubble()
                    }
                }
            }
        }

        uiState.error?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 6.dp
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = uiState.input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Message AI Assistant")
                },
                maxLines = 4,
                enabled = !uiState.isLoading
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = viewModel::send,
                enabled = uiState.input.isNotBlank() &&
                    !uiState.isLoading
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage
) {
    val isUser = message.role == MessageRole.USER

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) {
            Alignment.CenterEnd
        } else {
            Alignment.CenterStart
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = RoundedCornerShape(18.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                ),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun AssistantLoadingBubble() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(18.dp)
                        .height(18.dp),
                    strokeWidth = 2.dp
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "Thinking...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AssistantScreenPreview() {
    MobileAIAssistantTheme {
        AssistantScreen()
    }
}
