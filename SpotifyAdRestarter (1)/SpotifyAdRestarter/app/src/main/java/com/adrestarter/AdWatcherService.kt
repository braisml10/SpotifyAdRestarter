package com.adrestarter

import android.app.KeyguardManager
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
 * Se engancha a la sesión multimedia de Spotify y reacciona solo a cambios de metadatos
 * (sin polling, sin servicio en primer plano, consumo prácticamente nulo).
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
    private var adActive = false
    private var pendingPlay = false
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
        instance = null
    }

    override fun onDestroy() {
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
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        if (!pm.isInteractive || km.isKeyguardLocked) {
            Cfg.setResult(this, "Anuncio con pantalla apagada/bloqueada: no se puede reiniciar")
            return
        }
        if (!Cfg.allowRestart()) {
            Cfg.setResult(this, "Anuncio detectado, pero límite anti-bucle activo")
            return
        }
        val svc = RestarterService.instance
        if (svc == null) {
            Cfg.setResult(this, "Anuncio detectado, pero el servicio de accesibilidad no está activo")
            return
        }
        Cfg.setResult(this, "Anuncio detectado → reiniciando Spotify")
        svc.startRestart()
    }

    // ---------------------------------------------------------------- reanudar

    /** Lo llama RestarterService justo después de relanzar Spotify. */
    fun expectResume() {
        pendingPlay = true
        playTries = 0
        main.removeCallbacks(playStep)
        main.postDelayed(playStep, 2_500)
    }

    private val playStep = object : Runnable {
        override fun run() {
            if (!pendingPlay) return
            val c = controller
            val st = c?.playbackState?.state
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                pendingPlay = false
                return
            }
            playTries++
            if (playTries > 6) {
                pendingPlay = false
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
}
