package com.example.myvpn

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ServerCfg(val id: String, val name: String, val flag: String, val host: String, val port: Int, val psk: String)

object Store {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    // Elle eklenen sunucular
    fun manual(c: Context): MutableList<ServerCfg> {
        val out = mutableListOf<ServerCfg>()
        try {
            val a = JSONArray(prefs(c).getString("servers", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(ServerCfg(o.getString("id"), o.getString("name"), o.optString("flag", "🌐"),
                    o.getString("host"), o.getInt("port"), o.getString("psk")))
            }
        } catch (_: Exception) {}
        return out
    }

    // Uzak listeden gelen sunucular (en fazla 40), PSK ortaktır
    fun remote(c: Context): List<ServerCfg> {
        val out = mutableListOf<ServerCfg>()
        val psk = prefs(c).getString("remote_psk", "") ?: ""
        try {
            val a = JSONArray(prefs(c).getString("remote", "[]"))
            for (i in 0 until minOf(a.length(), 40)) {
                val o = a.getJSONObject(i)
                val host = o.getString("host")
                val port = o.getInt("port")
                out.add(ServerCfg("r:$host:$port", o.optString("name", host), o.optString("flag", "🌐"), host, port, psk))
            }
        } catch (_: Exception) {}
        return out
    }

    fun servers(c: Context): List<ServerCfg> = manual(c) + remote(c)

    fun saveServers(c: Context, list: List<ServerCfg>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("flag", it.flag)
                .put("host", it.host).put("port", it.port).put("psk", it.psk))
        }
        prefs(c).edit().putString("servers", a.toString()).apply()
    }

    fun selected(c: Context): String = prefs(c).getString("selected", "smart") ?: "smart"
    fun setSelected(c: Context, id: String) = prefs(c).edit().putString("selected", id).apply()
    fun setActive(c: Context, id: String) = prefs(c).edit().putString("active", id).apply()

    fun active(c: Context): ServerCfg? {
        val l = servers(c)
        val id = prefs(c).getString("active", null)
        return l.find { it.id == id } ?: l.firstOrNull()
    }

    fun clientId(c: Context): ByteArray {
        val p = prefs(c)
        var s = p.getString("cid", null)
        if (s == null) {
            val b = ByteArray(8); SecureRandom().nextBytes(b)
            s = Base64.encodeToString(b, Base64.NO_WRAP)
            p.edit().putString("cid", s).apply()
        }
        return Base64.decode(s, Base64.DEFAULT)
    }

    // myvpn://HOST:PORT?psk=...&name=...&flag=...
    fun parseLink(link: String): ServerCfg? = try {
        val u = Uri.parse(link.trim())
        val host = u.host
        val psk = u.getQueryParameter("psk")
        if (u.scheme != "myvpn" || host.isNullOrEmpty() || psk.isNullOrEmpty()) null
        else ServerCfg(UUID.randomUUID().toString(), u.getQueryParameter("name") ?: host,
            u.getQueryParameter("flag") ?: "🌐", host, if (u.port > 0) u.port else 51820, psk)
    } catch (e: Exception) { null }
}

object Ping {
    // Gerçek bir el sıkışma isteği gönderip yanıt süresini ölçer (VPN kapalıyken kullanın)
    fun rtt(s: ServerCfg, cid: ByteArray, protect: ((DatagramSocket) -> Unit)? = null): Long? = try {
        val psk = Base64.decode(s.psk.trim(), Base64.DEFAULT)
        val sock = DatagramSocket()
        sock.soTimeout = 1500
        protect?.invoke(sock)
        sock.connect(InetAddress.getByName(s.host), s.port)
        val p = Handshake.buildInit(psk, cid)
        val t0 = System.nanoTime()
        sock.send(DatagramPacket(p.pkt, p.pkt.size))
        val buf = ByteArray(128)
        val r = DatagramPacket(buf, buf.size)
        sock.receive(r)
        val ms = (System.nanoTime() - t0) / 1_000_000
        sock.close()
        if (r.length == 57 && buf[0].toInt() == Proto.T_RESP) ms else null
    } catch (e: Exception) { null }
}

// Uzak sunucu listesini periyodik olarak çeker (en fazla 40 sunucu)
object Updater {
    @Volatile var lastOk = 0L
    @Volatile var lastError = ""
    @Volatile private var lastTry = 0L
    @Volatile private var busy = false

    fun refresh(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        val url = (Store.prefs(app).getString("list_url", "") ?: "").trim()
        if (url.isEmpty() || busy) return
        val now = System.currentTimeMillis()
        if (!force && now - lastTry < 25000) return
        lastTry = now
        busy = true
        Thread {
            try {
                if (!url.startsWith("https://")) throw Exception("adres https:// ile başlamalı")
                val u = url + (if (url.contains("?")) "&" else "?") + "t=" + now
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Cache-Control", "no-cache")
                val bytes = conn.inputStream.use { st ->
                    val buf = ByteArray(262144)
                    var n = 0
                    while (n < buf.size) {
                        val r = st.read(buf, n, buf.size - n)
                        if (r <= 0) break
                        n += r
                    }
                    buf.copyOf(n)
                }
                val t = String(bytes, Charsets.UTF_8).trim()
                val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("servers")
                val out = JSONArray()
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    if (out.length() >= 40) break
                    val o = arr.getJSONObject(i)
                    val host = o.optString("host").trim()
                    val port = o.optInt("port", 51820)
                    if (host.isEmpty() || port !in 1..65535 || !seen.add("$host:$port")) continue
                    out.put(JSONObject().put("name", o.optString("name", host))
                        .put("flag", o.optString("flag", "🌐")).put("host", host).put("port", port))
                }
                Store.prefs(app).edit().putString("remote", out.toString()).apply()
                lastOk = System.currentTimeMillis()
                lastError = ""
            } catch (e: Exception) {
                lastError = e.message ?: "güncelleme başarısız"
            } finally {
                busy = false
            }
        }.start()
    }
}
