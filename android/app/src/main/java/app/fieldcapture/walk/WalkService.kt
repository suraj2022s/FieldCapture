package app.fieldcapture.walk

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import app.fieldcapture.MainActivity
import app.fieldcapture.R

/**
 * Keeps a walk alive while the phone is in a pocket with the screen off.
 * Types: connectedDevice (the glasses link), camera (Mock Device Kit uses the phone camera as the
 * glasses feed), microphone (voice commands). ColorOS also needs "allow background activity"
 * for FieldCapture, or it may still kill the process.
 */
class WalkService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Walk-through", NotificationManager.IMPORTANCE_LOW))
    val open =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
    val notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle("FieldCapture walk in progress")
            .setContentText("Listening for capture commands")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    // start() only gets here when foregroundTypes() is non-zero. Android 14+ validates each type
    // against a granted runtime permission, so claim exactly what is granted.
    val type = foregroundTypes(this)
    try {
      startForeground(NOTIFICATION_ID, notification, if (type != 0) type else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } catch (e: Exception) {
      // Nothing safe left to do: stopping before startForeground() is itself fatal on Android 12+.
      android.util.Log.e("FieldCapture", "foreground service refused: ${e.message}")
    }
    wakeLock =
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fieldcapture:walk")
            .apply { acquire(MAX_WALK_MS) }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onDestroy() {
    runCatching { wakeLock?.release() }
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "walk"
    private const val NOTIFICATION_ID = 42
    private const val MAX_WALK_MS = 4 * 60 * 60 * 1000L

    /** Foreground-service types whose runtime permissions are currently granted. */
    fun foregroundTypes(context: Context): Int {
      fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
      var type = 0
      if (granted(Manifest.permission.BLUETOOTH_CONNECT)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
      if (granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
      if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      return type
    }

    /**
     * Best effort: the walk works without the service (it only protects screen-off operation).
     * Decide BEFORE startForegroundService(): once called, Android requires startForeground() within
     * seconds, and a service that can't comply crashes the app (ForegroundServiceDidNotStartInTime).
     */
    fun start(context: Context) {
      if (foregroundTypes(context) == 0) {
        android.util.Log.w("FieldCapture", "foreground service skipped: no Bluetooth/camera/mic permission granted")
        return
      }
      runCatching { context.startForegroundService(Intent(context, WalkService::class.java)) }
          .onFailure { android.util.Log.w("FieldCapture", "foreground service not started: ${it.message}") }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, WalkService::class.java))
    }
  }
}
