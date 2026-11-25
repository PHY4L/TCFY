package com.phy4l.tcfy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.phy4l.tcfy.MainActivity
import com.phy4l.tcfy.R
import com.phy4l.tcfy.TcfyApplication
import com.phy4l.tcfy.data.export.SessionExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground microphone service that keeps 45-90+ minute classroom recordings alive
 * even when screen is locked on MIUI / HyperOS (Redmi Note 11 5G).
 */
class RecordingForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateObserverJob: Job? = null

    private val coordinator: RecordingCoordinator
        get() = (application as TcfyApplication).recordingCoordinator

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val cur = coordinator.state.value
                if (cur.isRecording || cur.isInitializing) {
                    // Ignore duplicate rapid taps so we never kill an active recording's WakeLock/notification
                    return START_NOT_STICKY
                }
                val title = intent.getStringExtra(EXTRA_TITLE)
                acquireWakeLock()
                startForegroundCompat(buildNotification("正在初始化教室远场录音...", isPaused = false))
                observeRecordingStateForNotification()
                serviceScope.launch {
                    val result = coordinator.startSession(title)
                    if (result.isFailure && !coordinator.state.value.isRecording) {
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            ACTION_TOGGLE_PAUSE -> {
                coordinator.togglePause()
            }
            ACTION_STOP -> {
                serviceScope.launch {
                    coordinator.stopSession()
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeRecordingStateForNotification() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var sawActiveRecording = false
            coordinator.state.collectLatest { st ->
                if (st.isRecording) {
                    sawActiveRecording = true
                    val timeText = SessionExporter.formatTimestamp(st.elapsedMs)
                    val prefix = if (st.isPaused) "已暂停" else "录音转写中"
                    val detail = "$prefix · $timeText · ${st.asrModeLabel}"
                    nm.notify(NOTIFICATION_ID, buildNotification(detail, st.isPaused))
                } else if (sawActiveRecording && !st.isInitializing) {
                    // Recording ended unexpectedly (e.g. mic preempted by phone call) -> release WakeLock & stop service
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String, isPaused: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_TOGGLE_PAUSE
        }
        val pausePending = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("TCFY 课堂听译")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, if (isPaused) "继续" else "暂停", pausePending)
            .addAction(0, "结束保存", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TCFY:ClassroomRecording").apply {
            // Up to 4 hours safety timeout for double-block university seminars
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "tcfy_recording_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.phy4l.tcfy.action.START"
        const val ACTION_TOGGLE_PAUSE = "com.phy4l.tcfy.action.TOGGLE_PAUSE"
        const val ACTION_STOP = "com.phy4l.tcfy.action.STOP"
        const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, title: String? = null) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_START
                if (!title.isNullOrBlank()) putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun togglePause(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
