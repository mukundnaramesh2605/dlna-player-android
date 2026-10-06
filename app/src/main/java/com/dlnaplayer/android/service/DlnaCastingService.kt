package com.dlnaplayer.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dlnaplayer.android.R
import com.dlnaplayer.android.model.FileItem
import com.dlnaplayer.android.model.TransportState
import com.dlnaplayer.android.ui.MainActivity

/**
 * Foreground service that maintains an active DLNA media session, keeps the device awake,
 * and shows playback control notifications while casting.
 */
class DlnaCastingService : Service() {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null

    inner class LocalBinder : Binder() {
        fun getService(): DlnaCastingService = this@DlnaCastingService
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DLNA:CastingWakeLock")
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun updateNotification(
        mediaItem: FileItem?,
        deviceName: String?,
        state: TransportState
    ) {
        if (mediaItem == null || state == TransportState.STOPPED || state == TransportState.IDLE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val notification = buildNotification(mediaItem, deviceName ?: "DLNA Device", state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        media: FileItem,
        deviceName: String,
        state: TransportState
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = state == TransportState.PLAYING
        val playPauseActionIntent = Intent(this, MainActivity::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePendingIntent = PendingIntent.getActivity(
            this,
            1,
            playPauseActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopActionIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getActivity(
            this,
            2,
            stopActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(media.name)
            .setContentText("Casting to $deviceName")
            .setSubText(media.extension.uppercase())
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (ignored: Exception) {}
    }

    companion object {
        const val CHANNEL_ID = "dlna_casting_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "com.dlnaplayer.android.ACTION_PLAY"
        const val ACTION_PAUSE = "com.dlnaplayer.android.ACTION_PAUSE"
        const val ACTION_STOP = "com.dlnaplayer.android.ACTION_STOP"
        const val ACTION_STOP_SERVICE = "com.dlnaplayer.android.ACTION_STOP_SERVICE"
        private const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L // 6 hours max
    }
}
