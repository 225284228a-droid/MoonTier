package cn.moonflow.easytier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class EasyTierVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var attachJob: Job? = null
    private var instanceId = ""
    private var generation = -1L
    private val controller by lazy { EasyTierController.get(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        controller.onVpnServiceCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        if (intent?.action == ACTION_STOP) {
            shutdownVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        val config = runCatching { JSONObject(intent?.getStringExtra(EXTRA_CONFIG).orEmpty()) }.getOrNull()
        val id = config?.stringValue("instanceId").orEmpty()
        val token = config?.optLong("generation", -1L) ?: -1L
        if (config == null || !controller.acceptsVpnRequest(id, token)) {
            if (vpnInterface == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val previous = attachJob
        attachJob = serviceScope.launch {
            previous?.cancelAndJoin()
            if (!controller.acceptsVpnRequest(id, token)) return@launch
            disconnect()
            instanceId = id
            generation = token
            val pfd = createVpnInterface(config)
            if (pfd == null) {
                controller.onVpnServiceStartFailed(IllegalStateException("无法创建 VPN 接口"))
                stopSelfResult(startId)
                return@launch
            }
            vpnInterface = pfd
            try {
                controller.attachTunFd(id, token, pfd.fd)
                if (!controller.acceptsVpnRequest(id, token)) {
                    if (vpnInterface === pfd) disconnect()
                    return@launch
                }
                controller.onVpnAttached(id, token)
                AppDiagnostics.event("vpn", "VPN interface attached to $id")
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (vpnInterface === pfd) disconnect()
                throw error
            } catch (error: Throwable) {
                if (vpnInterface === pfd) disconnect()
                controller.onVpnServiceStartFailed(error)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        AppDiagnostics.warn("vpn", "VPN permission revoked by Android")
        controller.onVpnClosed(instanceId, generation, revoked = true)
        shutdownVpn()
        stopSelf()
    }

    override fun onDestroy() {
        controller.onVpnServiceDestroyed(this)
        serviceScope.cancel()
        controller.onVpnClosed(instanceId, generation, revoked = false)
        shutdownVpn()
        super.onDestroy()
    }

    private fun createVpnInterface(config: JSONObject): ParcelFileDescriptor? = runCatching {
        val addresses = config.getJSONArray("addresses")
        val routes = config.optJSONArray("routes")
        val dns = config.stringValue("dns")
        val builder = Builder().setSession("MoonTier")
            .setMtu(config.optInt("mtu", 1400)).setBlocking(false)
        require(addresses.length() > 0) { "共享 VPN 没有可用地址" }
        for (index in 0 until addresses.length()) {
            val parts = addresses.getString(index).split('/')
            builder.addAddress(parts[0], parts[1].toInt())
        }
        AppDiagnostics.info("vpn", "creating shared interface members=${config.optJSONArray("instanceIds")?.length()} addresses=${addresses.length()}")

        if (dns.isNotBlank()) builder.addDnsServer(dns)
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { Log.w(TAG, "addDisallowedApplication failed", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val directCoreIps = config.optJSONArray("directCoreIps")
            if (directCoreIps != null) {
                for (i in 0 until directCoreIps.length()) {
                    val ip = directCoreIps.optString(i)
                    if (ip.isNotBlank()) {
                        runCatching {
                            builder.excludeRoute(IpPrefix(java.net.InetAddress.getByName(ip), 32))
                        }
                            .onFailure { Log.w(TAG, "skip exclude route: $ip", it) }
                    }
                }
            }
        }

        if (routes != null) {
            for (i in 0 until routes.length()) {
                val cidr = routes.optString(i)
                val parts = cidr.split("/")
                if (parts.size == 2) {
                    runCatching { builder.addRoute(parts[0], parts[1].toInt()) }
                        .onFailure { Log.w(TAG, "skip invalid route: $cidr", it) }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        builder.establish()
    }.onFailure {
        Log.e(TAG, "createVpnInterface failed", it)
        AppDiagnostics.error("vpn", "VPN interface creation failed", it)
    }.getOrNull()

    private suspend fun disconnect() {
        controller.detachTunFd(instanceId, generation)
        vpnInterface?.close()
        vpnInterface = null
    }

    internal suspend fun stopAndWait() {
        val pending = attachJob
        attachJob = null
        pending?.cancelAndJoin()
        disconnect()
        instanceId = ""
        generation = -1L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdownVpn() {
        val pending = attachJob
        attachJob = null
        pending?.cancel()
        val descriptor = vpnInterface
        vpnInterface = null
        val closingId = instanceId
        val closingGeneration = generation
        instanceId = ""
        generation = -1L
        // Finish attachment and detach native readers before closing the caller-owned fd.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            pending?.join()
            try {
                controller.detachTunFd(closingId, closingGeneration)
            } finally {
                descriptor?.close()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MoonTier VPN", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("MoonTier")
            .setContentText("VPN 正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MoonTierVpn"
        private const val CHANNEL_ID = "moontier_vpn"
        private const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "cn.moonflow.easytier.action.STOP_VPN"
        const val EXTRA_CONFIG = "vpn_config"
    }
}
