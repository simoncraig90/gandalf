package uk.simon.walk

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/** Crash reporter: there is no cable to the Mac, so an uncaught exception is written to files/crash.txt and posted to
 *  <serverUrl>/crash (3 s budget) before the default handler kills the process. */
class GandalfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val body = "thread=${t.name}\nsdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL}\n$sw"
                File(filesDir, "crash.txt").writeText(body)
                val th = Thread {
                    try {
                        val c = URL(Prefs.serverUrl(this) + "/crash").openConnection() as HttpURLConnection
                        c.requestMethod = "POST"; c.connectTimeout = 2000; c.readTimeout = 2000; c.doOutput = true
                        c.setRequestProperty("Content-Type", "text/plain")
                        c.outputStream.use { it.write(body.toByteArray()) }
                        c.responseCode
                    } catch (_: Exception) {}
                }
                th.start(); th.join(3000)
            } catch (_: Exception) {}
            previous?.uncaughtException(t, e)
        }
    }
}
