package at.weinbau.reihennav

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lädt Fotos von Schadensmeldungen zum Server (api/foto_upload.php) hoch.
 * Fotos, die (z. B. offline) nicht sofort hochgeladen werden können, landen in
 * einer Warteschlange und werden beim nächsten Versuch nachgereicht. Erst wenn
 * das Foto am Server ist, wird sein Dateiname der Meldung hinzugefügt – so gibt
 * es im Web keine kaputten Bild-Verweise.
 */
object PhotoUpload {

    // Warteschlange: Liste von {meldungId, localPath}
    private val pending = mutableListOf<Pair<String, String>>()
    private var loaded = false

    private fun queueFile() = File(Store.filesDir(), "fotos_pending.json")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val f = queueFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                pending.add(o.getString("meldungId") to o.getString("path"))
            }
        }
    }

    private fun saveQueue() {
        runCatching {
            val arr = JSONArray()
            pending.forEach { (mid, path) ->
                arr.put(JSONObject().put("meldungId", mid).put("path", path))
            }
            queueFile().writeText(arr.toString())
        }
    }

    /** Foto zur Meldung vormerken und Upload versuchen (im Hintergrund aufrufen!) */
    fun addAndUpload(meldungId: String, localPath: String) {
        ensureLoaded()
        pending.add(meldungId to localPath)
        saveQueue()
        processQueue()
    }

    /** Alle offenen Uploads versuchen. Läuft synchron – im Hintergrundthread aufrufen. */
    fun processQueue() {
        ensureLoaded()
        if (!Sync.configured) return
        val base = Store.serverUrl.trimEnd('/')
        val token = Store.serverToken
        val done = mutableListOf<Pair<String, String>>()
        for ((mid, path) in pending.toList()) {
            val file = File(path)
            if (!file.exists()) { done.add(mid to path); continue }  // Datei weg -> aus Queue
            val name = uploadFile(base, token, file)
            if (name != null) {
                // Dateiname der Meldung hinzufügen
                val m = Store.meldungen.firstOrNull { it.id == mid }
                if (m != null && !m.fotos.contains(name)) {
                    m.fotos.add(name)
                    m.updatedAt = System.currentTimeMillis()
                    Store.saveMeldungen()
                }
                runCatching { file.delete() }
                done.add(mid to path)
            }
            // bei Fehler: in der Queue lassen, nächster Versuch später
        }
        if (done.isNotEmpty()) {
            pending.removeAll(done.toSet())
            saveQueue()
            // Meldung(en) synchronisieren, damit der Server die Fotonamen bekommt
            runCatching { Sync.sync { _, _ -> } }
        }
    }

    fun hasPending(): Boolean { ensureLoaded(); return pending.isNotEmpty() }

    /** Ein Foto per multipart/form-data hochladen. Liefert den Serverdateinamen oder null. */
    private fun uploadFile(base: String, token: String, file: File): String? {
        val url = "$base/foto_upload.php"
        val boundary = "----rn" + System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"token\"\r\n\r\n")
                out.writeBytes("$token\r\n")
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"foto\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) {
                val j = JSONObject(body)
                if (j.optBoolean("ok", false)) j.optString("foto").ifBlank { null } else null
            } else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
