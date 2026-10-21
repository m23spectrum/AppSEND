package local.edgebeam

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class BeamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: BeamApp

    override fun onCreate() {
        super.onCreate()
        app = application as BeamApp
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("availability", "AppSEND", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    setSound(null, null)
                    enableVibration(false)
                }
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        fun notification() =
            NotificationCompat.Builder(this, "availability")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("AppSEND")
                .setContentText(UiText.text(app.state.value.language ?: "en", "availabilityActive"))
                .setSilent(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        ServiceCompat.startForeground(
            this,
            1,
            notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        val engine = Engine(app)
        app.engine = engine
        scope.launch {
            try {
                engine.start()
            } catch (_: Exception) {
                stopSelf()
            }
        }
        scope.launch {
            app.state
                .map { it.language }
                .distinctUntilChanged()
                .collect { manager.notify(1, notification()) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onBind(intent: Intent?) = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        app.status("Android background service time limit reached")
        stopSelf()
    }

    override fun onDestroy() {
        app.engine?.close()
        app.engine = null
        scope.cancel()
        super.onDestroy()
    }
}
