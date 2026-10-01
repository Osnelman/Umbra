package com.freebuff.barrage.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.freebuff.barrage.MainActivity
import com.freebuff.barrage.Prefs
import com.freebuff.barrage.R
import com.freebuff.barrage.dns.DnsPacket
import com.freebuff.barrage.filter.BlocklistRepository
import com.freebuff.barrage.filter.DomainBlocklist
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * VPN local : crée un tunnel TUN qui ne route que le DNS du téléphone
 * (10.111.222.53). Chaque requête est lue depuis le tunnel, vérifiée contre la
 * liste de blocage + les mots-clés, puis :
 *  - bloquée  → réponse 0.0.0.0 (la connexion échoue) + notification « Site bloqué »
 *  - autorisée → relayée vers un résolveur amont (sockets protégées pour ne pas
 *                se router en boucle dans le tunnel).
 *
 * Rien ne quitte le téléphone au-delà des requêtes DNS classiques : aucun
 * paquet n'est inspecté ni envoyé ailleurs.
 */
class LocalVpnService : VpnService() {

    companion object {
        private const val TAG = "Umbra"
        private const val NOTIF_ID = 42
        private const val BLOCK_NOTIF_ID = 43
        private const val CHANNEL_ID = "protection"
        private const val LOCAL_DNS = "10.111.222.53"
        private const val LOCAL_ADDR = "10.111.222.1"
        private val UPSTREAMS = arrayOf("1.1.1.1", "9.9.9.9")

        @Volatile
        var isRunning = false
            private set

        /** Requêtes bloquées depuis le démarrage du service. */
        @Volatile
        var sessionBlocked = 0
            private set

        /** Recharge la liste (après mise à jour) si le service est actif. */
        fun refresh(context: Context) {
            if (isRunning) {
                context.startService(Intent(context, LocalVpnService::class.java))
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var running = false

    private val writeLock = Any()
    private val executor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "barrage-fwd").apply { isDaemon = true }
    }
    private lateinit var prefs: Prefs

    @Volatile
    private var blocklist: DomainBlocklist? = null

    private var lastBlockNotifAt = 0L

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        loadBlocklistAsync()
        if (tun == null && !startTunnel()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // L'utilisateur a coupé le VPN depuis les réglages système
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        executor.shutdownNow()
        try {
            tun?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close tun", e)
        }
        tun = null
        try {
            notificationManager.cancel(NOTIF_ID)
        } catch (e: Exception) {
            // canal déjà détruit
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ tunnel

    private fun startTunnel(): Boolean {
        return try {
            val builder = Builder()
                .setSession("Umbra")
                .addAddress(LOCAL_ADDR, 24)
                .addDnsServer(LOCAL_DNS)
                .addRoute(LOCAL_DNS, 32) // seules les requêtes DNS entrent dans le tunnel
                .setBlocking(true)
            val fd = builder.establish() ?: run {
                Log.e(TAG, "establish() refusé (consentement VPN manquant ?)")
                return false
            }
            tun = fd
            running = true
            isRunning = true
            Thread({ pump(fd) }, "barrage-tun").start()
            updateNotification()
            true
        } catch (e: Exception) {
            Log.e(TAG, "erreur VPN", e)
            false
        }
    }

    /** Boucle de lecture du tunnel (bloquante) : une requête = un traitement. */
    private fun pump(fd: ParcelFileDescriptor) {
        try {
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val buf = ByteArray(65535)
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                try {
                    handle(buf, n, output)
                } catch (e: Exception) {
                    Log.w(TAG, "paquet ignoré", e)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "tunnel fermé : ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "pump", e)
        } finally {
            running = false
            isRunning = false
            stopSelf()
        }
    }

    private fun handle(buf: ByteArray, n: Int, output: FileOutputStream) {
        val request = IpPacket.parseDnsRequest(buf, n) ?: return
        val question = DnsPacket.parseQuestion(request.dnsPayload) ?: return

        val list = blocklist
        if (list != null && list.isBlocked(question.name)) {
            onBlocked(question.name)
            val response = DnsPacket.buildBlockedResponse(request.dnsPayload, question)
            write(output, IpPacket.buildReply(request, response))
            return
        }

        // Autorisé : relais vers le résolveur amont (hors tunnel)
        val query = request.dnsPayload
        try {
            executor.execute {
                val answer = forward(query)
                if (answer != null && running) {
                    write(output, IpPacket.buildReply(request, answer))
                }
            }
        } catch (e: Exception) {
            // pool arrêté : on laisse tomber la requête
        }
    }

    private fun write(output: FileOutputStream, packet: ByteArray) {
        synchronized(writeLock) {
            try {
                output.write(packet)
            } catch (e: IOException) {
                Log.w(TAG, "écriture tunnel", e)
            }
        }
    }

    private fun onBlocked(host: String) {
        sessionBlocked = sessionBlocked + 1
        prefs.recordBlock(host)
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockNotifAt >= 1500) {
            lastBlockNotifAt = now
            updateNotification()
            try {
                notificationManager.notify(
                    BLOCK_NOTIF_ID,
                    buildNotification(getString(R.string.notif_block, host), "Connexion coupée", false)
                )
            } catch (e: Exception) {
                // permission de notification absente
            }
        }
    }

    // -------------------------------------------------------------- résolution

    /** Envoie la requête aux résolveurs amont, UDP puis TCP si nécessaire. */
    private fun forward(query: ByteArray): ByteArray? {
        for (server in UPSTREAMS) {
            val addr = try {
                InetAddress.getByName(server)
            } catch (e: Exception) {
                continue
            }
            val udp = sendUdp(addr, query)
            if (udp != null) {
                if (!isTruncated(udp)) return udp
                val tcp = sendTcp(addr, query)
                if (tcp != null) return tcp
                return udp // tronqué mais on renvoie quand même
            }
            val tcp = sendTcp(addr, query)
            if (tcp != null) return tcp
        }
        return null
    }

    private fun sendUdp(addr: InetAddress, query: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            protect(socket) // ne jamais se router dans notre propre tunnel
            socket.soTimeout = 2500
            socket.send(DatagramPacket(query, query.size, addr, IpPacket.DNS_PORT))
            val buf = ByteArray(65535)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            buf.copyOfRange(0, packet.length)
        } catch (e: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun sendTcp(addr: InetAddress, query: ByteArray): ByteArray? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            protect(socket)
            socket.connect(InetSocketAddress(addr, IpPacket.DNS_PORT), 2500)
            socket.soTimeout = 3000
            val out = socket.getOutputStream()
            out.write((query.size shr 8) and 0xFF)
            out.write(query.size and 0xFF)
            out.write(query)
            out.flush()

            val input = socket.getInputStream()
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) return null
            val len = (hi shl 8) or lo
            if (len <= 0 || len > 65535) return null
            val resp = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = input.read(resp, off, len - off)
                if (r < 0) return null
                off += r
            }
            resp
        } catch (e: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun isTruncated(resp: ByteArray): Boolean =
        resp.size >= 4 && (resp[2].toInt() and 0x02) != 0

    // ----------------------------------------------------------- notifications

    private fun loadBlocklistAsync() {
        Thread({
            val list = BlocklistRepository.load(this)
            blocklist = list
            prefs.listSize = list.size
            updateNotification()
        }, "barrage-load").start()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "État de la protection"
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification(getString(R.string.notif_title_on), "Démarrage…", true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        isRunning = true
    }

    private fun updateNotification() {
        try {
            val text = "$sessionBlocked bloqués en session · ${prefs.listSize} domaines connus"
            notificationManager.notify(
                NOTIF_ID,
                buildNotification(getString(R.string.notif_title_on), text, true)
            )
        } catch (e: Exception) {
            // permission de notification absente
        }
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_block)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
        if (!ongoing) builder.setAutoCancel(true)
        return builder.build()
    }
}
