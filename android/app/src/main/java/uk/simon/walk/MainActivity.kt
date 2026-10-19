package uk.simon.walk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var wake: Switch

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun field(label: String, value: String, secret: Boolean = false): EditText {
            col.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(value); setSingleLine()
                if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                col.addView(this)
            }
        }
        url = field("Server URL", Prefs.serverUrl(this))
        token = field("Token", Prefs.token(this), true)
        wake = Switch(this).apply { text = "Wake word on"; isChecked = Prefs.wakeOn(this@MainActivity); col.addView(this) }
        fun button(label: String, f: () -> Unit) = col.addView(Button(this).apply { text = label; setOnClickListener { f() } })
        button("Save") { save(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }
        button("Test conversation") {
            save()
            withMic { startSvc(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_CONVERSE)) }
        }
        button("Set as assistant") { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        setContentView(ScrollView(this).apply { addView(col) })
        handleIntent(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleIntent(i) }

    /** Boot notification tap: start the listening service from this visible Activity (Android 14+ forbids it from BOOT_COMPLETED). */
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_START_LISTENING, false) == true) withMic { wake.isChecked = true; save(); applyWake() }
    }

    private fun save() = Prefs.save(this, url.text.toString(), token.text.toString(), wake.isChecked)

    private fun startSvc(i: Intent) = try { startForegroundService(i) } catch (e: Exception) {
        Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
    }

    private var pending: (() -> Unit)? = null

    /** Run [action] once RECORD_AUDIO (and POST_NOTIFICATIONS on 13+) are granted; BLUETOOTH_CONNECT is asked for but optional. */
    private fun withMic(action: () -> Unit) {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { action(); return }
        pending = action
        val ask = missing.toMutableList()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            ask += Manifest.permission.BLUETOOTH_CONNECT
        requestPermissions(ask.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        val a = pending; pending = null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) (a ?: { applyWake() })()
    }

    override fun onPause() { super.onPause(); save(); applyWake() }

    /** Start/stop the always-on service to match the switch; ask for battery exemption once. */
    private fun applyWake() {
        val i = Intent(this, WakeService::class.java)
        if (wake.isChecked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSvc(i)
            val pm = getSystemService(PowerManager::class.java)
            if (!Prefs.batteryAsked(this) && !pm.isIgnoringBatteryOptimizations(packageName)) {
                Prefs.setBatteryAsked(this)
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } else if (!wake.isChecked) stopService(i)
    }

    override fun onResume() {
        super.onResume()
        if (wake.isChecked) withMic { applyWake() }
    }

    companion object { const val EXTRA_START_LISTENING = "start_listening" }
}
