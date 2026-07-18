package at.weinbau.reihennav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Speicher: eine JSON-Datei je Sammlung im App-Verzeichnis.
 * Bewusst einfach gehalten - das Dateiformat ist identisch zum Export der
 * Web-Version, damit deine bestehenden Felder uebernommen werden koennen.
 */
object Store {

    lateinit var ctx: Context
    var fields = mutableListOf<Field>()
    var sessions = mutableListOf<Session>()
    var notes = mutableListOf<Note>()

    var widthM: Double = 2.0
    var night: Boolean = false
    var user: String = "Ich"
    var btMac: String = ""
    var btName: String = ""
    var serverUrl: String = ""
    var serverToken: String = ""
    var syncSince: Long = 0
    var lastPush: Long = 0
    var ntripHost: String = ""
    var ntripPort: Int = 2101
    var ntripMount: String = ""
    var ntripUser: String = ""
    var ntripPass: String = ""

    private fun f(name: String) = File(ctx.filesDir, name)

    fun init(c: Context) {
        ctx = c.applicationContext
        load()
    }

    fun load() {
        runCatching {
            val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            widthM = p.getFloat("widthM", 2.0f).toDouble()
            night = p.getBoolean("night", false)
            user = p.getString("user", "Ich") ?: "Ich"
            btMac = p.getString("btMac", "") ?: ""
            btName = p.getString("btName", "") ?: ""
            serverUrl = p.getString("serverUrl", "") ?: ""
            serverToken = p.getString("serverToken", "") ?: ""
            syncSince = p.getLong("syncSince", 0)
            lastPush = p.getLong("lastPush", 0)
            ntripHost = p.getString("ntripHost", "") ?: ""
            ntripPort = p.getInt("ntripPort", 2101)
            ntripMount = p.getString("ntripMount", "") ?: ""
            ntripUser = p.getString("ntripUser", "") ?: ""
            ntripPass = p.getString("ntripPass", "") ?: ""
        }
        fields = readList("fields.json") { Field.fromJson(it) }
        sessions = readList("sessions.json") { Session.fromJson(it) }
        notes = readList("notes.json") { Note.fromJson(it) }
    }

    fun savePrefs() {
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putFloat("widthM", widthM.toFloat())
            .putBoolean("night", night)
            .putString("user", user)
            .putString("btMac", btMac)
            .putString("btName", btName)
            .putString("serverUrl", serverUrl)
            .putString("serverToken", serverToken)
            .putLong("syncSince", syncSince)
            .putLong("lastPush", lastPush)
            .putString("ntripHost", ntripHost)
            .putInt("ntripPort", ntripPort)
            .putString("ntripMount", ntripMount)
            .putString("ntripUser", ntripUser)
            .putString("ntripPass", ntripPass)
            .apply()
    }

    private fun <T> readList(name: String, conv: (JSONObject) -> T): MutableList<T> {
        val out = mutableListOf<T>()
        runCatching {
            val file = f(name)
            if (!file.exists()) return out
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                runCatching { out.add(conv(arr.getJSONObject(i))) }
            }
        }
        return out
    }

    private fun writeList(name: String, arr: JSONArray) {
        runCatching {
            val tmp = f("$name.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f(name))   // atomar -> kein Datenverlust bei Absturz
        }
    }

    fun saveFields() = writeList("fields.json", JSONArray().also { a -> fields.forEach { a.put(it.toJson()) } })
    fun saveSessions() = writeList("sessions.json", JSONArray().also { a -> sessions.forEach { a.put(it.toJson()) } })
    fun saveNotes() = writeList("notes.json", JSONArray().also { a -> notes.forEach { a.put(it.toJson()) } })

    fun activeFields() = fields.filter { !it.deleted }
    fun activeSessions() = sessions.filter { !it.deleted }
    fun activeNotes() = notes.filter { !it.deleted }

    fun fieldAt(p: Pt): Field? = activeFields().firstOrNull { Geo.inside(p, it.coords) }
    fun fieldById(id: String?): Field? = id?.let { i -> fields.firstOrNull { it.id == i } }

    fun newId(prefix: String) = prefix + System.currentTimeMillis() + "-" +
            (1000..9999).random()

    /** Export - gleiches Format wie die Web-Version */
    fun exportJson(): String = JSONObject().apply {
        put("app", "reihen-navigator")
        put("version", 2)
        put("exported", System.currentTimeMillis())
        put("settings", JSONObject().put("width", widthM).put("night", night))
        put("fields", JSONArray().also { a -> fields.forEach { a.put(it.toJson()) } })
        put("sessions", JSONArray().also { a -> sessions.forEach { a.put(it.toJson()) } })
        put("notes", JSONArray().also { a -> notes.forEach { a.put(it.toJson()) } })
    }.toString(1)

    /**
     * Import - ergaenzt nur, ueberschreibt nie. Versteht sowohl das neue
     * Format als auch den Export der Web-Version (dort heissen die Felder
     * "coords" als [lat,lng] und Sessions haben "date"/"width").
     */
    fun importJson(text: String): Triple<Int, Int, Int> {
        val o = JSONObject(text)
        if (o.optString("app") != "reihen-navigator") error("Keine gueltige Sicherungsdatei")
        var nf = 0; var ns = 0; var nn = 0

        val fa = o.optJSONArray("fields") ?: JSONArray()
        for (i in 0 until fa.length()) {
            runCatching {
                val fo = fa.getJSONObject(i)
                val id = fo.getString("id")
                if (fields.any { it.id == id }) return@runCatching
                val fld = Field.fromJson(fo)
                if (fld.coords.size < 3) return@runCatching
                if (fld.ha <= 0.0) fld.ha = Geo.areaHa(fld.coords)
                fields.add(fld); nf++
            }
        }

        val sa = o.optJSONArray("sessions") ?: JSONArray()
        for (i in 0 until sa.length()) {
            runCatching {
                val so = sa.getJSONObject(i)
                val id = so.getString("id")
                if (sessions.any { it.id == id }) return@runCatching
                // Web-Format umsetzen
                if (!so.has("startedAt") && so.has("date")) {
                    val t = runCatching {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                            .parse(so.getString("date").substring(0, 19))!!.time
                    }.getOrDefault(0L)
                    so.put("startedAt", t); so.put("endedAt", t)
                }
                if (!so.has("widthM") && so.has("width")) so.put("widthM", so.getDouble("width"))
                val s = Session.fromJson(so)
                sessions.add(s); ns++
            }
        }

        // Notizen (neu) und Hindernisse (Web-Version)
        val na = o.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until na.length()) {
            runCatching {
                val no = na.getJSONObject(i)
                if (notes.any { it.id == no.getString("id") }) return@runCatching
                notes.add(Note.fromJson(no)); nn++
            }
        }
        val oa = o.optJSONArray("obstacles") ?: JSONArray()
        for (i in 0 until oa.length()) {
            runCatching {
                val oo = oa.getJSONObject(i)
                val id = oo.getString("id")
                if (notes.any { it.id == id }) return@runCatching
                notes.add(
                    Note(
                        id = id, lat = oo.getDouble("lat"), lng = oo.getDouble("lng"),
                        kind = "Hindernis", text = oo.optString("type", "")
                    )
                ); nn++
            }
        }

        saveFields(); saveSessions(); saveNotes()
        return Triple(nf, ns, nn)
    }
}
