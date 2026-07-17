package at.weinbau.reihennav

import android.util.Base64
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

/**
 * NTRIP-Client: holt RTCM-Korrekturdaten von einem Korrekturdienst und gibt
 * sie an den Bluetooth-Empfaenger weiter. Erst damit erreicht RTK die
 * Zentimeter-Genauigkeit.
 *
 * In Oesterreich sind das z. B. EPOSA oder APOS (BEV) - beide kostenpflichtig.
 * Zugangsdaten (Host, Port, Mountpoint, Benutzer, Passwort) kommen vom Anbieter.
 */
class Ntrip(
    private val host: String,
    private val port: Int,
    private val mount: String,
    private val user: String,
    private val pass: String,
    private val ggaProvider: () -> String,
    private val onRtcm: (ByteArray, Int) -> Unit,
    private val onState: (String) -> Unit
) {
    @Volatile private var running = false
    private var sock: Socket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "ntrip") {
            while (running) {
                try {
                    onState("Korrekturdaten: verbinde …")
                    val s = Socket(host, port)
                    s.soTimeout = 30000
                    sock = s
                    val out: OutputStream = s.getOutputStream()
                    val inp: InputStream = s.getInputStream()

                    val auth = Base64.encodeToString(
                        "$user:$pass".toByteArray(), Base64.NO_WRAP
                    )
                    val req = buildString {
                        append("GET /$mount HTTP/1.1\r\n")
                        append("Host: $host:$port\r\n")
                        append("Ntrip-Version: Ntrip/2.0\r\n")
                        append("User-Agent: NTRIP ReihenNavigator/1.0\r\n")
                        append("Authorization: Basic $auth\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(req.toByteArray()); out.flush()

                    // Antwortkopf lesen
                    val head = StringBuilder()
                    while (running) {
                        val c = inp.read()
                        if (c < 0) break
                        head.append(c.toChar())
                        if (head.endsWith("\r\n\r\n") || head.endsWith("\n\n")) break
                        if (head.length > 2000) break
                    }
                    val h = head.toString()
                    if (!h.contains("200") && !h.contains("ICY")) {
                        onState("Korrekturdienst lehnt ab: " + h.lineSequence().firstOrNull())
                        throw IllegalStateException("NTRIP: $h")
                    }
                    onState("Korrekturdaten aktiv")

                    // Eigene Position regelmaessig melden (fuer VRS noetig)
                    val ggaThread = thread(name = "ntrip-gga") {
                        while (running && !s.isClosed) {
                            runCatching {
                                val g = ggaProvider()
                                if (g.isNotBlank()) {
                                    out.write((g + "\r\n").toByteArray()); out.flush()
                                }
                            }
                            Thread.sleep(10000)
                        }
                    }

                    val buf = ByteArray(4096)
                    while (running) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        if (n > 0) onRtcm(buf, n)
                    }
                    ggaThread.interrupt()
                } catch (e: Exception) {
                    Log.w("Ntrip", "err: ${e.message}")
                    onState("Korrekturdaten getrennt – neuer Versuch …")
                } finally {
                    runCatching { sock?.close() }
                    sock = null
                }
                if (running) Thread.sleep(5000)
            }
            onState("Korrekturdaten aus")
        }
    }

    fun stop() {
        running = false
        runCatching { sock?.close() }
    }
}
