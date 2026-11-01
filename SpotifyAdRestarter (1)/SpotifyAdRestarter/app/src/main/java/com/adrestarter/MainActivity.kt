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

/** Pantalla mínima: estado de permisos, interruptor, prueba manual y diagnóstico. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var debug: TextView
    private lateinit var extra: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        col.addView(button("2 · Accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("3 · Mostrar sobre otras apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        col.addView(button("Info de la app (ajustes restringidos / batería)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })

        col.addView(text("Palabras extra que identifican un anuncio (separadas por comas):"))
        extra = EditText(this).apply { setText(Cfg.extraWords(this@MainActivity)) }
        col.addView(extra)
        col.addView(button("Guardar palabras") {
            Cfg.setExtraWords(this, extra.text.toString())
            Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
        })

        col.addView(button("Probar reinicio de Spotify ahora") {
            val svc = RestarterService.instance
            if (svc == null) {
                Toast.makeText(this, "Activa primero la accesibilidad", Toast.LENGTH_LONG).show()
            } else {
                svc.startRestart()
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
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        val acc = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        val ov = Settings.canDrawOverlays(this)
        fun m(b: Boolean) = if (b) "✅" else "❌"
        status.text = "${m(nl)} Acceso a notificaciones\n" +
            "${m(acc)} Accesibilidad\n" +
            "${m(ov)} Mostrar sobre otras apps"
        debug.text = "Último metadato visto de Spotify:\n${Cfg.lastSeen(this)}\n\n" +
            "Último resultado:\n${Cfg.lastResult(this)}"
    }
}
