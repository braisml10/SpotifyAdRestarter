package com.adrestarter

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
 * Detecta anuncios por los metadatos de la sesión de Spotify (sin polling) y, al detectarlo,
 * cierra Spotify vía Shizuku y lo despierta con la tecla Play. Sin UI: funciona con otra app
 * en primer plano y con el móvil bloqueado.
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
    }

    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var adActive = false
    private var pendingPlay = false
    private var canFallback = true
    private var playTries = 0

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

    override fun onListenerConnected() {
        instance = this
        ShizukuBridge.init(applicationContext)
        val m = getSystemService(MediaSessionManager::class.java)
        sessions = m
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            attach(m.getActiveSessions(component))
        } catch (e: SecurityException) {
            Cfg.setResult(this, "Sin acceso a las sesiones multimedia: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(sessionsListener)
        detach()
        main.removeCallbacksAndMessages(null)
        releaseWakeLock()
        instance = null
    }

    override fun onDestroy() {
        releaseWakeLock()
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
        if (!Cfg.allowRestart()) {
            Cfg.setResult(this, "Anuncio detectado, pero límite anti-bucle activo")
            return
        }
        Cfg.setResult(this, "Anuncio detectado → reiniciando Spotify")
        restartSpotify()
    }

    // ---------------------------------------------------------------- reinicio

    /** Cierra Spotify sin mostrar nada. También lo usa el botón de prueba. */
    fun restartSpotify() {
        keepAwake()
        ShizukuBridge.forceStop(Cfg.SPOTIFY) { ok ->
            if (ok) {
                Cfg.setResult(this, "Spotify cerrado en segundo plano")
                expectResume(allowFallback = true)
            } else {
                Cfg.setResult(this, "No pude cerrar Spotify: ${ShizukuBridge.status()}")
                releaseWakeLock()
            }
        }
    }

    /** Mantiene la CPU despierta unos segundos (Spotify ya no la mantiene tras cerrarlo). */
    private fun keepAwake() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adrestarter:restart")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        wl.acquire(30_000)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun expectResume(allowFallback: Boolean) {
        keepAwake()
        pendingPlay = true
        canFallback = allowFallback
        playTries = 0
        main.removeCallbacks(playStep)
        main.postDelayed(playStep, 1_500)
    }

    private val playStep = object : Runnable {
        override fun run() {
            if (!pendingPlay) return
            val c = controller
            val st = c?.playbackState?.state
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                pendingPlay = false
                Cfg.setResult(this@AdWatcherService, "Spotify reanudado")
                releaseWakeLock()
                return
            }
            playTries++
            if (playTries > 6) {
                pendingPlay = false
                fallbackLaunch()
                return
            }
            if (c != null) c.transportControls.play() else pressPlayKey()
            main.postDelayed(this, 1_500)
        }
    }

    private fun pressPlayKey() {
        val am = getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }

    /**
     * Último recurso si la tecla Play no despierta a Spotify: lanzarlo con privilegios de shell
     * (funciona desde segundo plano y con el móvil bloqueado, sin pedir «Mostrar sobre otras apps»).
     */
    private fun fallbackLaunch() {
        if (!canFallback || !Cfg.fallbackOpen(this)) {
            Cfg.setResult(this, "Spotify no despertó con la tecla Play (apertura de respaldo desactivada o ya reintentada)")
            releaseWakeLock()
            return
        }
        val comp = packageManager.getLaunchIntentForPackage(Cfg.SPOTIFY)
            ?.component?.flattenToShortString()
        if (comp == null) {
            Cfg.setResult(this, "Spotify no está instalado")
            releaseWakeLock()
            return
        }
        ShizukuBridge.startApp(comp) { ok ->
            if (ok) {
                Cfg.setResult(this, "Spotify no despertó solo; lo abrí vía Shizuku")
                expectResume(allowFallback = false)
            } else {
                Cfg.setResult(this, "No pude abrir Spotify: ${ShizukuBridge.status()}")
                releaseWakeLock()
            }
        }
    }
}
