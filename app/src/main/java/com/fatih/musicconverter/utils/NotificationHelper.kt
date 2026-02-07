package com.fatih.musicconverter.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fatih.musicconverter.R

class NotificationHelper(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "conversion_channel"

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Dönüştürme İşlemleri",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Müzik dönüştürme ilerlemesini gösterir"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showProgressNotification(fileName: String, progress: Int) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Dönüştürülüyor...")
            .setContentText(fileName)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
        notificationManager.notify(1, notification)
    }

    fun showCompletionNotification(fileName: String, success: Boolean, message: String? = null) {
        val title = if (success) "Dönüştürme Tamamlandı" else "Dönüştürme Başarısız"
        val icon = if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error
        
        val content = if (message != null) "$fileName: $message" else fileName

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(1, notification)
    }
}
