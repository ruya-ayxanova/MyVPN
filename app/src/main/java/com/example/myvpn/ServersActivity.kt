package com.example.myvpn

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.UUID

class ServersActivity : ComponentActivity() {
    private lateinit var list: LinearLayout
    private lateinit var info: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var gen = 0
    private var sec = 0

    // Her 30 saniyede: uzak listeyi çek, ardından yeniden listele ve ping'le
    private val ticker = object : Runnable {
        override fun run() {
            sec++
            if (sec % 30 == 0) {
                Updater.refresh(applicationContext)
                handler.postDelayed({ render() }, 1500)
            }
            updateInfo()
            handler.postDelayed(this, 1000)
        }
    }

    private fun lp(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
            setBackgroundColor(C.BG)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("‹", 30f, C.TEXT).apply { setPadding(0, 0, dp(16), 0); setOnClickListener { finish() } })
        head.addView(text("Konum seç", 20f, C.TEXT, true))
        root.addView(head)
        info = text("", 12f, C.MUTED)
        root.addView(info, lp(-1, -2, 8))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, lp(-1, -2, 12))
        root.addView(Button(this).apply { text = "+ Sunucu ekle"; setOnClickListener { addDialog() } }, lp(-1, -2, 16))
        root.addView(Button(this).apply { text = "⟳ Uzak liste ayarları (40 sunucu)"; setOnClickListener { remoteDialog() } }, lp(-1, -2, 4))
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        sec = 0
        Updater.refresh(applicationContext, true)
        render()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        gen++
        super.onPause()
    }

    private fun updateInfo() {
        val n = Store.servers(this).size
        val url = Store.prefs(this).getString("list_url", "").orEmpty()
        info.text = if (url.isBlank()) "$n sunucu • uzak liste kapalı" else {
            val a = if (Updater.lastOk == 0L) "henüz yok" else "${(System.currentTimeMillis() - Updater.lastOk) / 1000} sn önce"
            "$n sunucu • liste güncellemesi: $a" + (if (Updater.lastError.isNotEmpty()) "\n⚠ ${Updater.lastError}" else "")
        }
    }

    private fun row(flag: String, name: String, sub: String, selected: Boolean, onClick: () -> Unit,
                    onLong: (() -> Unit)?): Pair<LinearLayout, TextView> {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(if (selected) Integer.parseUnsignedInt("FF252A3D", 16) else C.CARD, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        r.addView(text(flag, 26f, C.TEXT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(name, 16f, C.TEXT, true))
        col.addView(text(sub, 12f, C.MUTED))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        val ping = text(if (selected) "✓" else "", 14f, C.GREEN, true)
        r.addView(ping)
        return r to ping
    }

    private fun render() {
        updateInfo()
        list.removeAllViews()
        val sel = Store.selected(this)
        val servers = Store.servers(this)
        val smart = row("⚡", "Akıllı konum", "En hızlı sunucuyu otomatik seç", sel == "smart",
            { Store.setSelected(this, "smart"); finish() }, null)
        list.addView(smart.first, lp(-1, -2, 0))
        if (servers.isEmpty()) {
            list.addView(text("Henüz sunucu yok. Elle ekleyin veya \"Uzak liste ayarları\" ile 40 sunuculuk bir liste adresi girin.", 13f, C.MUTED),
                lp(-1, -2, 16))
        }
        val cid = Store.clientId(this)
        val myGen = ++gen
        val canPing = MyVpnService.state == MyVpnService.OFF
        for (s in servers) {
            val remote = s.id.startsWith("r:")
            val r = row(s.flag, s.name, s.host + if (remote) "  • uzak liste" else "", sel == s.id,
                { Store.setSelected(this, s.id); finish() },
                { if (remote) Toast.makeText(this, "Uzak listeden gelir, listeden silin", Toast.LENGTH_SHORT).show() else confirmDelete(s) })
            list.addView(r.first, lp(-1, -2, 8))
            val pv = r.second
            if (sel != s.id) pv.text = if (canPing) "…" else "—"
            pv.setTextColor(C.MUTED)
            if (canPing) {
                Thread {
                    val ms = Ping.rtt(s, cid)
                    runOnUiThread {
                        if (myGen != gen) return@runOnUiThread
                        pv.text = if (ms != null) "$ms ms" else "çevrimdışı"
                        pv.setTextColor(when {
                            ms == null -> C.RED
                            ms < 100 -> C.GREEN
                            ms < 250 -> C.AMBER
                            else -> C.RED
                        })
                    }
                }.start()
            }
        }
    }

    private fun confirmDelete(s: ServerCfg) {
        AlertDialog.Builder(this).setTitle("${s.name} silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                Store.saveServers(this, Store.manual(this).filter { it.id != s.id })
                if (Store.selected(this) == s.id) Store.setSelected(this, "smart")
                render()
            }
            .setNegativeButton("İptal", null).show()
    }

    private fun validPsk(s: String) = try { Base64.decode(s.trim(), Base64.DEFAULT).size == 32 } catch (e: Exception) { false }

    private fun remoteDialog() {
        val p = Store.prefs(this)
        val url = EditText(this).apply { hint = "Liste adresi (https://....json)"; setText(p.getString("list_url", "")) }
        val psk = EditText(this).apply { hint = "Ortak PSK (base64)"; setText(p.getString("remote_psk", "")) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(url); addView(psk)
        }
        AlertDialog.Builder(this).setTitle("Uzak sunucu listesi")
            .setMessage("Liste her 30 saniyede otomatik güncellenir (en fazla 40 sunucu). Boş bırakırsanız kapanır.")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                val u = url.text.toString().trim()
                if (u.isNotEmpty() && (!u.startsWith("https://") || !validPsk(psk.text.toString()))) {
                    Toast.makeText(this, "Adres https:// olmalı ve PSK geçerli olmalı", Toast.LENGTH_LONG).show()
                } else {
                    p.edit().putString("list_url", u).putString("remote_psk", psk.text.toString().trim())
                        .apply { if (u.isEmpty()) putString("remote", "[]") }.apply()
                    Updater.refresh(applicationContext, true)
                    handler.postDelayed({ render() }, 1500)
                }
            }
            .setNegativeButton("İptal", null).show()
    }

    private fun addDialog() {
        fun et(h: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply { hint = h; inputType = type }
        val link = et("myvpn:// bağlantısı (varsa sadece bunu yapıştırın)")
        val name = et("Ad (örn. Almanya)")
        val flag = et("Bayrak/emoji (örn. 🇩🇪)")
        val host = et("Sunucu IP / alan adı")
        val port = et("Port (51820)", InputType.TYPE_CLASS_NUMBER)
        val psk = et("PSK (base64)")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            listOf(link, name, flag, host, port, psk).forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle("Sunucu ekle")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Ekle") { _, _ ->
                val cfg: ServerCfg? = if (link.text.isNotBlank()) Store.parseLink(link.text.toString())
                else ServerCfg(UUID.randomUUID().toString(),
                    name.text.toString().ifBlank { host.text.toString() },
                    flag.text.toString().ifBlank { "🌐" },
                    host.text.toString().trim(),
                    port.text.toString().toIntOrNull() ?: 51820,
                    psk.text.toString().trim())
                if (cfg == null || cfg.host.isEmpty() || !validPsk(cfg.psk)) {
                    Toast.makeText(this, "Geçersiz bilgi (host veya PSK hatalı)", Toast.LENGTH_LONG).show()
                } else {
                    val l = Store.manual(this)
                    l.add(cfg)
                    Store.saveServers(this, l)
                    render()
                }
            }
            .setNegativeButton("İptal", null).show()
    }
}
