package com.adrestarter

import android.app.ActivityManager
import android.content.ComponentName
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.view.KeyEvent

/**
 * Todo en local y sin interfaz:
 *  1. Detecta el anuncio por los metadatos de la sesión multimedia de Spotify (por eventos, sin polling).
 *  2. Pausa Spotify (así deja de ser un servicio en primer plano) y lo cierra con
 *     ActivityManager.killBackgroundProcesses (permiso normal, sin Shizuku ni accesibilidad).
 *  3. Lo reanuda con la tecla Play multimedia: el sistema lo arranca en segundo plano,
 *     sin abrir ninguna pantalla. Funciona con otra app delante y con el móvil bloqueado.
 */
class AdWatcherService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: AdWatcherService? = null

        private const val AD_FLAG_KEY = "android.media.metadata.ADVERTISEMENT"
        private val TEXT_KEYS = listOf(
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE
        )

        private const val FIRST_KILL_DELAY_MS = 700L   // tras pausar, antes del primer intento
        private const val KILL_RETRY_MS = 1_000L       // entre intentos de cierre
        private const val MAX_KILLS = 6
        private const val FIRST_PLAY_DELAY_MS = 1_000L // tras cerrarse, antes de la primera tecla Play
        private const val PLAY_RETRY_MS = 1_500L
        private const val MAX_PLAYS = 8
        private const val WAKE_MS = 30_000L
    }

    private enum class St { IDLE, CLOSING, REOPENING }

    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var adActive = false
    private var st = St.IDLE
    private var tries = 0

    private val component by lazy { ComponentName(this, AdWatcherService::class.java) }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            evaluate(metadata)
        }

        override fun onSessionDestroyed() {
            detach()
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onListenerConnected() {
        instance = this
        val m = getSystemService(MediaSessionManager::class.java)
        sessions = m
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            attach(m.getActiveSessions(component))
        } catch (e: SecurityException) {
            Cfg.log(this, "Sin acceso a las sesiones multimedia: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(sessionsListener)
        detach()
        stopMachine()
        instance = null
        // Si el sistema nos desconectó (p. ej. ahorro de batería), pedimos volver a enlazar
        try {
            NotificationListenerService.requestRebind(component)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        stopMachine()
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sesiones

    private fun attach(list: List<MediaController>?) {
        val c = list?.firstOrNull { it.packageName == Cfg.SPOTIFY }
        if (c?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = c
        adActive = false
        if (c != null) {
            c.registerCallback(callback, main)
            evaluate(c.metadata)
        }
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
        adActive = false
    }

    // ---------------------------------------------------------------- detección

    private fun isAd(md: MediaMetadata?): Boolean {
        if (md == null) return false
        if (md.getLong(AD_FLAG_KEY) == 1L) return true
        val id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (id != null && id.startsWith("spotify:ad:")) return true
        val words = Cfg.adWords(this)
        for (k in TEXT_KEYS) {
            val v = md.getString(k) ?: continue
            if (Cfg.norm(v) in words) return true
        }
        return false
    }

    private fun evaluate(md: MediaMetadata?) {
        val ad = isAd(md)
        if (md != null) {
            Cfg.setLastSeen(
                this,
                "título=${md.getString(MediaMetadata.METADATA_KEY_TITLE)}\n" +
                    "artista=${md.getString(MediaMetadata.METADATA_KEY_ARTIST)}\n" +
                    "id=${md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)}\n" +
                    "marcaAnuncio=${md.getLong(AD_FLAG_KEY)}  →  esAnuncio=$ad"
            )
        }
        if (ad && !adActive) {
            adActive = true
            onAdStarted()
        } else if (!ad) {
            adActive = false
        }
    }

    private fun onAdStarted() {
        if (!Cfg.enabled(this)) return
        if (st != St.IDLE) return // ya hay un reinicio en curso
        if (!Cfg.allowRestart()) {
            Cfg.log(this, "Anuncio detectado, pero límite anti-bucle activo")
            return
        }
        Cfg.log(this, "Anuncio detectado")
        restartSpotify()
    }

    // ---------------------------------------------------------------- 1) cerrar

    /** Inicia pausa + cierre + reanudación. También lo usa el botón de prueba. */
    fun restartSpotify() {
        if (st != St.IDLE) {
            Cfg.log(this, "Ya hay un reinicio en curso")
            return
        }
        val c = controller
        if (c == null) {
            Cfg.log(this, "Spotify no tiene sesión multimedia activa: nada que reiniciar")
            return
        }
        st = St.CLOSING
        tries = 0
        keepAwake()
        Cfg.log(this, "Pausando Spotify para poder cerrarlo")
        c.transportControls.pause()
        main.postDelayed(closeStep, FIRST_KILL_DELAY_MS)
    }

    private val closeStep = object : Runnable {
        override fun run() {
            if (st != St.CLOSING) return
            val c = controller
            if (c == null) {
                // La sesión desapareció = el proceso de Spotify ha muerto
                Cfg.log(this@AdWatcherService, "Spotify cerrado (intentos: $tries)")
                startReopen()
                return
            }
            if (tries >= MAX_KILLS) {
                Cfg.log(
                    this@AdWatcherService,
                    "Android no dejó cerrar Spotify (sigue protegido o en primer plano). Lo reanudo sin reiniciar"
                )
                c.transportControls.play()
                finish()
                return
            }
            tries++
            getSystemService(ActivityManager::class.java).killBackgroundProcesses(Cfg.SPOTIFY)
            main.postDelayed(this, KILL_RETRY_MS)
        }
    }

    // ---------------------------------------------------------------- 2) reanudar

    private fun startReopen() {
        st = St.REOPENING
        tries = 0
        keepAwake()
        main.postDelayed(reopenStep, FIRST_PLAY_DELAY_MS)
    }

    private val reopenStep = object : Runnable {
        override fun run() {
            if (st != St.REOPENING) return
            val c = controller
            val ps = c?.playbackState?.state
            if (ps == PlaybackState.STATE_PLAYING || ps == PlaybackState.STATE_BUFFERING) {
                Cfg.log(this@AdWatcherService, "Spotify reanudado en segundo plano")
                finish()
                return
            }
            if (tries >= MAX_PLAYS) {
                Cfg.log(
                    this@AdWatcherService,
                    "Spotify cerrado, pero la tecla Play no lo despertó. Ábrelo a mano"
                )
                finish()
                return
            }
            tries++
            if (c != null) c.transportControls.play() else pressPlayKey()
            main.postDelayed(this, PLAY_RETRY_MS)
        }
    }

    /** El sistema entrega la tecla al último reproductor usado y lo arranca aunque esté cerrado. */
    private fun pressPlayKey() {
        val am = getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }

    // ---------------------------------------------------------------- utilidades

    private fun finish() {
        st = St.IDLE
        main.removeCallbacks(closeStep)
        main.removeCallbacks(reopenStep)
        releaseWakeLock()
    }

    private fun stopMachine() {
        main.removeCallbacksAndMessages(null)
        st = St.IDLE
        releaseWakeLock()
    }

    /** Mantiene la CPU despierta unos segundos (tras cerrar Spotify, nadie más la mantiene). */
    private fun keepAwake() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adrestarter:restart")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        wl.acquire(WAKE_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
