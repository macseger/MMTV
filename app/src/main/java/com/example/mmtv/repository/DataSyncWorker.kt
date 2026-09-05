package com.example.mmtv.repository

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.mmtv.api.ApiClient
import com.example.mmtv.api.SessionManager
import com.example.mmtv.database.MediaDatabase

class DataSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val sessionManager = SessionManager(context)
        val loginInfo = sessionManager.getLogin() ?: return Result.success()
        
        val (host, user, pass) = loginInfo
        val database = MediaDatabase.getDatabase(context)
        val repository = MediaRepository(ApiClient.getClient(host), database.mediaDao(), context)

        return try {
            Log.d("DataSyncWorker", "Starting background sync...")
            
            if (sessionManager.getSyncOnlyLive()) {
                Log.d("DataSyncWorker", "Syncing only Live channels as per setting")
                repository.syncLiveChannels(user, pass)
            } else {
                Log.d("DataSyncWorker", "Syncing full library")
                repository.syncLibrary(user, pass)
            }

            repository.fetchAndStoreEpg(user, pass)
            repository.resolveLiveIcons()
            
            Log.d("DataSyncWorker", "Background sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e("DataSyncWorker", "Error during background sync", e)
            Result.retry()
        }
    }
}
