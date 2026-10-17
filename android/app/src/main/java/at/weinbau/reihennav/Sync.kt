package at.weinbau.reihennav

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Synchronisierung mit dem eigenen Server (PHP + MySQL).
 *
 * Ablauf pro Sync:
 *  - PUSH: alle lokal seit dem letzten Sync geaenderten Datensaetze senden
 *  - PULL: alle Datensaetze mit srv > since empfangen und einmischen
 *          ("neuere Geraete-Zeit gewinnt")
 *  - since = hoechste erhaltene srv-Nummer merken
 *
 * Netz im Hintergrund, Einsammeln/Einmischen auf dem Hauptthread; das
 * Ergebnis kommt ueber onDone auf dem Hauptthread zurueck.
 */
object Sync {

    @Volatile var busy = false
    @Volatile var lastResult: String = ""

    private fun base() = Store.serverUrl.trimEnd('/')

    /** Anmeldung: Token holen und speichern. onDone(true, meldung) bei Erfolg. */
    fun login(server: String, user: String, pass: String, onDone: (Boolean, String) -> Unit) {
        thread {
            try {
                val url = server.trimEnd('/') + "/login.php"
                val payload = JSONObject().put("username", user).put("password", pass)
                val (code, text) = post(url, payload.toString(), null)
                val j = JSONObject(text)
                if (code == 200 && j.optBoolean("ok")) {
                    Store.serverUrl = server.trimEnd('/')
                    Store.serverToken = j.getString("token")
                    Store.user = j.optString("username", user)
                    Store.syncSince = 0            // beim ersten Mal alles holen
                    Store.lastPush = 0
                    Store.savePrefs()
                    onDone(true, "Angemeldet als ${Store.user}")
                } else {
                    onDone(false, j.optString("error", "Anmeldung fehlgeschlagen"))
                }
            } catch (e: Exception) {
                onDone(false, "Server nicht erreichbar: ${e.message}")
            }
        }
    }

    fun logout() {
        Store.serverToken = ""; Store.syncSince = 0; Store.lastPush = 0
        Store.savePrefs()
    }

    val configured: Boolean get() = Store.serverUrl.isNotBlank() && Store.serverToken.isNotBlank()

    /** Zeitpunkt des letzten erfolgreichen Abgleichs (0 = noch keiner seit App-Start) */
    @Volatile var lastOkAt: Long = 0

    /**
     * Wird nach jedem Abgleich aufgerufen, der Daten geändert hat – egal wer ihn
     * ausgelöst hat (Knopf, 5-Minuten-Takt, Fahrt im Hintergrund). Die Karte
     * hängt sich hier an und zeichnet sich dann neu. Läuft auf dem Hauptthread.
     */
    var onChanged: (() -> Unit)? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var nochmal = false   // während eines Abgleichs kam eine neue Änderung

    /**
     * Ein Sync-Durchlauf. onDone(geaendert?, meldung) – kommt auf dem Hauptthread.
     *
     * Die Daten werden auf dem HAUPTTHREAD eingesammelt und eingemischt (dort
     * ändert auch die Oberfläche die Listen), nur das Netz läuft im Hintergrund.
     * So können Abgleich und Bedienung nie gleichzeitig dieselbe Liste ändern
     * (das konnte früher selten zum Absturz führen).
     */
    fun sync(onDone: (Boolean, String) -> Unit) {
        if (!configured) { main.post { onDone(false, "Kein Server eingerichtet") }; return }
        main.post { syncAufHauptthread(onDone) }
    }

    private fun syncAufHauptthread(onDone: (Boolean, String) -> Unit) {
        if (busy) {
            // läuft schon: danach gleich noch einmal, damit die neue Änderung nicht 5 min wartet
            nochmal = true
            onDone(false, "Sync läuft bereits")
            return
        }
        busy = true
        val t0 = System.currentTimeMillis()
        val payload: String
        try {
            // 1. PUSH-Nutzlast: lokal seit lastPush geaenderte Datensaetze
            val p = JSONObject()
            p.put("token", Store.serverToken)   // Notfall-Token (falls Header geschluckt wird)
            p.put("since", Store.syncSince)
            p.put("fields", changedArray(Store.fields.map { it.toJson() to it.updatedAt }))
            p.put("sessions", changedArray(Store.sessions.map { it.toJson() to it.updatedAt }))
            p.put("notes", changedArray(Store.notes.map { it.toJson() to it.updatedAt }))
            p.put("tasks", changedArray(Store.tasks.map { it.toJson() to it.updatedAt }))
            p.put("meldungen", changedArray(Store.meldungen.map { it.toJson() to it.updatedAt }))
            p.put("ernte", changedArray(Store.ernte.map { it.toJson() to it.updatedAt }))
            p.put("lieferung", changedArray(Store.lieferungen.map { it.toJson() to it.updatedAt }))
            payload = p.toString()
        } catch (e: Exception) {
            busy = false
            onDone(false, "Sync fehlgeschlagen: ${e.message}")
            return
        }
        thread {
            val antwort = try { post(base() + "/sync.php", payload, Store.serverToken) }
                          catch (e: Exception) { null to (e.message ?: "keine Verbindung") }
            main.post { einmischen(antwort, t0, onDone) }
        }
    }

