package at.weinbau.reihennav

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Liest NMEA-Daten von einem externen GNSS-/RTK-Empfaenger ueber Bluetooth
 * (SPP / Serial Port Profile). Funktioniert mit Geraeten wie ArduSimple,
 * Emlid Reach, Garmin GLO u.ae., die NMEA seriell ausgeben.
 *
 * Die Fix-Qualitaet aus dem GGA-Satz zeigt an, ob RTK wirklich aktiv ist:
 *   0 = ungueltig, 1 = GPS (~3 m), 2 = DGPS (~1 m),
 *   4 = RTK fixed (1-2 cm), 5 = RTK float (~20-50 cm)
 */
class BtNmea(
    private val mac: String,
    private val onFix: (NmeaFix) -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BtNmea"
    }

    @Volatile private var running = false
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    @Volatile var lastGga: String = ""
        private set

    val connected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread(name = "bt-nmea") {
            while (running) {
                try {
                    onState("Verbinde mit Empfaenger …")
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                        ?: throw IllegalStateException("Kein Bluetooth")
                    val dev: BluetoothDevice = adapter.getRemoteDevice(mac)
                    adapter.cancelDiscovery()
                    val s = dev.createRfcommSocketToServiceRecord(SPP)
                    s.connect()
                    socket = s
                    out = s.outputStream
                    onState("Empfaenger verbunden")

                    val r = BufferedReader(InputStreamReader(s.inputStream, Charsets.US_ASCII), 4096)
                    while (running) {
                        val line = r.readLine() ?: break
                        parse(line.trim())?.let { onFix(it) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "BT: ${e.message}")
                    onState("Empfaenger getrennt – neuer Versuch …")
                } finally {
                    runCatching { socket?.close() }
                    socket = null; out = null
                }
                if (running) Thread.sleep(3000)   // Auto-Reconnect
            }
            onState("Empfaenger aus")
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    /** RTCM-Korrekturdaten (vom NTRIP-Dienst) an den Empfaenger weitergeben */
    fun writeRtcm(data: ByteArray, len: Int) {
        runCatching { out?.write(data, 0, len); out?.flush() }
    }

    private fun parse(line: String): NmeaFix? {
        if (!line.startsWith("$")) return null
        val body = line.substringBefore('*')
        val p = body.split(",")
        if (p.isEmpty()) return null
        val type = p[0].substring(1)

        // GGA: Position + Fix-Qualitaet + Satelliten
        if (type.endsWith("GGA") && p.size >= 10) {
            val lat = nmeaCoord(p.getOrNull(2), p.getOrNull(3)) ?: return null
            val lng = nmeaCoord(p.getOrNull(4), p.getOrNull(5)) ?: return null
            val q = p.getOrNull(6)?.toIntOrNull() ?: 0
            if (q == 0) return null
            lastGga = line
            return NmeaFix(
                lat = lat, lng = lng,
                quality = q,
                sats = p.getOrNull(7)?.toIntOrNull() ?: 0,
                hdop = p.getOrNull(8)?.toDoubleOrNull() ?: 0.0,
                altM = p.getOrNull(9)?.toDoubleOrNull() ?: 0.0,
                speedKmh = null
            )
        }

        // RMC: liefert zusaetzlich die Geschwindigkeit
        if (type.endsWith("RMC") && p.size >= 8 && p.getOrNull(2) == "A") {
            val lat = nmeaCoord(p.getOrNull(3), p.getOrNull(4)) ?: return null
            val lng = nmeaCoord(p.getOrNull(5), p.getOrNull(6)) ?: return null
            val kn = p.getOrNull(7)?.toDoubleOrNull()
            return NmeaFix(
                lat = lat, lng = lng, quality = 1, sats = 0, hdop = 0.0, altM = 0.0,
                speedKmh = kn?.times(1.852)
            )
        }
        return null
    }

    /** NMEA-Format ddmm.mmmm + Himmelsrichtung -> Dezimalgrad */
    private fun nmeaCoord(v: String?, hemi: String?): Double? {
        if (v.isNullOrBlank() || hemi.isNullOrBlank()) return null
        val dot = v.indexOf('.')
        if (dot < 3) return null
        val deg = v.substring(0, dot - 2).toDoubleOrNull() ?: return null
        val min = v.substring(dot - 2).toDoubleOrNull() ?: return null
        var d = deg + min / 60.0
        if (hemi == "S" || hemi == "W") d = -d
        return d
    }
}

data class NmeaFix(
    val lat: Double,
    val lng: Double,
    val quality: Int,
    val sats: Int,
    val hdop: Double,
    val altM: Double,
    val speedKmh: Double?
) {
    val qualityText: String get() = when (quality) {
        1 -> "GPS (~3 m)"
        2 -> "DGPS (~1 m)"
        4 -> "RTK fix (1–2 cm)"
        5 -> "RTK float (~30 cm)"
        else -> "kein Fix"
    }
    val isRtk: Boolean get() = quality == 4 || quality == 5
}
