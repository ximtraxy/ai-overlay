package dev.aioverlay

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Setup (permissions) + settings (pen-only circling, quick questions). */
class MainActivity : Activity() {
    companion object { const val ASK_MIC = "ask_mic" }

    private lateinit var status: TextView
    private lateinit var promptsBox: EditText
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val pad = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 18f }
        promptsBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prompts(prefs).joinToString("\n"))
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(TextView(this@MainActivity).apply { text = "AI Overlay"; textSize = 28f })
                addView(status)
                addView(Button(this@MainActivity).apply {
                    text = "1. Permetti \"mostra sopra altre app\""
                    setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                })
                addView(Button(this@MainActivity).apply {
                    text = "2. Attiva AI Overlay in Accessibilità"
                    setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                })
                addView(Button(this@MainActivity).apply {
                    text = "3. Consenti il microfono (dettatura nelle chat)"
                    setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
                })
                if (Build.VERSION.SDK_INT >= 33) addView(Button(this@MainActivity).apply {
                    text = "4. Aggiungi l'interruttore rapido (nasconde/mostra la bolla)"
                    setOnClickListener {
                        getSystemService(StatusBarManager::class.java).requestAddTileService(
                            ComponentName(this@MainActivity, ToggleTile::class.java), "AI Overlay",
                            Icon.createWithResource(this@MainActivity, android.R.drawable.ic_menu_view), mainExecutor,
                        ) {}
                    }
                })
                addView(CheckBox(this@MainActivity).apply {
                    text = "Dopo il primo uso della penna, cerchia solo con la penna (ignora dita e palmo)"
                    isChecked = prefs.getBoolean("penOnly", true)
                    setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("penOnly", on).apply() }
                })
                addView(TextView(this@MainActivity).apply { text = "\nDomande rapide dopo il cerchio (una per riga):" })
                addView(promptsBox)
                addView(TextView(this@MainActivity).apply {
                    text = "\nSe l'interruttore in Accessibilità è grigio (\"impostazione con restrizioni\"): " +
                        "Impostazioni → App → AI Overlay → ⋮ → Consenti impostazioni con restrizioni.\n\n" +
                        "Uso:\n• Tocca la bolla AI: apre/chiude il pannello.\n" +
                        "• Tieni premuta la bolla (o ✂): screenshot, cerchi la parte, va nella chat; poi tocca una domanda rapida.\n" +
                        "• 📋: cerchi una parte dello schermo (es. la risposta) e la copi come immagine da incollare negli appunti."
                })
            })
        })
        askMicIfRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        askMicIfRequested(intent)
    }

    private fun askMicIfRequested(i: Intent?) {
        if (i?.getBooleanExtra(ASK_MIC, false) == true) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) = refresh()

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putString("prompts", promptsBox.text.toString()).apply()
    }

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        val overlay = Settings.canDrawOverlays(this)
        val service = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        status.text = "\nSovrapposizione: ${mark(overlay)}\nServizio: ${mark(service)}\nMicrofono: ${mark(mic)}\n"
    }
}
