package com.example.myvpn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class MyVpnService : VpnService() {
    companion object {
        const val OFF = 0
        const val CONNECTING = 1
        const val CONNECTED = 2
        const val RECONNECTING = 3
        @Volatile var state = OFF
        @Volatile var lastStatus = ""
        @Volatile var connectedSince = 0L
        @Volatile var bytesDown = 0L
        @Volatile var bytesUp = 0L
        @Volatile var notice = ""
        @Volatile var noticeAt = 0L
        private const val TAG = "MyVPN"
    }

    @Volatile private var running = false
    @Volatile private var sock: DatagramSocket? = null
    @Volatile private var cur: Session? = null
    @Volatile private var prev: Session? = null
    @Volatile private var lastRx = 0L
    @Volatile private var forceReconnect = false
    @Volatile private var startedAt = 0L
    private var tun: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private lateinit var serverAddr: InetAddress
    private var serverPort = 51820
    private lateinit var psk: ByteArray
    private lateinit var clientId: ByteArray
    private var full = true
    private var failover = true
    private var curId = ""
    private var kill = true
    private var excluded: Set<String> = emptySet()
    private val pendings = ConcurrentHashMap<Int, Pending>()
    private var assignedIp = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            lastStatus = ""
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        // intent == null: sistem "Her zaman açık VPN" ile başlattı
        val srv = Store.active(this)
        if (srv == null) {
            lastStatus = "Sunucu seçilmedi"
            return START_NOT_STICKY
        }
        val key = try { Base64.decode(srv.psk.trim(), Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }
        if (key.size != 32) {
            lastStatus = "Hata: PSK geçersiz (32 bayt base64 olmalı)"
            return START_NOT_STICKY
        }
        val p = Store.prefs(this)
        psk = key
        clientId = Store.clientId(this)
        serverPort = srv.port
        full = p.getBoolean("full", true)
        kill = p.getBoolean("kill", true)
        failover = p.getBoolean("failover", true)
        curId = srv.id
        excluded = p.getStringSet("excluded", emptySet()) ?: emptySet()
        bytesUp = 0; bytesDown = 0; lastStatus = ""
        running = true
        state = CONNECTING
        Thread { runTunnel(srv.host) }.start()
        return START_NOT_STICKY
    }

    private fun openSocket() {
        try { sock?.close() } catch (_: Exception) {}
        val s = DatagramSocket()
        protect(s)
        s.connect(serverAddr, serverPort)
        sock = s
    }

    private fun sendInit() {
        val p = Handshake.buildInit(psk, clientId)
        pendings[p.sid] = p
        try { sock?.send(DatagramPacket(p.pkt, p.pkt.size)) } catch (_: Exception) {}
    }

    private fun handleResp(b: ByteArray): Session? {
        if (b.size != 57 || b[0].toInt() != Proto.T_RESP) return null
        val sid = ByteBuffer.wrap(b, 1, 4).int
        val p = pendings[sid] ?: return null
        val sEph = b.copyOfRange(5, 37)
        val shared = ByteArray(32)
        try {
            p.priv.generateSecret(X25519PublicKeyParameters(sEph, 0), shared, 0)
        } catch (e: Exception) { return null }
        val info = Proto.INFO + p.pub + sEph + clientId + Proto.be32(sid)
        val okm = Crypto.hkdf(psk, shared, info, 64)
        val c2s = okm.copyOfRange(0, 32)
        val s2c = okm.copyOfRange(32, 64)
        val ip = Crypto.open(s2c, 0, b.copyOfRange(0, 37), b.copyOfRange(37, 57)) ?: return null
        if (ip.size != 4) return null
        pendings.remove(sid)
        assignedIp = ip.joinToString(".") { (it.toInt() and 255).toString() }
        val s = Session(sid, c2s, s2c)
        prev = cur
        cur = s
        lastRx = System.currentTimeMillis()
        sendData(s, ByteArray(0))
        return s
    }

    private fun sendData(s: Session, payload: ByteArray) {
        val ctr = s.txCtr.incrementAndGet()
        val hdr = ByteArray(13)
        hdr[0] = Proto.T_DATA.toByte()
        System.arraycopy(Proto.be32(s.sid), 0, hdr, 1, 4)
        System.arraycopy(Proto.be64(ctr), 0, hdr, 5, 8)
        val pkt = hdr + Crypto.seal(s.txKey, ctr, hdr, payload)
        try { sock?.send(DatagramPacket(pkt, pkt.size)) } catch (_: Exception) {}
    }

    private fun handleData(buf: ByteArray, n: Int) {
        if (n < 29) return
        val sid = ByteBuffer.wrap(buf, 1, 4).int
        val ctr = ByteBuffer.wrap(buf, 5, 8).long
        if (ctr <= 0) return
        val s = cur?.takeIf { it.sid == sid } ?: prev?.takeIf { it.sid == sid } ?: return
        if (!s.replayOk(ctr)) return
        val pt = Crypto.open(s.rxKey, ctr, buf.copyOfRange(0, 13), buf.copyOfRange(13, n)) ?: return
        s.mark(ctr)
        lastRx = System.currentTimeMillis()
        if (state == RECONNECTING) state = CONNECTED
        if (pt.isNotEmpty()) {
            bytesDown += pt.size
            try { tunOut?.write(pt) } catch (_: Exception) {}
        }
    }

    private fun recv(buf: ByteArray, timeoutMs: Int): Int {
        val s = sock ?: return -1
        return try {
            s.soTimeout = timeoutMs
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            p.length
        } catch (e: SocketTimeoutException) {
            -1
        } catch (e: Exception) {
            if (running) Thread.sleep(200)
            -1
        }
    }

    // Wi-Fi <-> mobil geçişinde hemen yeniden bağlan
    private fun watchNetwork() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (System.currentTimeMillis() - startedAt > 5000) forceReconnect = true
                }
            }
            cm.registerNetworkCallback(req, cb)
            netCb = cb
        } catch (e: Exception) {
            Log.e(TAG, "watchNetwork", e)
        }
    }

    private fun runTunnel(host: String) {
        try {
            serverAddr = InetAddress.getByName(host)
            openSocket()

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 20000
            var first: Session? = null
            while (running && first == null && System.currentTimeMillis() < deadline) {
                sendInit()
                val until = System.currentTimeMillis() + 3000
                while (running && first == null && System.currentTimeMillis() < until) {
                    val n = recv(buf, 1000)
                    if (n > 0 && buf[0].toInt() == Proto.T_RESP) first = handleResp(buf.copyOf(n))
                }
            }
            if (first == null) {
                if (running) lastStatus = "Sunucuya ulaşılamadı veya PSK yanlış"
                return
            }

            val b = Builder().setSession("My VPN").addAddress(assignedIp, 24).setMtu(1380)
            if (full) {
                b.addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
                b.addAddress("fd00:8::2", 128).addRoute("::", 0) // IPv6 sızıntısını engelle
            } else {
                b.addRoute("10.8.0.0", 24)
            }
            for (pkg in excluded) {
                try { b.addDisallowedApplication(pkg) } catch (_: Exception) {}
            }
            val pfd = b.establish()
            if (pfd == null) {
                lastStatus = "VPN arayüzü kurulamadı"
                return
            }
            tun = pfd
            val input = FileInputStream(pfd.fileDescriptor)
            tunOut = FileOutputStream(pfd.fileDescriptor)
            startedAt = System.currentTimeMillis()
            connectedSince = startedAt
            state = CONNECTED
            watchNetwork()

            // UDP -> TUN
            Thread {
                val rb = ByteArray(2048)
                while (running) {
                    val n = recv(rb, 1000)
                    if (n <= 0) continue
                    when (rb[0].toInt()) {
                        Proto.T_DATA -> handleData(rb, n)
                        Proto.T_RESP -> if (handleResp(rb.copyOf(n)) != null) state = CONNECTED
                    }
                }
            }.start()

            // Kontrol: keepalive, yeniden anahtarlama, kopma tespiti, ağ değişimi
            Thread {
                var lastKeep = 0L
                var lastInit = 0L
                var lastFail = 0L
                var lastList = 0L
                while (running) {
                    Thread.sleep(1000)
                    val now = System.currentTimeMillis()
                    val c = cur
                    if (c != null && now - lastKeep >= 15000) { sendData(c, ByteArray(0)); lastKeep = now }
                    if (now - lastList >= 30000) { lastList = now; Updater.refresh(applicationContext) }
                    if (forceReconnect) {
                        forceReconnect = false
                        try { openSocket() } catch (_: Exception) {}
                        sendInit(); lastInit = now
                    }
                    if (now - lastRx > 40000) {
                        if (failover && now - lastFail >= 20000) {
                            lastFail = now
                            if (tryFailover()) return@Thread
                        }
                        if (!kill) {
                            // Kill switch kapalı: tüneli kapat, internet normale dönsün
                            lastStatus = "Bağlantı koptu"
                            cleanup()
                            return@Thread
                        }
                        state = RECONNECTING // kill switch açık: tünel açık kalır, trafik engellenir
                        if (now - lastInit >= 5000) {
                            try { openSocket() } catch (_: Exception) {}
                            sendInit(); lastInit = now
                        }
                    } else if (c != null && now - c.created > 120000 && now - lastInit >= 5000) {
                        sendInit(); lastInit = now // 2 dakikada bir yeni anahtar
                    }
                    pendings.entries.removeIf { now - it.value.t > 20000 }
                }
            }.start()

            // TUN -> UDP
            val tb = ByteArray(2000)
            while (running) {
                val n = input.read(tb)
                if (n < 0) break
                if (n < 20 || (tb[0].toInt() ushr 4) != 4) continue
                val c = cur ?: continue
                bytesUp += n
                sendData(c, tb.copyOf(n))
            }
            if (running) lastStatus = "Bağlantı koptu"
        } catch (e: Exception) {
            Log.e(TAG, "tunnel", e)
            if (running) lastStatus = "Hata: ${e.message}"
        } finally {
            cleanup()
        }
    }

    // Sunucu yanıt vermiyorsa en hızlı diğer sunucuya geç (liste 40 sunucuya kadar)
    private fun tryFailover(): Boolean {
        val all = Store.servers(this).filter { it.id != curId }
        if (all.isEmpty()) return false
        val res = arrayOfNulls<Long>(all.size)
        val ts = all.mapIndexed { i, s ->
            Thread { res[i] = Ping.rtt(s, clientId) { sk -> protect(sk) } }.apply { start() }
        }
        ts.forEach { it.join() }
        var best: ServerCfg? = null
        var bt = Long.MAX_VALUE
        all.forEachIndexed { i, s ->
            val r = res[i]
            if (r != null && r < bt) { bt = r; best = s }
        }
        val b = best ?: return false
        Store.setActive(this, b.id)
        notice = "Sunucu otomatik değiştirildi: ${b.flag} ${b.name}"
        noticeAt = System.currentTimeMillis()
        cleanup()
        startService(Intent(this, MyVpnService::class.java).setAction("START"))
        return true
    }

    private fun cleanup() {
        running = false
        state = OFF
        connectedSince = 0
        try { netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        netCb = null
        try { sock?.close() } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        sock = null; tun = null; tunOut = null
        cur = null; prev = null
        pendings.clear()
    }

    override fun onRevoke() {
        lastStatus = "Bağlantı sistem tarafından kesildi"
        cleanup()
        super.onRevoke()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
