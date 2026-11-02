package com.adrestarter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Pantalla mínima: estado de permisos, interruptores, prueba manual y diagnóstico. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var debug: TextView
    private lateinit var extra: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuBridge.init(applicationContext)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun text(t: String) = TextView(this).apply {
            text = t
            setPadding(0, pad / 2, 0, pad / 2)
        }

        fun button(t: String, action: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { action() }
        }

        col.addView(text("Cierra y reabre Spotify cuando suena un anuncio.").apply { textSize = 18f })

        col.addView(Switch(this).apply {
            text = "Activado"
            isChecked = Cfg.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Cfg.setEnabled(this@MainActivity, v) }
        })

        status = text("")
        col.addView(status)

        col.addView(button("1 · Acceso a notificaciones") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(button("2 · Permiso de Shizuku") {
            ShizukuBridge.requestPermission()
        })
        col.addView(button("Abrir Shizuku") {
            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (i != null) startActivity(i)
            else Toast.makeText(this, "Instala Shizuku primero", Toast.LENGTH_LONG).show()
        })
        col.addView(button("Actualizar estado") { refresh() })
        col.addView(button("Info de la app (ajustes restringidos / batería)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })

        col.addView(Switch(this).apply {
            text = "Si no se reanuda solo, abrir Spotify"
            isChecked = Cfg.fallbackOpen(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Cfg.setFallbackOpen(this@MainActivity, v) }
        })

        col.addView(text("Palabras extra que identifican un anuncio (separadas por comas):"))
        extra = EditText(this).apply { setText(Cfg.extraWords(this@MainActivity)) }
        col.addView(extra)
        col.addView(button("Guardar palabras") {
            Cfg.setExtraWords(this, extra.text.toString())
            Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
        })

        col.addView(button("Probar reinicio de Spotify ahora") {
            val svc = AdWatcherService.instance
            if (svc == null) {
                Toast.makeText(this, "Activa primero el acceso a notificaciones", Toast.LENGTH_LONG).show()
            } else {
                svc.restartSpotify()
                Toast.makeText(this, "Reiniciando… mira «Último resultado»", Toast.LENGTH_SHORT).show()
            }
        })

        debug = text("")
        col.addView(debug)

        val scroll = ScrollView(this).apply {
            addView(col)
            // Android 15+ dibuja edge-to-edge: dejamos espacio para las barras del sistema
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // El enlace con Shizuku es asíncrono: refrescamos otra vez un instante después
        status.postDelayed({ refresh() }, 1_200)
    }

    private fun refresh() {
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        fun m(b: Boolean) = if (b) "✅" else "❌"
        val sh = ShizukuBridge.status()
        status.text = "${m(nl)} Acceso a notificaciones\n" +
            "${m(sh == "Listo")} Shizuku: $sh"
        debug.text = "Último metadato visto de Spotify:\n${Cfg.lastSeen(this)}\n\n" +
            "Último resultado:\n${Cfg.lastResult(this)}"
    }
}
