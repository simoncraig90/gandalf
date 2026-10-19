package uk.simon.walk

import android.content.Context

/** SharedPreferences wrapper. Nothing secret is baked into the APK. */
object Prefs {
    const val DEFAULT_URL = "http://100.98.119.35:8846"
    private fun sp(c: Context) = c.getSharedPreferences("walk", Context.MODE_PRIVATE)

    fun serverUrl(c: Context) = sp(c).getString("serverUrl", DEFAULT_URL) ?: DEFAULT_URL
    fun token(c: Context) = sp(c).getString("token", "") ?: ""
    fun wakeOn(c: Context) = sp(c).getBoolean("wakeOn", false)
    fun batteryAsked(c: Context) = sp(c).getBoolean("batteryAsked", false)

    fun save(c: Context, url: String, token: String, wake: Boolean) {
        sp(c).edit().putString("serverUrl", url.trim().trimEnd('/'))
            .putString("token", token.trim()).putBoolean("wakeOn", wake).apply()
    }

    fun setBatteryAsked(c: Context) = sp(c).edit().putBoolean("batteryAsked", true).apply()
}