    /** 2. PULL-Antwort auf dem Hauptthread einmischen */
    private fun einmischen(antwort: Pair<Int?, String>, t0: Long, onDone: (Boolean, String) -> Unit) {
        var changed = false
        var meldung: String
        try {
            val (code, text) = antwort
            if (code == null) {
                meldung = "Sync fehlgeschlagen: $text"
            } else if (code == 401) {
                meldung = "Sitzung abgelaufen – neu anmelden"
            } else {
                val j = JSONObject(text)
                if (code != 200 || !j.optBoolean("ok")) {
                    meldung = j.optString("error", "Sync-Fehler ($code)")
                } else {
                    var maxSrv = Store.syncSince
                    val geaendert = HashSet<String>()
                    fun merk(name: String, c: Boolean) { if (c) geaendert.add(name) }
                    val onSrv: (Long) -> Unit = { s -> if (s > maxSrv) maxSrv = s }
                    merk("fields", mergeFields(j.optJSONArray("fields"), onSrv))
                    merk("sessions", mergeSessions(j.optJSONArray("sessions"), onSrv))
                    merk("notes", mergeNotes(j.optJSONArray("notes"), onSrv))
                    merk("tasks", mergeTasks(j.optJSONArray("tasks"), onSrv))
                    merk("meldungen", mergeMeldungen(j.optJSONArray("meldungen"), onSrv))
                    merk("ernte", mergeErnte(j.optJSONArray("ernte"), onSrv))
                    merk("lieferung", mergeLieferung(j.optJSONArray("lieferung"), onSrv))

                    // Sorten- und Typen-Liste vom Server (im Web gepflegt)
                    j.optJSONArray("sorten")?.let { Store.updateSorten(strings(it)) }
                    j.optJSONArray("typen")?.let { Store.updateTypen(strings(it)) }

                    Store.syncSince = maxSrv
                    Store.lastPush = t0
                    Store.savePrefs()
                    // nur die Listen speichern, die sich wirklich geändert haben
                    if ("fields" in geaendert) Store.saveFields()
                    if ("sessions" in geaendert) Store.saveSessions()
                    if ("notes" in geaendert) Store.saveNotes()
                    if ("tasks" in geaendert) Store.saveTasks()
                    if ("meldungen" in geaendert) Store.saveMeldungen()
                    if ("ernte" in geaendert) Store.saveErnte()
                    if ("lieferung" in geaendert) Store.saveLieferungen()
                    changed = geaendert.isNotEmpty()

                    val pushed = j.optInt("pushed", 0)
                    lastResult = "gesendet $pushed"
                    lastOkAt = System.currentTimeMillis()
                    meldung = "Synchronisiert (↑$pushed)"
                }
            }
        } catch (e: Exception) {
            meldung = "Sync fehlgeschlagen: ${e.message}"
        }
        busy = false
        if (changed) runCatching { onChanged?.invoke() }
        onDone(changed, meldung)
        if (nochmal) { nochmal = false; sync { _, _ -> } }
    }

    private fun strings(a: JSONArray): List<String> {
        val list = ArrayList<String>(a.length())
        for (i in 0 until a.length()) list.add(a.optString(i, ""))
        return list
    }

    /** nur Datensaetze mit updatedAt > lastPush senden */
    private fun changedArray(list: List<Pair<JSONObject, Long>>): JSONArray {
        val a = JSONArray()
        for ((json, ua) in list) if (ua > Store.lastPush) a.put(json)
        return a
    }

