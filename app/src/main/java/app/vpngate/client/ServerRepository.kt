package app.vpngate.client

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class FetchResult(val servers: List<Server>, val label: String)

/**
 * دریافت لیست سرورها. چند منبع هم‌زمان امتحان می‌شود و اولین جواب معتبر برنده است؛
 * پس اگر آدرس اصلی vpngate.net فیلتر باشد، منبع‌های جایگزین (کش GitHub، jsDelivr،
 * آدرس دلخواه) جواب می‌دهند.
 */
class ServerRepository(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val cacheFile = File(ctx.filesDir, "servers.csv")

    var customUrl: String
        get() = prefs.getString("custom_url", "") ?: ""
        set(value) {
            prefs.edit().putString("custom_url", value).apply()
        }

    private fun sources(): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>()
        if (customUrl.isNotBlank()) l.add("منبع دلخواه" to customUrl.trim())
        l.add("VPN Gate" to "https://www.vpngate.net/api/iphone/")
        if (BuildConfig.CACHE_RAW_URL.isNotBlank()) l.add("کش GitHub" to BuildConfig.CACHE_RAW_URL)
        if (BuildConfig.CACHE_CDN_URL.isNotBlank()) l.add("کش jsDelivr" to BuildConfig.CACHE_CDN_URL)
        l.add("VPN Gate (HTTP)" to "http://www.vpngate.net/api/iphone/")
        return l
    }

    suspend fun fetch(): FetchResult? {
        val result = CompletableDeferred<Triple<String, String, String>?>()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val srcs = sources()
        val remaining = AtomicInteger(srcs.size)

        for ((label, url) in srcs) {
            ioScope.launch {
                val text = try {
                    download(url)
                } catch (e: Exception) {
                    null
                }
                if (text != null && looksValid(text)) {
                    result.complete(Triple(label, url, text))
                }
                if (remaining.decrementAndGet() == 0) result.complete(null)
            }
        }

        val r = withTimeoutOrNull(45_000L) { result.await() }
        ioScope.cancel()
        if (r == null) return null

        val servers = withContext(Dispatchers.Default) { VpnGateParser.parse(r.third) }
        if (servers.isEmpty()) return null
        try {
            cacheFile.writeText(r.third)
        } catch (e: IOException) {
            // ذخیره نشد؛ مهم نیست
        }
        return FetchResult(servers, r.first)
    }

    fun loadCached(): List<Server> {
        return try {
            if (cacheFile.exists()) VpnGateParser.parse(cacheFile.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** لیست چسبانده‌شده از کلیپ‌بورد */
    fun importText(text: String): List<Server> {
        val servers = VpnGateParser.parse(text)
        if (servers.isNotEmpty()) {
            try {
                cacheFile.writeText(text)
            } catch (e: IOException) {
            }
        }
        return servers
    }

    private fun looksValid(text: String): Boolean {
        return text.contains("*vpn_servers") || text.contains("#HostName")
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 25_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) VpnGateClient/1.0")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
