package com.example.myvpn

import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var timer: TextView
    private lateinit var power: PowerButton
    private lateinit var locFlag: TextView
    private lateinit var locName: TextView
    private lateinit var locSub: TextView
    private lateinit var stats: TextView
    private lateinit var splitRow: TextView
    @Volatile private var searching = false
    private var tick = 0

    private val prepareLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startVpn()
        }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (++tick % 60 == 0) Updater.refresh(applicationContext) // 30 sn
            handler.postDelayed(this, 500)
        }
    }

    private fun lp(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(C.CARD, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Store.prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
            setBackgroundColor(C.BG)
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(C.BG); isFillViewport = true; addView(root) }

        root.addView(text("My VPN", 16f, C.MUTED, true).apply { gravity = Gravity.CENTER },
            lp(-1, -2))
        title = text("Bağlı değil", 26f, C.TEXT, true).apply { gravity = Gravity.CENTER }
        root.addView(title, lp(-1, -2, 24))
        sub = text("", 14f, C.MUTED).apply { gravity = Gravity.CENTER }
        root.addView(sub, lp(-1, -2, 4))

        power = PowerButton(this).apply { setOnClickListener { onPower() } }
        root.addView(power, lp(dp(180), dp(180), 28).apply { gravity = Gravity.CENTER_HORIZONTAL })
        timer = text("", 22f, C.TEXT).apply { gravity = Gravity.CENTER }
        root.addView(timer, lp(-1, -2, 16))

        // Konum kartı
        val loc = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                if (MyVpnService.state != MyVpnService.OFF) toast("Önce bağlantıyı kesin")
                else startActivity(Intent(this@MainActivity, ServersActivity::class.java))
            }
        }
        locFlag = text("⚡", 28f, C.TEXT)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        locName = text("", 16f, C.TEXT, true)
        locSub = text("", 12f, C.MUTED)
        col.addView(locName); col.addView(locSub)
        loc.addView(locFlag)
        loc.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        loc.addView(text("›", 24f, C.MUTED))
        root.addView(loc, lp(-1, -2, 20))

        stats = text("", 13f, C.MUTED).apply { gravity = Gravity.CENTER }
        root.addView(stats, lp(-1, -2, 14))

        // Ayarlar
        val set = card()
        fun switchRow(label: String, key: String, def: Boolean) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(label, 15f, C.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Switch(this).apply {
                isChecked = p.getBoolean(key, def)
                setOnCheckedChangeListener { _, v ->
                    p.edit().putBoolean(key, v).apply()
                    if (MyVpnService.state != MyVpnService.OFF) toast("Değişiklik bir sonraki bağlantıda geçerli olur")
                }
            })
            set.addView(row, lp(-1, -2, if (set.childCount == 0) 0 else 14))
        }
        switchRow("Kill switch (kopunca interneti kes)", "kill", true)
        switchRow("Tüm trafiği tünelden geçir", "full", true)
        switchRow("Sunucu koparsa otomatik değiştir", "failover", true)
        splitRow = text("", 15f, C.TEXT).apply { setOnClickListener { pickApps() } }
        set.addView(splitRow, lp(-1, -2, 14))
        root.addView(set, lp(-1, -2, 14))

        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); Updater.refresh(applicationContext, true); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private fun refresh() {
        val st = MyVpnService.state
        when {
            searching -> {
                title.text = "Aranıyor…"; title.setTextColor(C.AMBER); power.color = C.AMBER; power.setBusy(true)
                sub.text = "En hızlı sunucu seçiliyor"; timer.text = ""
            }
            st == MyVpnService.CONNECTING -> {
                title.text = "Bağlanıyor…"; title.setTextColor(C.AMBER); power.color = C.AMBER; power.setBusy(true)
                sub.text = "Güvenli tünel kuruluyor"; timer.text = ""
            }
            st == MyVpnService.RECONNECTING -> {
                title.text = "Yeniden bağlanıyor…"; title.setTextColor(C.AMBER); power.color = C.AMBER; power.setBusy(true)
                sub.text = "Kill switch etkin: trafik engellendi"; timer.text = ""
            }
            st == MyVpnService.CONNECTED -> {
                title.text = "Bağlı"; title.setTextColor(C.GREEN); power.color = C.GREEN; power.setBusy(false)
                sub.text = if (MyVpnService.notice.isNotEmpty() && System.currentTimeMillis() - MyVpnService.noticeAt < 10000)
                    MyVpnService.notice else "Bağlantınız korunuyor"
                val s = (System.currentTimeMillis() - MyVpnService.connectedSince) / 1000
                timer.text = "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
            }
            else -> {
                title.text = "Bağlı değil"; title.setTextColor(C.TEXT); power.color = C.GRAY; power.setBusy(false)
                sub.text = MyVpnService.lastStatus.ifEmpty { "Bağlanmak için düğmeye dokunun" }
                timer.text = ""
            }
        }
        stats.text = if (st == MyVpnService.OFF) "" else "↓ ${fmt(MyVpnService.bytesDown)}    ↑ ${fmt(MyVpnService.bytesUp)}"

        val sel = Store.selected(this)
        val srv = if (st != MyVpnService.OFF) Store.active(this) else Store.servers(this).find { it.id == sel }
        if (srv == null) {
            locFlag.text = "⚡"; locName.text = "Akıllı konum"
            locSub.text = if (Store.servers(this).isEmpty()) "Önce bir sunucu ekleyin" else "En hızlı sunucu otomatik seçilir"
        } else {
            locFlag.text = srv.flag; locName.text = srv.name; locSub.text = srv.host
        }
        val n = (Store.prefs(this).getStringSet("excluded", emptySet()) ?: emptySet()).size
        splitRow.text = "Uygulama ayır (split tunneling)  •  $n   ›"
    }

    private fun onPower() {
        if (searching) return
        if (MyVpnService.state != MyVpnService.OFF) {
            startService(Intent(this, MyVpnService::class.java).setAction("STOP"))
            return
        }
        val list = Store.servers(this)
        if (list.isEmpty()) {
            toast("Önce bir sunucu ekleyin")
            startActivity(Intent(this, ServersActivity::class.java))
            return
        }
        val sel = Store.selected(this)
        if (sel == "smart") {
            searching = true
            Thread {
                val pick = smartPick(list)
                runOnUiThread { searching = false; Store.setActive(this, pick.id); proceed() }
            }.start()
        } else {
            Store.setActive(this, (list.find { it.id == sel } ?: list[0]).id)
            proceed()
        }
    }

    private fun proceed() {
        val i = VpnService.prepare(this)
        if (i != null) prepareLauncher.launch(i) else startVpn()
    }

    private fun startVpn() {
        startService(Intent(this, MyVpnService::class.java).setAction("START"))
    }

    private fun smartPick(list: List<ServerCfg>): ServerCfg {
        val cid = Store.clientId(this)
        val res = arrayOfNulls<Long>(list.size)
        val ts = list.mapIndexed { i, s -> Thread { res[i] = Ping.rtt(s, cid) }.apply { start() } }
        ts.forEach { it.join() }
        var best = list[0]
        var bt = Long.MAX_VALUE
        list.forEachIndexed { i, s ->
            val r = res[i]
            if (r != null && r < bt) { bt = r; best = s }
        }
        return best
    }

    private fun pickApps() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(q, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val p = Store.prefs(this)
        val ex = (p.getStringSet("excluded", emptySet()) ?: emptySet()).toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in ex }
        AlertDialog.Builder(this)
            .setTitle("VPN dışında kalacak uygulamalar")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Kaydet") { _, _ ->
                val s = HashSet<String>()
                apps.forEachIndexed { i, a -> if (checked[i]) s.add(a.first) }
                p.edit().putStringSet("excluded", s).apply()
                if (MyVpnService.state != MyVpnService.OFF) toast("Değişiklik bir sonraki bağlantıda geçerli olur")
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}
