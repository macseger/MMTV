package com.example.mmtv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.mmtv.api.SessionManager
import com.example.mmtv.database.MediaDatabase
import com.example.mmtv.repository.MediaRepository

class MediaViewModelFactory(
    private val repository: MediaRepository,
    private val sessionManager: SessionManager,
    private val database: MediaDatabase,
    private val context: android.content.Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewModel(repository, sessionManager, database, context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
