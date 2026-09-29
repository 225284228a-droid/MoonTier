package cn.moonflow.easytier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class EasyTierRuntimeService : Service() {
    private val controller by lazy { EasyTierController.get(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification("网络服务正在运行"))
        controller.onServiceCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        controller.onServiceCommand(intent, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller.onServiceDestroyed(this)
        super.onDestroy()
    }

    fun updateStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "MoonTier 网络", NotificationManager.IMPORTANCE_LOW))
        }
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("MoonTier").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share).setContentIntent(pending).setOngoing(true).build()
    }

    companion object {
        private const val CHANNEL_ID = "moontier_runtime"
        private const val NOTIFICATION_ID = 8
        const val ACTION_RESTORE = "cn.moonflow.easytier.runtime.RESTORE"
        const val ACTION_START = "cn.moonflow.easytier.runtime.START"
        const val ACTION_STOP_INSTANCE = "cn.moonflow.easytier.runtime.STOP_INSTANCE"
        const val ACTION_ENABLE_INSTANCE = "cn.moonflow.easytier.runtime.ENABLE_INSTANCE"
        const val ACTION_DELETE_INSTANCE = "cn.moonflow.easytier.runtime.DELETE_INSTANCE"
        const val ACTION_CONFIG_SERVER = "cn.moonflow.easytier.runtime.CONFIG_SERVER"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_SERVER_URL = "server_url"
    }
}
