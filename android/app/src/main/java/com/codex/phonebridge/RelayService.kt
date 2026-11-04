package com.codex.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.ServiceInfo
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class RelayService : Service() {
    companion object {
        const val ACTION_START = "com.codex.phonebridge.START"
        const val ACTION_STOP = "com.codex.phonebridge.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_CODE = "code"
        const val PREFS = "phonebridge"
        private const val PORT = 47860
        private const val CHANNEL_ID = "phonebridge_relay"
        private const val NOTIFICATION_ID = 47800
    }

    private val running = AtomicBoolean(false)
    private var networkThread: Thread? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            RelaySession.host = intent.getStringExtra(EXTRA_HOST).orEmpty()
            RelaySession.pairingCode = intent.getStringExtra(EXTRA_CODE).orEmpty()
        }
        if (RelaySession.host.isBlank() || RelaySession.pairingCode.length != 6) {
            setStatus("Enter the PC address and pairing code, then start again.")
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val notification = buildNotification("Connecting to ${RelaySession.host}…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireLocks()
        RelaySession.token = ""
        RelaySession.connected = false
        RelaySession.clear()
        setStatus("Connecting to PC…")
        if (running.compareAndSet(false, true)) {
            networkThread = Thread(::networkLoop, "PhoneBridge-UDP").apply { isDaemon = true; start() }
        }
        return START_NOT_STICKY
    }

    private fun networkLoop() {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 100
                val address = InetAddress.getByName(RelaySession.host)
                val target = java.net.InetSocketAddress(address, PORT)
                var lastPair = 0L
                var lastInput = 0L
                val buffer = ByteArray(2048)
                while (running.get()) {
                    val now = System.currentTimeMillis()
                    if (RelaySession.token.isBlank()) {
                        if (now - lastPair >= 800) {
                            val hello = JSONObject().put("type", "pair").put("code", RelaySession.pairingCode).toString()
                            send(socket, target, hello)
                            lastPair = now
                        }
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            if (packet.address != address) continue
                            val reply = JSONObject(String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8))
                            if (reply.optString("type") == "paired" && reply.optString("token").isNotBlank()) {
                                RelaySession.token = reply.getString("token")
                                RelaySession.connected = true
                                setStatus("Relaying to PC. You can lock the phone screen.")
                                updateNotification("Controller relay is active")
                            } else if (reply.optString("type") == "error") {
                                setStatus(reply.optString("message", "Pairing was rejected."))
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // Continue pairing attempts while the receiver is starting.
                        }
                    } else if (now - lastInput >= 16) {
                        val input = RelaySession.inputPacket().put("token", RelaySession.token)
                        send(socket, target, input.toString())
                        lastInput = now
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) setStatus("Connection error: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            RelaySession.connected = false
            running.set(false)
        }
    }

    private fun send(socket: DatagramSocket, target: java.net.SocketAddress, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, target))
    }

    private fun setStatus(value: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("status", value).apply()
    }

    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBridge:Relay").apply { setReferenceCounted(false); acquire() }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneBridge:RelayWiFi").apply { setReferenceCounted(false); acquire() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "PhoneBridge relay", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(line: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("PhoneBridge is active")
            .setContentText(line)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build())
            .build()
    }

    private fun updateNotification(line: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(line))
    }

    override fun onDestroy() {
        running.set(false)
        networkThread?.interrupt()
        try { cpuLock?.release() } catch (_: Exception) { }
        try { wifiLock?.release() } catch (_: Exception) { }
        RelaySession.connected = false
        RelaySession.token = ""
        setStatus("Relay stopped.")
        super.onDestroy()
    }
}
