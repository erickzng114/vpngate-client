package app.vpngate.client

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import kotlin.math.min

class ProbeResult(val state: Int, val rttMs: Int)

/**
 * تست واقعی دسترسی از روی شبکه‌ی خود کاربر.
 *
 * اول یک اتصال TCP (یا بسته‌ی UDP) باز می‌کنیم و بعد اولین بسته‌ی دست‌دهی OpenVPN
 * (P_CONTROL_HARD_RESET_CLIENT_V2) را می‌فرستیم. اگر سرور با
 * P_CONTROL_HARD_RESET_SERVER_V2 جواب بدهد یعنی سرور واقعاً OpenVPN است
 * و فیلترینگ جلوی دست‌دهی را نگرفته است.
 */
object Prober {

    private val random = SecureRandom()

    fun probe(s: Server): ProbeResult {
        return if (s.proto == "tcp") probeTcp(s.remoteHost, s.port) else probeUdp(s.remoteHost, s.port)
    }

    private fun hardReset(withLength: Boolean): ByteArray {
        val body = ByteArray(14)
        body[0] = 0x38 // opcode 7 (HARD_RESET_CLIENT_V2) << 3 | key_id 0
        val sid = ByteArray(8)
        random.nextBytes(sid)
        System.arraycopy(sid, 0, body, 1, 8)
        // body[9] = 0 (تعداد ACK)، body[10..13] = شناسه‌ی بسته = 0
        return if (withLength) byteArrayOf(0x00, 0x0E) + body else body
    }

    private fun probeTcp(host: String, port: Int): ProbeResult {
        val sock = Socket()
        try {
            val t0 = System.nanoTime()
            sock.connect(InetSocketAddress(host, port), 3000)
            val rtt = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)

            sock.soTimeout = 3000
            sock.tcpNoDelay = true
            val out = sock.getOutputStream()
            out.write(hardReset(true))
            out.flush()

            val buf = ByteArray(3)
            var n = 0
            try {
                val inp = sock.getInputStream()
                while (n < 3) {
                    val r = inp.read(buf, n, 3 - n)
                    if (r < 0) break
                    n += r
                }
            } catch (e: IOException) {
                // تایم‌اوت یا قطع شدن؛ یعنی تایید نشد
            }
            val verified = n == 3 && ((buf[2].toInt() and 0xFF) shr 3) == 8
            return ProbeResult(if (verified) Server.VERIFIED else Server.REACHABLE, rtt)
        } catch (e: Exception) {
            return ProbeResult(Server.UNREACHABLE, -1)
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun probeUdp(host: String, port: Int): ProbeResult {
        val sock = DatagramSocket()
        try {
            sock.soTimeout = 2500
            val addr = InetAddress.getByName(host)
            val pkt = hardReset(false)
            val t0 = System.nanoTime()
            sock.send(DatagramPacket(pkt, pkt.size, addr, port))
            val buf = ByteArray(64)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            val rtt = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
            val verified = resp.length >= 1 && ((buf[0].toInt() and 0xFF) shr 3) == 8
            return ProbeResult(if (verified) Server.VERIFIED else Server.UNTESTED, rtt)
        } catch (e: Exception) {
            // جواب نیامد؛ برای UDP نمی‌توان فهمید بسته است یا نه
            return ProbeResult(Server.UNTESTED, -1)
        } finally {
            sock.close()
        }
    }
}

/** امتیاز کیفیت: تاخیر واقعی، سرعت، پایداری، شلوغی و تایید دست‌دهی */
object Ranker {
    fun score(s: Server): Double {
        val rtt = when {
            s.rttMs > 0 -> s.rttMs.toDouble()
            s.pingMs in 1..1500 -> s.pingMs.toDouble()
            else -> 800.0
        }
        var q = 0.0
        q += min(s.speedMbps, 100.0) / 100.0 * 40.0
        q += (1.0 - min(rtt, 1500.0) / 1500.0) * 30.0
        q += min(s.uptimeMs / 86_400_000.0, 30.0) / 30.0 * 8.0
        q -= min(s.sessions, 300) / 300.0 * 5.0
        q += when {
            s.proto == "tcp" && s.port == 443 -> 6.0
            s.proto == "tcp" -> 3.0
            else -> 0.0
        }
        q += when (s.probe) {
            Server.VERIFIED -> 40.0
            Server.REACHABLE -> 12.0
            Server.UNREACHABLE -> -1000.0
            else -> 0.0
        }
        return q
    }
}
