package app.vpngate.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback

/**
 * اتصال به موتور OpenVPN for Android از طریق API رسمی خودش (AIDL).
 * فایل‌های AIDL هنگام ساخت با scripts/fetch_aidl.sh از مخزن ics-openvpn گرفته می‌شوند.
 */
class OpenVpnController(private val ctx: Context) {

    companion object {
        const val PKG = "de.blinkt.openvpn"
    }

    @Volatile
    var api: IOpenVPNAPIService? = null

    var onBound: (() -> Unit)? = null
    var onStatus: ((String, String, String, String) -> Unit)? = null
    var lastError: String = ""

    private var bound = false
    private var callbackRegistered = false

    private val callback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            onStatus?.invoke(uuid ?: "", state ?: "", message ?: "", level ?: "")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            api = IOpenVPNAPIService.Stub.asInterface(service)
            onBound?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
            callbackRegistered = false
        }
    }

    fun isInstalled(): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(PKG, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun bind(): Boolean {
        val i = Intent(IOpenVPNAPIService::class.java.name)
        i.setPackage(PKG)
        bound = try {
            ctx.bindService(i, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            lastError = e.message ?: ""
            false
        }
        return bound
    }

    fun unbind() {
        try {
            api?.unregisterStatusCallback(callback)
        } catch (e: Exception) {
        }
        if (bound) {
            try {
                ctx.unbindService(connection)
            } catch (e: Exception) {
            }
        }
        bound = false
        api = null
        callbackRegistered = false
    }

    /** اگر null برگردد یعنی اجازه قبلاً داده شده است */
    fun prepareApi(): Intent? {
        return try {
            api?.prepare(ctx.packageName)
        } catch (e: Exception) {
            lastError = e.message ?: ""
            null
        }
    }

    /** اگر null برگردد یعنی اجازه‌ی VPN سیستم قبلاً داده شده است */
    fun prepareVpn(): Intent? {
        return try {
            api?.prepareVPNService()
        } catch (e: Exception) {
            lastError = e.message ?: ""
            null
        }
    }

    fun registerCallback() {
        if (callbackRegistered) return
        try {
            api?.registerStatusCallback(callback)
            callbackRegistered = true
        } catch (e: Exception) {
            lastError = e.message ?: ""
        }
    }

    fun start(config: String): Boolean {
        val a = api ?: return false
        return try {
            a.startVPN(config)
            true
        } catch (e: Exception) {
            lastError = e.message ?: ""
            false
        }
    }

    fun disconnect() {
        try {
            api?.disconnect()
        } catch (e: Exception) {
            lastError = e.message ?: ""
        }
    }
}