    private fun mergeFields(arr: JSONArray?, onSrv: (Long) -> Unit): Boolean {
        if (arr == null) return false
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            onSrv(o.optLong("_srv", 0))
            val inc = Field.fromJson(o)
            val cur = Store.fields.firstOrNull { it.id == inc.id }
            if (cur == null) { Store.fields.add(inc); changed = true }
            else if (inc.updatedAt > cur.updatedAt) {
                Store.fields[Store.fields.indexOf(cur)] = inc; changed = true
            }
        }
        return changed
    }

    private fun mergeSessions(arr: JSONArray?, onSrv: (Long) -> Unit): Boolean {
        if (arr == null) return false
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            onSrv(o.optLong("_srv", 0))
            val inc = Session.fromJson(o)
            val cur = Store.sessions.firstOrNull { it.id == inc.id }
            if (cur == null) { Store.sessions.add(inc); changed = true }
            else if (inc.updatedAt > cur.updatedAt) {
                Store.sessions[Store.sessions.indexOf(cur)] = inc; changed = true
            }
        }
        return changed
    }

    private fun mergeNotes(arr: JSONArray?, onSrv: (Long) -> Unit): Boolean {
        if (arr == null) return false
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            onSrv(o.optLong("_srv", 0))
            val inc = Note.fromJson(o)
            val cur = Store.notes.firstOrNull { it.id == inc.id }
            if (cur == null) { Store.notes.add(inc); changed = true }
            else if (inc.updatedAt > cur.updatedAt) {
                Store.notes[Store.notes.indexOf(cur)] = inc; changed = true
            }
        }
        return changed
    }

    private fun mergeTasks(arr: JSONArray?, onSrv: (Long) -> Unit): Boolean {
        if (arr == null) return false
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            onSrv(o.optLong("_srv", 0))
            val inc = Task.fromJson(o)
            val cur = Store.tasks.firstOrNull { it.id == inc.id }
            if (cur == null) { Store.tasks.add(inc); changed = true }
            // Feld für Feld zusammenführen statt die ganze Aufgabe zu ersetzen
            else if (cur.mergeFrom(inc)) changed = true
        }
        return changed
    }

    private fun mergeMeldungen(arr: JSONArray?, onSrv: (Long) -> Unit): Boolean {
        if (arr == null) return false
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            onSrv(o.optLong("_srv", 0))
            val inc = Meldung.fromJson(o)
            val cur = Store.meldungen.firstOrNull { it.id == inc.id }
            if (cur == null) { Store.meldungen.add(inc); changed = true }
            else if (inc.updatedAt > cur.updatedAt) {
                Store.meldungen[Store.meldungen.indexOf(cur)] = inc; changed = true
            }
        }
        return changed
    }

    private fun mergeErnte(arr: JSONArray?, onSrv: (Long) -> Unit): Boolean {
        if (arr == null) return false
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            onSrv(o.optLong("_srv", 0))
            val inc = Ernte.fromJson(o)
            val cur = Store.ernte.firstOrNull { it.id == inc.id }
            if (cur == null) { Store.ernte.add(inc); changed = true }
            else if (inc.updatedAt > cur.updatedAt) {
                Store.ernte[Store.ernte.indexOf(cur)] = inc; changed = true
            }
        }
        return changed
    }

    private fun mergeLieferung(arr: JSONArray?, onSrv: (Long) -> Unit): Boolean {
        if (arr == null) return false
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            onSrv(o.optLong("_srv", 0))
            val inc = Lieferung.fromJson(o)
            val cur = Store.lieferungen.firstOrNull { it.id == inc.id }
            if (cur == null) { Store.lieferungen.add(inc); changed = true }
            else if (inc.updatedAt > cur.updatedAt) {
                Store.lieferungen[Store.lieferungen.indexOf(cur)] = inc; changed = true
            }
        }
        return changed
    }

    /** Eigene Position melden (optional) und alle aktuellen Positionen holen. */
    fun fetchPositions(myLat: Double?, myLng: Double?, acc: Double?): org.json.JSONArray? {
        if (!configured) return null
        return try {
            val b = org.json.JSONObject()
            b.put("token", Store.serverToken)
            if (myLat != null && myLng != null) {
                b.put("lat", myLat); b.put("lng", myLng)
                b.put("acc", acc ?: 0.0); b.put("ts", System.currentTimeMillis())
            }
            val (code, text) = post(base() + "/pos.php", b.toString(), Store.serverToken)
            if (code in 200..299) org.json.JSONObject(text).optJSONArray("positions") else null
        } catch (e: Exception) { null }
    }

    /** Nur die eigene Position melden (Antwort egal), im Hintergrund. */
    fun uploadPosition(lat: Double, lng: Double, acc: Double) {
        if (!configured) return
        Thread { runCatching { fetchPositions(lat, lng, acc) } }.start()
    }

    /** HTTP POST mit JSON; liefert (statuscode, antworttext) */
    private fun post(urlStr: String, jsonBody: String, token: String?): Pair<Int, String> {
        val url = URL(urlStr)
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = stream.bufferedReader().use(BufferedReader::readText)
            return code to text
        } finally {
            c.disconnect()
        }
    }
}
