package com.zeidex.convert.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zeidex.convert.MainActivity
import com.zeidex.convert.R

class ConversionForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "zeidex_conversions_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.zeidex.convert.action.START"
        const val ACTION_UPDATE = "com.zeidex.convert.action.UPDATE"
        const val ACTION_STOP = "com.zeidex.convert.action.STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_COMPLETED = "extra_completed"

        fun start(context: Context, title: String) {
            val intent = Intent(context, ConversionForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, title: String, progress: Int) {
            val intent = Intent(context, ConversionForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PROGRESS, progress)
            }
            context.startService(intent)
        }

        fun stop(context: Context, completionMessage: String? = null) {
            val intent = Intent(context, ConversionForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_COMPLETED, completionMessage)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.converting)
                startForeground(NOTIFICATION_ID, buildNotification(title, 0, indeterminate = true))
            }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.converting)
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val notification = buildNotification(title, progress, indeterminate = false)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
            }
            ACTION_STOP -> {
                val completion = intent.getStringExtra(EXTRA_COMPLETED)
                if (completion != null) {
                    val manager = getSystemService(NotificationManager::class.java)
                    val doneNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(completion)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setAutoCancel(true)
                        .build()
                    manager.notify(NOTIFICATION_ID + 1, doneNotification)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (indeterminate) {
            builder.setProgress(100, 0, true)
        } else {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zeidex Conversions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of active local file conversions"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
