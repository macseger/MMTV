package com.example.mmtv.repository

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.mmtv.api.ApiClient
import com.example.mmtv.api.SessionManager
import com.example.mmtv.database.MediaDatabase
import com.example.mmtv.util.StartupDiagnostics
import kotlinx.coroutines.withContext

class DataSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(StartupDiagnostics.Trigger("worker:$id")) {
        StartupDiagnostics.timed("worker", "work_id=$id attempt=$runAttemptCount") {
        val context = applicationContext
        val sessionManager = SessionManager(context)
        StartupDiagnostics.event("worker_state", "pending=${sessionManager.isSyncSelectionPending()} selection=${sessionManager.hasSyncSelection()}")
        if (!sessionManager.hasSyncSelection()) {
            StartupDiagnostics.event("worker_skip", "reason=no_selection")
            return@withContext Result.success()
        }
        val loginInfo = sessionManager.getLogin() ?: run {
            StartupDiagnostics.event("worker_skip", "reason=no_login")
            return@withContext Result.success()
        }
        
        val (host, user, pass) = loginInfo
        val database = MediaDatabase.getDatabase(context)
        val repository = MediaRepository(ApiClient.getClient(host), database.mediaDao(), context)

        try {
            StartupDiagnostics.rows(database.mediaDao(), "worker_before")
            Log.d("DataSyncWorker", "Starting background sync...")
            
            repository.syncLibrary(user, pass)

            repository.fetchAndStoreEpg(user, pass)
            repository.resolveLiveIcons()
            
            Log.d("DataSyncWorker", "Background sync completed successfully")
            StartupDiagnostics.event("worker_result", "result=success work_id=$id")
            Result.success()
        } catch (e: Exception) {
            Log.e("DataSyncWorker", "Error during background sync", e)
            StartupDiagnostics.event("worker_result", "result=retry work_id=$id error=${e.javaClass.simpleName}")
            Result.retry()
        } finally {
            StartupDiagnostics.rows(database.mediaDao(), "worker_after")
        }
        }
    }
}
