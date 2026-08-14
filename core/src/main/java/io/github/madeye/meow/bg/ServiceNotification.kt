package io.github.madeye.meow.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.madeye.meow.core.R

class ServiceNotification(
    private val service: Service,
    profileName: String,
    channelId: String,
) {
    companion object {
        private const val NOTIFICATION_ID = 1
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = service.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Mihomo VPN Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(service, channelId)
            .setContentTitle("Mihomo")
            .setContentText(if (profileName.isNotEmpty()) "Connected: $profileName" else "Connecting...")
            .setSmallIcon(R.drawable.ic_service_active)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun destroy() {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }
}
