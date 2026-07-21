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
 * Laeuft immer im Hintergrund-Thread; das Ergebnis kommt ueber onDone
 * zurueck auf den Hauptthread (per Handler in der Activity).
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

    /** Ein Sync-Durchlauf. onDone(geaendert?, meldung). */
    fun sync(onDone: (Boolean, String) -> Unit) {
        if (!configured) { onDone(false, "Kein Server eingerichtet"); return }
        if (busy) { onDone(false, "Sync läuft bereits"); return }
        busy = true
        thread {
            try {
                val t0 = System.currentTimeMillis()

                // 1. PUSH-Nutzlast: lokal seit lastPush geaenderte Datensaetze
                val payload = JSONObject()
                payload.put("token", Store.serverToken)   // Notfall-Token (falls Header geschluckt wird)
                payload.put("since", Store.syncSince)
                payload.put("fields", changedArray(Store.fields.map { it.toJson() to it.updatedAt }))
                payload.put("sessions", changedArray(Store.sessions.map { it.toJson() to it.updatedAt }))
                payload.put("notes", changedArray(Store.notes.map { it.toJson() to it.updatedAt }))
                payload.put("tasks", changedArray(Store.tasks.map { it.toJson() to it.updatedAt }))
                payload.put("meldungen", changedArray(Store.meldungen.map { it.toJson() to it.updatedAt }))

                val (code, text) = post(base() + "/sync.php", payload.toString(), Store.serverToken)
                if (code == 401) { busy = false; onDone(false, "Sitzung abgelaufen – neu anmelden"); return@thread }
                val j = JSONObject(text)
                if (code != 200 || !j.optBoolean("ok")) {
                    busy = false; onDone(false, j.optString("error", "Sync-Fehler ($code)")); return@thread
                }

                // 2. PULL einmischen
                var maxSrv = Store.syncSince
                var changed = false
                changed = mergeFields(j.optJSONArray("fields")) { s -> if (s > maxSrv) maxSrv = s } || changed
                changed = mergeSessions(j.optJSONArray("sessions")) { s -> if (s > maxSrv) maxSrv = s } || changed
                changed = mergeNotes(j.optJSONArray("notes")) { s -> if (s > maxSrv) maxSrv = s } || changed
                changed = mergeTasks(j.optJSONArray("tasks")) { s -> if (s > maxSrv) maxSrv = s } || changed
                changed = mergeMeldungen(j.optJSONArray("meldungen")) { s -> if (s > maxSrv) maxSrv = s } || changed

                Store.syncSince = maxSrv
                Store.lastPush = t0
                Store.savePrefs()
                if (changed) { Store.saveFields(); Store.saveSessions(); Store.saveNotes(); Store.saveTasks(); Store.saveMeldungen() }

                busy = false
                val pushed = j.optInt("pushed", 0)
                lastResult = "gesendet $pushed"
                onDone(changed, "Synchronisiert (↑$pushed)")
            } catch (e: Exception) {
                busy = false
                onDone(false, "Sync fehlgeschlagen: ${e.message}")
            }
        }
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
            else if (inc.updatedAt > cur.updatedAt) {
                Store.tasks[Store.tasks.indexOf(cur)] = inc; changed = true
            }
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
