package com.sai.mobileaiassistant

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sai.mobileaiassistant.data.local.AppDatabase
import com.sai.mobileaiassistant.data.local.RoomMessageHistoryStore

class AssistantViewModelFactory(
    context: Context
) : ViewModelProvider.Factory {

    private val applicationContext = context.applicationContext

    override fun <T : ViewModel> create(
        modelClass: Class<T>
    ): T {
        if (modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
            val database = AppDatabase.getInstance(applicationContext)

            @Suppress("UNCHECKED_CAST")
            return AssistantViewModel(
                historyStore = RoomMessageHistoryStore(
                    database.messageDao()
                )
            ) as T
        }

        throw IllegalArgumentException(
            "Unknown ViewModel class: ${modelClass.name}"
        )
    }
}
