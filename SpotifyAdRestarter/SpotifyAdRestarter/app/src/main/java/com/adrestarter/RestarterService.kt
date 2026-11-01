package com.adrestarter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Abre «Información de la app» de Spotify, pulsa «Forzar detención», confirma
 * y vuelve a abrir Spotify. Solo procesa eventos mientras hay un reinicio en curso.
 */
class RestarterService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RestarterService? = null

        private val FORCE_STOP = listOf(
            "Forzar detención", "Forzar parada", "Forzar cierre",
            "Força l'aturada", "Força l’aturada", "Força aturada",
            "Force stop"
        )
        private val OK = listOf("Aceptar", "Acceptar", "OK", "Sí", "Si")
        private val CANCEL = listOf("Cancelar", "Cancel·lar", "Cancel")
    }

    private enum class St { IDLE, WAIT_INFO, WAIT_CONFIRM, CLOSING }

    private var st = St.IDLE
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        fail("Tiempo agotado: no encontré el botón «Forzar detención» (¿otro idioma?)")
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    /** Inicia el reinicio. Devuelve false si ya hay uno en curso. */
    fun startRestart(): Boolean {
        if (st != St.IDLE) return false
        st = St.WAIT_INFO
        main.postDelayed(timeout, 8_000)
        return try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${Cfg.SPOTIFY}")
                ).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
                )
            )
            true
        } catch (e: Exception) {
            fail("No pude abrir Ajustes: ${e.message}")
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (st == St.IDLE || st == St.CLOSING) return
        val root = rootInActiveWindow ?: return

        when (st) {
            St.WAIT_INFO -> {
                val btn = findClickable(root, FORCE_STOP) ?: return
                if (!btn.isEnabled) {
                    // Botón desactivado = Spotify ya no está en ejecución: solo lo abrimos
                    reopen()
                } else {
                    st = St.WAIT_CONFIRM
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            St.WAIT_CONFIRM -> {
                // Botón positivo del diálogo (id estándar de Android)
                var ok = root.findAccessibilityNodeInfosByViewId("android:id/button1")
                    .firstOrNull()
                // Respaldo por texto, solo si realmente hay un diálogo (existe «Cancelar»)
                if (ok == null && findClickable(root, CANCEL) != null) {
                    ok = findClickable(root, FORCE_STOP + OK)
                }
                if (ok != null && ok.isEnabled) {
                    st = St.CLOSING
                    ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    main.postDelayed({ reopen() }, 600)
                }
            }
            else -> {}
        }
    }

    private fun reopen() {
        main.removeCallbacks(timeout)
        st = St.IDLE
        val i = packageManager.getLaunchIntentForPackage(Cfg.SPOTIFY)
        if (i == null) {
            Cfg.setResult(this, "Spotify no está instalado")
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i)
            AdWatcherService.instance?.expectResume()
            Cfg.setResult(this, "Spotify cerrado y reabierto")
        } catch (e: Exception) {
            Cfg.setResult(this, "No pude reabrir Spotify: ${e.message}")
        }
    }

    private fun fail(msg: String) {
        main.removeCallbacks(timeout)
        st = St.IDLE
        Cfg.setResult(this, msg)
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Busca un nodo por texto y sube hasta el ancestro pulsable. */
    private fun findClickable(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            for (n in root.findAccessibilityNodeInfosByText(t)) {
                var x: AccessibilityNodeInfo? = n
                while (x != null && !x.isClickable) x = x.parent
                if (x != null) return x
            }
        }
        return null
    }
}
