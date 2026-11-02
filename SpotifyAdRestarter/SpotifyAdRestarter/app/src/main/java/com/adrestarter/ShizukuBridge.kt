package com.adrestarter

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** Conexión con Shizuku: permiso, enlace al servicio privilegiado y llamadas. */
object ShizukuBridge {
    private const val REQ = 1001

    @Volatile private var shell: IShell? = null
    @Volatile private var binding = false
    private var args: Shizuku.UserServiceArgs? = null
    private var started = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            shell = IShell.Stub.asInterface(b)
            binding = false
        }

        override fun onServiceDisconnected(n: ComponentName) {
            shell = null
            binding = false
        }
    }

    @Synchronized
    fun init(ctx: Context) {
        if (started) return
        started = true
        args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
            .processNameSuffix("shell")
            .daemon(false)
            .version(1)
        Shizuku.addBinderReceivedListenerSticky { bind() }
        Shizuku.addBinderDeadListener {
            shell = null
            binding = false
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) bind()
        }
    }

    private fun bind() {
        try {
            if (shell == null && !binding &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) {
                binding = true
                Shizuku.bindUserService(args!!, conn)
            }
        } catch (_: Throwable) {
            binding = false
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ)
            }
        } catch (_: Throwable) {
        }
    }

    fun isReady(): Boolean = shell != null

    fun status(): String = try {
        when {
            !Shizuku.pingBinder() -> "Shizuku no está en marcha"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Falta conceder permiso a esta app"
            shell == null -> {
                bind()
                "Conectando…"
            }
            else -> "Listo"
        }
    } catch (e: Throwable) {
        "Shizuku no disponible"
    }

    private fun call(block: (IShell) -> Boolean, done: (Boolean) -> Unit) {
        val s = shell
        if (s == null) {
            done(false)
            return
        }
        io.execute {
            val ok = try { block(s) } catch (e: Throwable) { false }
            main.post { done(ok) }
        }
    }

    fun forceStop(pkg: String, done: (Boolean) -> Unit) = call({ it.forceStop(pkg) }, done)

    fun startApp(component: String, done: (Boolean) -> Unit) = call({ it.startApp(component) }, done)
}
