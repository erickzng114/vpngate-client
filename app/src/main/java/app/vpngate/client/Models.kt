package app.vpngate.client

import android.util.Base64

/** یک سرور از لیست VPN Gate */
class Server(
    val host: String,
    val ip: String,
    val remoteHost: String,
    val countryCode: String,
    val countryName: String,
    val vpnGateScore: Long,
    val pingMs: Int,
    val speedBps: Long,
    val sessions: Int,
    val uptimeMs: Long,
    val logPolicy: String,
    val operator: String,
    val proto: String,
    val port: Int,
    val config: String
) {
    /** نتیجه‌ی تست از روی گوشی کاربر */
    var probe: Int = UNTESTED

    /** تاخیر واقعی از گوشی تا سرور (میلی‌ثانیه)، یا -1 اگر تست نشده */
    var rttMs: Int = -1

    var quality: Double = 0.0

    val speedMbps: Double
        get() = speedBps / 1_000_000.0

    companion object {
        const val UNTESTED = 0
        const val UNREACHABLE = 1
        const val REACHABLE = 2
        const val VERIFIED = 3
    }
}

fun flagEmoji(countryCode: String): String {
    val cc = countryCode.trim().uppercase()
    if (cc.length != 2 || cc[0] !in 'A'..'Z' || cc[1] !in 'A'..'Z') return "🌐"
    val a = Character.toChars(0x1F1E6 + (cc[0] - 'A'))
    val b = Character.toChars(0x1F1E6 + (cc[1] - 'A'))
    return String(a) + String(b)
}

object VpnGateParser {

    /**
     * قالب CSV لیست VPN Gate:
     * HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,
     * TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
     */
    fun parse(csv: String): List<Server> {
        val out = ArrayList<Server>()
        for (raw in csv.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue

            val p = line.split(",")
            if (p.size < 15) continue

            val config = decode(p.last().trim()) ?: continue

            var proto = "tcp"
            var port = 443
            var remoteHost = p[1].trim()
            var gotRemote = false
            for (l in config.lineSequence()) {
                val t = l.trim()
                if (t.startsWith("proto ")) {
                    proto = if (t.substring(6).trim().startsWith("udp")) "udp" else "tcp"
                } else if (!gotRemote && t.startsWith("remote ")) {
                    val f = t.split(Regex("\\s+"))
                    if (f.size >= 3) {
                        remoteHost = f[1]
                        port = f[2].toIntOrNull() ?: port
                        gotRemote = true
                    }
                }
            }
            if (!gotRemote) continue

            out.add(
                Server(
                    host = p[0].trim(),
                    ip = p[1].trim(),
                    remoteHost = remoteHost,
                    countryCode = p[6].trim(),
                    countryName = p[5].trim(),
                    vpnGateScore = p[2].trim().toLongOrNull() ?: 0L,
                    pingMs = p[3].trim().toIntOrNull() ?: 9999,
                    speedBps = p[4].trim().toLongOrNull() ?: 0L,
                    sessions = p[7].trim().toIntOrNull() ?: 0,
                    uptimeMs = p[8].trim().toLongOrNull() ?: 0L,
                    logPolicy = p[11].trim(),
                    operator = p[12].trim(),
                    proto = proto,
                    port = port,
                    config = config
                )
            )
        }
        return out
    }

    private fun decode(b64: String): String? {
        return try {
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).replace("\r\n", "\n")
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
