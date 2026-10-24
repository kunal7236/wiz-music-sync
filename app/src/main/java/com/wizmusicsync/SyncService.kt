package com.wizmusicsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Foreground service that captures other apps' media audio (AudioPlaybackCapture, no microphone),
 * runs it through [LightEngine] and sends the resulting setPilot commands to the WiZ bulb over UDP.
 */
class SyncService : Service() {

    companion object {
        const val ACTION_START = "com.wizmusicsync.START"
        const val ACTION_STOP = "com.wizmusicsync.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_BULB_IP = "bulbIp"

        const val PORT = 38899
        const val SAMPLE_RATE = 48000
        const val CHUNK = 1024
        const val CHANNELS = 2

        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1

        // Shared with MainActivity
        @Volatile var isRunning = false
            private set
        @Volatile var status = "Stopped"
            private set
        /** Holds every bulb command back by this much so the light lines up with a lagging speaker. */
        @Volatile var delayMs = 0
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing = false

    private var sender: ScheduledThreadPoolExecutor? = null
    private var socket: DatagramSocket? = null
    private var bulbIp = ""
    private var bulbAddress: InetAddress? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        bulbIp = intent.getStringExtra(EXTRA_BULB_IP).orEmpty()

        // Must be in the foreground before the MediaProjection token is redeemed (Android 14+)
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        if (resultData == null || bulbIp.isEmpty() ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, resultData)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Fires when the user stops casting from the status bar / lock screen
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        socket = DatagramSocket()
        sender = ScheduledThreadPoolExecutor(1).apply {
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }

        startCapture(mp)
        isRunning = true
        status = "Connecting to WiZ bulb at $bulbIp..."
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun startCapture(mp: MediaProjection) {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBuffer, CHUNK * CHANNELS * 2 * 4))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        record = rec

        capturing = true
        captureThread = Thread({
            val engine = LightEngine(SAMPLE_RATE, nowSeconds())
            val buffer = ShortArray(CHUNK * CHANNELS)
            rec.startRecording()
            while (capturing) {
                var offset = 0
                while (offset < buffer.size && capturing) {
                    val n = rec.read(buffer, offset, buffer.size - offset, AudioRecord.READ_BLOCKING)
                    if (n < 0) {
                        capturing = false
                        break
                    }
                    offset += n
                }
                if (!capturing) break

                val command = engine.process(buffer, CHUNK, CHANNELS, nowSeconds())
                status = engine.status
                if (command != null) {
                    try {
                        sender?.schedule({ send(command) }, delayMs.toLong(), TimeUnit.MILLISECONDS)
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        // service is shutting down
                    }
                }
            }
        }, "audio-capture").apply { start() }
    }

    private fun nowSeconds() = System.nanoTime() / 1e9

    // ----------------- UDP DISPATCH -----------------
    private fun payload(command: LightEngine.Command): String {
        val params = JSONObject().put("state", true)
        when (command) {
            is LightEngine.Command.Pilot -> params
                .put("r", command.r)
                .put("g", command.g)
                .put("b", command.b)
                .put("c", 0)
                .put("w", 0)
                .put("dimming", command.dimming.coerceIn(55, 100))
                .put("speed", 100)
            // Clean natural white using the bulb's dedicated white diodes
            LightEngine.Command.White -> params
                .put("temp", 4200)
                .put("dimming", 100)
                .put("speed", 100)
        }
        return JSONObject().put("id", 1).put("method", "setPilot").put("params", params).toString()
    }

    /** Runs on the sender thread only. */
    private fun send(command: LightEngine.Command) {
        try {
            val address = bulbAddress ?: InetAddress.getByName(bulbIp).also { bulbAddress = it }
            val bytes = payload(command).toByteArray()
            socket?.send(DatagramPacket(bytes, bytes.size, address, PORT))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Light sync", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("WiZ Music Sync")
            .setContentText("Syncing the bulb to your music")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_launcher_foreground), "Stop", stop
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        status = "Stopped"

        capturing = false
        record?.let { rec ->
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            captureThread?.join(500)
            rec.release()
        }
        record = null

        projection?.stop()
        projection = null

        // Drop any delayed colour commands, then restore comfortable white light
        sender?.let { executor ->
            executor.execute { send(LightEngine.Command.White) }
            executor.shutdown()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
        sender = null
        socket?.close()
        socket = null
        super.onDestroy()
    }
}
