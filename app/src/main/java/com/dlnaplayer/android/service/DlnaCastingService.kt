package com.dlnaplayer.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import com.dlnaplayer.android.R
import com.dlnaplayer.android.model.DlnaDevice
import com.dlnaplayer.android.model.FileItem
import com.dlnaplayer.android.model.MediaType
import com.dlnaplayer.android.model.PlaybackState
import com.dlnaplayer.android.model.TransportState
import com.dlnaplayer.android.ui.MainActivity
import java.io.File

/**
 * Foreground service that manages active DLNA media casting sessions.
 * Provides system-level MediaSession integration and rich MediaStyle notification controls:
 *  - Responsive Play / Pause toggle
 *  - Skip to Next / Previous track
 *  - Stop casting
 *  - Live seek position, duration, and album artwork / video thumbnail
 *  - Seamless lockscreen and Quick Settings media player integration
 */
class DlnaCastingService : Service() {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null
    private var mediaSession: MediaSessionCompat? = null

    private var lastThumbnailPath: String? = null
    private var cachedThumbnailBitmap: Bitmap? = null

    interface PlaybackControlListener {
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onNext()
        fun onPrevious()
        fun onSeekTo(positionMs: Long)
    }

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

        // Initialize system MediaSession for lockscreen & Quick Settings controls
        mediaSession = MediaSessionCompat(this, "DlnaCastingSession").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSession: onPlay")
                    controlListener?.onPlay()
                }

                override fun onPause() {
                    Log.d(TAG, "MediaSession: onPause")
                    controlListener?.onPause()
                }

                override fun onSkipToNext() {
                    Log.d(TAG, "MediaSession: onSkipToNext")
                    controlListener?.onNext()
                }

                override fun onSkipToPrevious() {
                    Log.d(TAG, "MediaSession: onSkipToPrevious")
                    controlListener?.onPrevious()
                }

                override fun onStop() {
                    Log.d(TAG, "MediaSession: onStop")
                    controlListener?.onStop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isActive = false
                    stopSelf()
                }

                override fun onSeekTo(pos: Long) {
                    Log.d(TAG, "MediaSession: onSeekTo $pos")
                    controlListener?.onSeekTo(pos)
                }
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                Log.d(TAG, "Service Action: PLAY")
                controlListener?.onPlay()
            }
            ACTION_PAUSE -> {
                Log.d(TAG, "Service Action: PAUSE")
                controlListener?.onPause()
            }
            ACTION_PREVIOUS -> {
                Log.d(TAG, "Service Action: PREVIOUS")
                controlListener?.onPrevious()
            }
            ACTION_NEXT -> {
                Log.d(TAG, "Service Action: NEXT")
                controlListener?.onNext()
            }
            ACTION_STOP, ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Service Action: STOP")
                controlListener?.onStop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                mediaSession?.isActive = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setPlaybackControlListener(listener: PlaybackControlListener?) {
        controlListener = listener
    }

    /**
     * Updates notification with full PlaybackState, track position, duration, and playlist navigation.
     */
    fun updateNotification(
        playbackState: PlaybackState,
        hasNext: Boolean = false,
        hasPrevious: Boolean = false
    ) {
        val mediaItem = playbackState.currentMedia
        val deviceName = playbackState.targetDevice?.friendlyName
        val state = playbackState.transportState

        if (mediaItem == null || state == TransportState.STOPPED || state == TransportState.IDLE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            mediaSession?.isActive = false
            return
        }

        updateMediaSession(playbackState)
        val notification = buildNotification(playbackState, deviceName ?: "DLNA Device", hasNext, hasPrevious)

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

    /**
     * Legacy convenience overload.
     */
    fun updateNotification(
        mediaItem: FileItem?,
        deviceName: String?,
        state: TransportState
    ) {
        if (mediaItem == null || state == TransportState.STOPPED || state == TransportState.IDLE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            mediaSession?.isActive = false
            return
        }

        val stateObj = PlaybackState(
            transportState = state,
            currentMedia = mediaItem,
            targetDevice = DlnaDevice(
                udn = "",
                friendlyName = deviceName ?: "DLNA Device",
                manufacturer = "",
                modelName = "",
                locationUrl = "",
                ipAddress = "",
                avTransportControlUrl = "",
                renderingControlUrl = null
            )
        )
        updateNotification(stateObj)
    }

    private fun updateMediaSession(playbackState: PlaybackState) {
        val session = mediaSession ?: return
        val media = playbackState.currentMedia ?: return

        session.isActive = true

        val compatState = when (playbackState.transportState) {
            TransportState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            TransportState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            TransportState.CONNECTING, TransportState.TRANSITIONING -> PlaybackStateCompat.STATE_BUFFERING
            TransportState.STOPPED, TransportState.IDLE, TransportState.ERROR -> PlaybackStateCompat.STATE_STOPPED
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO

        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(compatState, playbackState.positionMs, 1.0f)
            .setActions(actions)

        session.setPlaybackState(stateBuilder.build())

        val thumbnail = getOrLoadThumbnail(media)
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, media.name)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Casting to ${playbackState.targetDevice?.friendlyName ?: "DLNA Device"}")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, media.extension.uppercase())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, playbackState.durationMs)

        if (thumbnail != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, thumbnail)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, thumbnail)
        }

        session.setMetadata(metadataBuilder.build())
    }

    private fun buildNotification(
        playbackState: PlaybackState,
        deviceName: String,
        hasNext: Boolean,
        hasPrevious: Boolean
    ): Notification {
        val media = playbackState.currentMedia ?: return NotificationCompat.Builder(this, CHANNEL_ID).build()
        val isPlaying = playbackState.isPlaying

        // Open MainActivity and show NowPlaying sheet on notification tap
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = ACTION_OPEN_PLAYER
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action PendingIntents routed directly to DlnaCastingService for zero-latency background execution
        val prevPendingIntent = createActionPendingIntent(ACTION_PREVIOUS, 10)
        val playPausePendingIntent = createActionPendingIntent(
            if (isPlaying) ACTION_PAUSE else ACTION_PLAY,
            11
        )
        val nextPendingIntent = createActionPendingIntent(ACTION_NEXT, 12)
        val stopPendingIntent = createActionPendingIntent(ACTION_STOP, 13)

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(media.name)
            .setContentText("Casting to $deviceName")
            .setSubText(media.extension.uppercase())
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setDeleteIntent(stopPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val thumbnail = getOrLoadThumbnail(media)
        if (thumbnail != null) {
            builder.setLargeIcon(thumbnail)
        }

        // Action 0: Previous track
        builder.addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)

        // Action 1: Play / Pause toggle
        builder.addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)

        // Action 2: Next track
        builder.addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)

        // Action 3: Stop casting
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)

        // MediaStyle configuration
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2) // Previous, Play/Pause, Next in compact view
            .setShowCancelButton(true)
            .setCancelButtonIntent(stopPendingIntent)

        mediaSession?.sessionToken?.let {
            mediaStyle.setMediaSession(it)
        }
        builder.setStyle(mediaStyle)

        return builder.build()
    }

    private fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, DlnaCastingService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getOrLoadThumbnail(media: FileItem): Bitmap? {
        val path = media.file.absolutePath
        if (path == lastThumbnailPath && cachedThumbnailBitmap != null) {
            return cachedThumbnailBitmap
        }
        val bitmap = loadThumbnail(media.file, media.mediaType)
        lastThumbnailPath = path
        cachedThumbnailBitmap = bitmap
        return bitmap
    }

    private fun loadThumbnail(file: File, mediaType: MediaType): Bitmap? {
        return try {
            when (mediaType) {
                MediaType.VIDEO -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(file, Size(256, 256), null)
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                    }
                }
                MediaType.IMAGE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createImageThumbnail(file, Size(256, 256), null)
                    } else {
                        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
                        val sampleSize = calculateInSampleSize(boundsOptions, 256, 256)
                        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeFile(file.absolutePath, opts)
                    }
                }
                MediaType.AUDIO -> {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(file.absolutePath)
                    val raw = mmr.embeddedPicture
                    mmr.release()
                    if (raw != null) {
                        BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
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
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (ignored: Exception) {}

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (ignored: Exception) {}

        cachedThumbnailBitmap?.recycle()
        cachedThumbnailBitmap = null
        lastThumbnailPath = null
    }

    companion object {
        private const val TAG = "DlnaCastingService"
        const val CHANNEL_ID = "dlna_casting_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.dlnaplayer.android.ACTION_PLAY"
        const val ACTION_PAUSE = "com.dlnaplayer.android.ACTION_PAUSE"
        const val ACTION_STOP = "com.dlnaplayer.android.ACTION_STOP"
        const val ACTION_PREVIOUS = "com.dlnaplayer.android.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.dlnaplayer.android.ACTION_NEXT"
        const val ACTION_OPEN_PLAYER = "com.dlnaplayer.android.ACTION_OPEN_PLAYER"
        const val ACTION_STOP_SERVICE = "com.dlnaplayer.android.ACTION_STOP_SERVICE"

        var controlListener: PlaybackControlListener? = null
        private const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L // 6 hours max
    }
}
