package com.redmi9.offlineai

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Resumable network-constrained worker that researches pending questions
 * and caches sources for offline use. It does not mark fetched material as
 * user-confirmed; confirmation remains an explicit in-chat action.
 */
class TriageResearchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val store = KnowledgeStore(applicationContext)
            val client = TopicResearchClient(store)
            val researched = client.processPendingTriage(limit = 25)
            Log.d(TAG, "Background triage research completed; successful topics=$researched")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Background triage research failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "TriageResearchWorker"
        private const val UNIQUE_WORK_NAME = "mr_red_triage_research"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<TriageResearchWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
