package app.fieldcapture.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fieldcapture.FieldCaptureApp
import app.fieldcapture.store.JobStore
import app.fieldcapture.store.OutboxItem
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Result of offering one outbox item to the back office. */
enum class UploadOutcome {
  /** Stored (or already stored): delete the marker. */
  ACKED,
  /** Network/server trouble: keep it and retry later. */
  RETRY,
  /** The server will never accept this item (4xx). Move it aside; don't block the queue, don't delete. */
  REJECTED,
  /** Wrong or missing access code (401/403): a setting to fix, not a bad item. Keep everything queued. */
  UNAUTHORIZED,
}

class BackendClient(private val baseUrl: String, private val accessCode: String, private val store: JobStore) {
  private fun Request.Builder.auth(): Request.Builder =
      if (accessCode.isBlank()) this else header("Authorization", "Bearer $accessCode")

  private val http =
      OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .writeTimeout(60, TimeUnit.SECONDS)
          .readTimeout(60, TimeUnit.SECONDS)
          .build()

  /** POST /v1/jobs/{jobId}/items, multipart: item + meta (+ image, + stream_image). Idempotent by item id. */
  fun upload(item: OutboxItem): UploadOutcome {
    val meta = store.file(item.metaFile)
    if (!meta.exists()) return UploadOutcome.ACKED
    val body =
        MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("item", store.json.encodeToString(item))
            .addFormDataPart("meta", meta.name, meta.asRequestBody(JSON))
    item.imageFile?.let { path ->
      val img = store.file(path)
      if (img.exists()) body.addFormDataPart("image", img.name, img.asRequestBody(JPEG))
      val stream = store.file(path.replace(".jpg", "_stream.jpg"))
      if (stream.exists() && stream != img) body.addFormDataPart("stream_image", stream.name, stream.asRequestBody(JPEG))
    }
    val request =
        Request.Builder()
            .url("$baseUrl/v1/jobs/${item.jobId}/items")
            .header("Idempotency-Key", item.id)
            .auth()
            .post(body.build())
            .build()
    http.newCall(request).execute().use { resp ->
      return when {
        resp.isSuccessful || resp.code == 409 -> UploadOutcome.ACKED
        resp.code == 401 || resp.code == 403 -> UploadOutcome.UNAUTHORIZED
        resp.code == 408 || resp.code == 429 || resp.code >= 500 -> UploadOutcome.RETRY
        else -> UploadOutcome.REJECTED
      }
    }
  }

  fun ping(): Boolean =
      runCatching {
            http.newCall(Request.Builder().url("$baseUrl/healthz").get().build()).execute().use { it.isSuccessful }
          }
          .getOrDefault(false)

  /** For the settings screen: is the address reachable, and does it accept the access code? */
  fun check(): String =
      runCatching {
            if (!ping()) return@runCatching "Can't reach $baseUrl"
            http.newCall(Request.Builder().url("$baseUrl/v1/jobs").auth().get().build()).execute().use {
              when {
                it.isSuccessful -> "Connected"
                it.code == 401 || it.code == 403 -> "Reached, but the access code is wrong"
                else -> "Reached, but it answered ${it.code}"
              }
            }
          }
          .getOrElse { "Can't reach $baseUrl (${it.javaClass.simpleName})" }

  companion object {
    private val JSON = "application/json".toMediaType()
    private val JPEG = "image/jpeg".toMediaType()

    @Suppress("unused") private val EMPTY = ByteArray(0).toRequestBody(JSON)
  }
}

/**
 * Drains the outbox whenever there is a network. WorkManager persists the request across app
 * kills and reboots, holds it until connectivity returns (NetworkType.CONNECTED), and backs off
 * exponentially on failures.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result =
      withContext(Dispatchers.IO) {
        val app = applicationContext as FieldCaptureApp
        if (app.settings.simulateOffline) {
          app.events.log("upload", "simulated no-signal: ${app.store.pending.value} items held")
          return@withContext Result.retry()
        }
        val client = BackendClient(app.settings.backendUrl, app.settings.accessCode, app.store)
        repeat(MAX_ROUNDS) {
          val items = app.store.pendingItems()
          if (items.isEmpty()) return@withContext Result.success()
          for (item in items) {
            val outcome =
                try {
                  client.upload(item)
                } catch (e: IOException) {
                  app.events.log("upload", "no connection to back office (${e.javaClass.simpleName}); will retry")
                  return@withContext Result.retry()
                }
            when (outcome) {
              UploadOutcome.ACKED -> {
                app.store.ack(item)
                app.events.log("upload", "${item.kind} ${item.id.take(8)} stored")
              }
              UploadOutcome.RETRY -> {
                app.events.log("upload", "back office busy on ${item.kind} ${item.id.take(8)}; will retry")
                return@withContext Result.retry()
              }
              UploadOutcome.UNAUTHORIZED -> {
                app.events.log("upload", "back office refused the access code; ${app.store.pending.value} items kept until it's fixed")
                return@withContext Result.retry()
              }
              UploadOutcome.REJECTED -> {
                app.store.reject(item)
                app.events.log("upload", "back office rejected ${item.kind} ${item.id.take(8)}; kept locally")
              }
            }
          }
        }
        Result.success()
      }

  companion object {
    private const val MAX_ROUNDS = 20
    private const val WORK_NAME = "fieldcapture-upload"

    private fun request() =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

    fun kick(context: Context) {
      WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
    }

    /**
     * The back office answers again: drop what's left of WorkManager's back-off and upload now.
     * Leaves a running upload alone, and work that was just kicked (no failed attempt yet).
     * Blocking (reads WorkInfo), so call it off the main thread. Returns true if it re-queued.
     */
    fun retryNow(context: Context): Boolean {
      val wm = WorkManager.getInstance(context)
      val infos = runCatching { wm.getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrDefault(emptyList())
      if (infos.any { it.state == WorkInfo.State.RUNNING }) return false
      val waiting = infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
      if (waiting.isNotEmpty() && waiting.none { it.runAttemptCount > 0 }) return false
      wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request())
      return true
    }
  }
}
