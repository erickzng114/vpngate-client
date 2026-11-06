package app.vpngate.client

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import app.vpngate.client.databinding.ActivityMainBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var repo: ServerRepository
    private lateinit var vpn: OpenVpnController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adapter = ServerAdapter { s -> connectTo(s) }

    private var all: List<Server> = emptyList()
    private var refreshJob: Job? = null
    private var autoJob: Job? = null
    private var waiter: CompletableDeferred<Boolean>? = null
    private var readyAction: (() -> Unit)? = null
    private var currentName: String = ""
    private var countryFilter: String = ""
    private var countryCodes: List<String> = listOf("")

    private val apiPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) {
                afterApiPermission()
            } else {
                readyAction = null
                setStatus("اجازه‌ی دسترسی داده نشد", "برای وصل شدن باید اجازه را تأیید کنید.")
            }
        }

    private val vpnPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) {
                runReady()
            } else {
                readyAction = null
                setStatus("اجازه‌ی VPN داده نشد", "برای وصل شدن باید اجازه‌ی VPN را تأیید کنید.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        repo = ServerRepository(this)
        vpn = OpenVpnController(this)

        b.rvServers.layoutManager = LinearLayoutManager(this)
        b.rvServers.adapter = adapter

        vpn.onStatus = { _, state, msg, level -> runOnUiThread { onVpnStatus(state, msg, level) } }
        vpn.onBound = { runOnUiThread { onServiceBound() } }

        b.btnRefresh.setOnClickListener { refresh() }
        b.btnClipboard.setOnClickListener { importClipboard() }
        b.btnSource.setOnClickListener { askCustomSource() }
        b.btnConnectBest.setOnClickListener { connectBest() }
        b.btnDisconnect.setOnClickListener { disconnect() }
        b.cbShowAll.setOnCheckedChangeListener { _, _ -> render() }

        val cached = repo.loadCached()
        if (cached.isNotEmpty()) all = cached
        setupFilters()
        render()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        vpn.unbind()
    }

    // ───────────────────────── لیست و رتبه‌بندی ─────────────────────────

    private fun ranked(): List<Server> {
        all.forEach { it.quality = Ranker.score(it) }
        return all
            .filter { b.cbShowAll.isChecked || it.probe != Server.UNREACHABLE }
            .filter { passesFilter(it) }
            .sortedWith(
                compareByDescending<Server> { probeRank(it.probe) }
                    .thenByDescending { it.quality }
            )
    }

    /** اول تأییدشده، بعد پاسخ‌گو، بعد تست‌نشده، آخر بسته */
    private fun probeRank(p: Int): Int = when (p) {
        Server.VERIFIED -> 3
        Server.REACHABLE -> 2
        Server.UNTESTED -> 1
        else -> 0
    }

    // ───────────────────────── فیلتر ─────────────────────────

    private fun passesFilter(s: Server): Boolean {
        val okStatus = when (b.cgStatus.checkedChipId) {
            R.id.chipResponsive -> s.probe == Server.VERIFIED || s.probe == Server.REACHABLE
            R.id.chipVerified -> s.probe == Server.VERIFIED
            R.id.chipTcp -> s.proto == "tcp"
            R.id.chipUdp -> s.proto == "udp"
            else -> true
        }
        val okCountry = countryFilter.isEmpty() || s.countryCode.equals(countryFilter, ignoreCase = true)
        return okStatus && okCountry
    }

    private fun setupFilters() {
        b.cgStatus.setOnCheckedStateChangeListener { _, _ -> render() }
        b.spCountry.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val code = countryCodes.getOrNull(pos) ?: ""
                if (code != countryFilter) {
                    countryFilter = code
                    render()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        updateCountries()
    }

    /** لیست کشورها را با تعداد سرورهای هر کشور می‌سازد */
    private fun updateCountries() {
        val groups = all.groupBy { it.countryCode.uppercase() }
            .toList()
            .sortedByDescending { it.second.size }
        val codes = ArrayList<String>()
        val labels = ArrayList<String>()
        codes.add("")
        labels.add("🌐 همه‌ی کشورها (${all.size})")
        for ((code, list) in groups) {
            codes.add(code)
            labels.add("${flagEmoji(code)} ${list.first().countryName} (${list.size})")
        }
        countryCodes = codes
        val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        b.spCountry.adapter = a
        val idx = codes.indexOf(countryFilter)
        if (idx >= 0) {
            b.spCountry.setSelection(idx, false)
        } else {
            countryFilter = ""
            b.spCountry.setSelection(0, false)
        }
    }

    private fun render() {
        adapter.submit(ranked())
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            b.progress.visibility = View.VISIBLE
            b.progress.isIndeterminate = true
            b.tvInfo.text = "در حال دریافت لیست سرورها…"

            val res = repo.fetch()
            if (res == null) {
                b.progress.visibility = View.GONE
                if (all.isEmpty()) {
                    b.tvInfo.text =
                        "دریافت لیست ممکن نشد (احتمالاً فیلتر است). «منبع دلخواه» یا «از کلیپ‌بورد» را امتحان کنید."
                } else {
                    b.tvInfo.text = "دریافت لیست ممکن نشد؛ لیست ذخیره‌شده تست می‌شود."
                    probeAll("ذخیره‌شده")
                }
                return@launch
            }
            all = res.servers
            updateCountries()
            render()
            probeAll(res.label)
        }
    }

    private suspend fun probeAll(sourceLabel: String) {
        val candidates = all.sortedByDescending { it.vpnGateScore }.take(160)
        b.progress.visibility = View.VISIBLE
        b.progress.isIndeterminate = false
        b.progress.max = candidates.size
        b.progress.progress = 0
        try {
            var done = 0
            val sem = Semaphore(32)
            coroutineScope {
                for (s in candidates) {
                    launch(Dispatchers.IO) {
                        val r = sem.withPermit { Prober.probe(s) }
                        withContext(Dispatchers.Main) {
                            s.probe = r.state
                            s.rttMs = r.rttMs
                            done++
                            b.progress.progress = done
                            b.tvInfo.text = "تست سرورها از روی شبکه‌ی شما… $done از ${candidates.size}"
                        }
                    }
                }
            }
            render()
            val verified = all.count { it.probe == Server.VERIFIED }
            val reachable = all.count { it.probe == Server.REACHABLE }
            val dead = all.count { it.probe == Server.UNREACHABLE }
            b.tvInfo.text =
                "منبع: $sourceLabel · ${all.size} سرور · تأییدشده: $verified · پاسخ‌گو: $reachable · بسته: $dead"
        } finally {
            b.progress.visibility = View.GONE
        }
    }

    private fun importClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else {
            ""
        }
        val servers = if (text.isBlank()) emptyList() else repo.importText(text)
        if (servers.isEmpty()) {
            toast("متن کلیپ‌بورد یک لیست معتبر VPN Gate نیست")
            return
        }
        all = servers
        updateCountries()
        render()
        refreshJob?.cancel()
        refreshJob = scope.launch { probeAll("کلیپ‌بورد") }
    }

    private fun askCustomSource() {
        val input = EditText(this)
        input.hint = "https://…/vpngate.csv"
        input.setText(repo.customUrl)
        input.setSingleLine()
        input.layoutDirection = View.LAYOUT_DIRECTION_LTR
        AlertDialog.Builder(this)
            .setTitle("آدرس منبع دلخواه")
            .setMessage("آدرس فایل CSV با همان قالب VPN Gate (مثلاً یک mirror یا کش خودتان).")
            .setView(input)
            .setPositiveButton("ذخیره و دریافت") { _, _ ->
                repo.customUrl = input.text.toString().trim()
                refresh()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    // ───────────────────────── اتصال ─────────────────────────

    private fun ensureReady(action: () -> Unit) {
        readyAction = action
        if (!vpn.isInstalled()) {
            readyAction = null
            showInstallDialog()
            return
        }
        if (vpn.api == null) {
            setStatus("در حال وصل شدن به OpenVPN for Android…", "")
            if (!vpn.bind()) {
                readyAction = null
                showInstallDialog()
            }
            return
        }
        checkApiPermission()
    }

    private fun onServiceBound() {
        if (readyAction != null) checkApiPermission()
    }

    private fun checkApiPermission() {
        val i = vpn.prepareApi()
        if (i != null) apiPermLauncher.launch(i) else afterApiPermission()
    }

    private fun afterApiPermission() {
        vpn.registerCallback()
        val i = vpn.prepareVpn()
        if (i != null) vpnPermLauncher.launch(i) else runReady()
    }

    private fun runReady() {
        val a = readyAction
        readyAction = null
        a?.invoke()
    }

    private fun startServer(s: Server): Boolean {
        currentName = "${flagEmoji(s.countryCode)} ${s.countryName} · ${s.host}"
        setStatus("در حال اتصال…", currentName)
        val ok = vpn.start(s.config)
        if (!ok) setStatus("شروع اتصال ممکن نشد", vpn.lastError)
        return ok
    }

    private fun connectTo(s: Server) {
        autoJob?.cancel()
        autoJob = null
        waiter = null
        ensureReady { startServer(s) }
    }

    /** به ترتیب بهترین سرورها را امتحان می‌کند تا یکی واقعاً وصل شود */
    private fun connectBest() {
        val list = ranked().filter { it.probe != Server.UNREACHABLE }.take(8)
        if (list.isEmpty()) {
            toast(if (all.isEmpty()) "اول لیست سرورها را دریافت کنید" else "با این فیلتر سروری نیست")
            return
        }
        autoJob?.cancel()
        ensureReady {
            autoJob = scope.launch {
                for ((idx, s) in list.withIndex()) {
                    b.tvInfo.text = "تلاش ${idx + 1} از ${list.size}…"
                    val w = CompletableDeferred<Boolean>()
                    waiter = w
                    if (!startServer(s)) {
                        waiter = null
                        continue
                    }
                    val ok = withTimeoutOrNull(30_000L) { w.await() } ?: false
                    waiter = null
                    if (ok) {
                        b.tvInfo.text = "وصل شد."
                        return@launch
                    }
                    vpn.disconnect()
                    delay(2000)
                }
                setStatus(
                    "هیچ‌کدام از سرورهای برتر وصل نشد",
                    "لیست را به‌روزرسانی کنید یا سرور دیگری را دستی انتخاب کنید."
                )
            }
        }
    }

    private fun disconnect() {
        autoJob?.cancel()
        autoJob = null
        waiter = null
        ensureReady {
            vpn.disconnect()
            setStatus("قطع شد", "")
        }
    }

    private fun onVpnStatus(state: String, msg: String, level: String) {
        val text = when (state) {
            "CONNECTED" -> "✅ متصل شدید"
            "NOPROCESS" -> "قطع"
            "EXITING" -> "در حال قطع…"
            "AUTH_FAILED" -> "خطای احراز هویت"
            "NONETWORK" -> "اینترنت در دسترس نیست"
            "CONNECTING", "WAIT", "AUTH", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES",
            "RESOLVE", "TCP_CONNECT", "RECONNECTING" -> "در حال اتصال…"
            else -> state
        }
        setStatus(text, "$currentName\n$msg".trim())
        if (state == "CONNECTED" || level == "LEVEL_CONNECTED") {
            waiter?.complete(true)
        } else if (state == "AUTH_FAILED") {
            waiter?.complete(false)
        }
    }

    // ───────────────────────── کمکی‌ها ─────────────────────────

    private fun setStatus(text: String, detail: String) {
        b.tvStatus.text = text
        b.tvDetail.text = detail
        b.tvDetail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun showInstallDialog() {
        AlertDialog.Builder(this)
            .setTitle("نیاز به OpenVPN for Android")
            .setMessage(
                "این برنامه برای وصل شدن از موتور رایگان و متن‌باز OpenVPN for Android استفاده می‌کند. " +
                    "آن را نصب کنید و دوباره امتحان کنید."
            )
            .setPositiveButton("F-Droid") { _, _ ->
                openUrl("https://f-droid.org/packages/de.blinkt.openvpn/")
            }
            .setNeutralButton("Google Play") { _, _ ->
                openUrl("https://play.google.com/store/apps/details?id=de.blinkt.openvpn")
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast("مرورگری پیدا نشد")
        }
    }
}
