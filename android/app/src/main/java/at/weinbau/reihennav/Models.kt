package at.weinbau.reihennav

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI

/**
 * Datenmodell.
 * Alle Objekte haben id / updatedAt / user / deleted -> damit spaeter die
 * Server-Synchronisierung zwischen mehreren Nutzern funktioniert, ohne dass
 * jemand die Aenderungen eines anderen ueberschreibt.
 */

data class Pt(val lat: Double, val lng: Double)

data class Field(
    var id: String,
    var name: String,
    var coords: MutableList<Pt>,
    var ha: Double = 0.0,
    var bezirk: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
    var user: String = "",
    var deleted: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("ha", ha); put("bezirk", bezirk)
        put("updatedAt", updatedAt); put("user", user); put("deleted", deleted)
        put("coords", JSONArray().also { arr ->
            coords.forEach { p -> arr.put(JSONArray().put(p.lat).put(p.lng)) }
        })
    }
    companion object {
        fun fromJson(o: JSONObject): Field {
            val cs = mutableListOf<Pt>()
            val a = o.optJSONArray("coords") ?: JSONArray()
            for (i in 0 until a.length()) {
                val p = a.getJSONArray(i)
                cs.add(Pt(p.getDouble(0), p.getDouble(1)))
            }
            return Field(
                id = o.getString("id"),
                name = o.optString("name", "Feld"),
                coords = cs,
                ha = o.optDouble("ha", 0.0),
                bezirk = o.optString("bezirk", ""),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                user = o.optString("user", ""),
                deleted = o.optBoolean("deleted", false)
            )
        }
    }
}

data class Session(
    var id: String,
    var startedAt: Long,
    var endedAt: Long = 0,
    var fieldId: String? = null,
    var fieldName: String = "Ohne Feld",
    var task: String = "",
    var taskId: String? = null,
    var widthM: Double = 2.0,
    var distM: Double = 0.0,
    var track: MutableList<Pt> = mutableListOf(),
    var user: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
    var deleted: Boolean = false
) {
    val ha: Double get() = distM * widthM / 10000.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("startedAt", startedAt); put("endedAt", endedAt)
        put("fieldId", fieldId ?: JSONObject.NULL); put("fieldName", fieldName)
        put("task", task); put("taskId", taskId ?: JSONObject.NULL)
        put("widthM", widthM); put("distM", distM)
        put("user", user); put("updatedAt", updatedAt); put("deleted", deleted)
        put("track", JSONArray().also { arr ->
            track.forEach { p -> arr.put(JSONArray().put(p.lat).put(p.lng)) }
        })
    }
    companion object {
        fun fromJson(o: JSONObject): Session {
            val ts = mutableListOf<Pt>()
            val a = o.optJSONArray("track") ?: JSONArray()
            for (i in 0 until a.length()) {
                val p = a.getJSONArray(i)
                ts.add(Pt(p.getDouble(0), p.getDouble(1)))
            }
            return Session(
                id = o.getString("id"),
                startedAt = o.optLong("startedAt", 0),
                endedAt = o.optLong("endedAt", 0),
                fieldId = if (o.isNull("fieldId") || !o.has("fieldId")) null else o.optString("fieldId").ifBlank { null },
                fieldName = o.optString("fieldName", "Ohne Feld"),
                task = o.optString("task", ""),
                taskId = if (o.isNull("taskId") || !o.has("taskId")) null else o.optString("taskId").ifBlank { null },
                widthM = o.optDouble("widthM", 2.0),
                distM = o.optDouble("distM", 0.0),
                track = ts,
                user = o.optString("user", ""),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                deleted = o.optBoolean("deleted", false)
            )
        }
    }
}

/**
 * Notiz an einer GPS-Position: Hindernis, Handarbeit (Stockputzen, Laub
 * einstricken) oder freie Anmerkung. Damit sehen alle Nutzer den Zustand
 * der Weingaerten.
 */
data class Note(
    var id: String,
    var lat: Double,
    var lng: Double,
    var fieldId: String? = null,
    var kind: String = "Anmerkung",
    var text: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var user: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
    var deleted: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("lat", lat); put("lng", lng)
        put("fieldId", fieldId ?: JSONObject.NULL)
        put("kind", kind); put("text", text); put("createdAt", createdAt)
        put("user", user); put("updatedAt", updatedAt); put("deleted", deleted)
    }
    companion object {
        val KINDS = listOf(
            "Stockputzen", "Laub einstricken", "Ausbrechen", "Heften",
            "Schaden", "Hindernis", "Anmerkung"
        )
        fun fromJson(o: JSONObject) = Note(
            id = o.getString("id"),
            lat = o.getDouble("lat"),
            lng = o.getDouble("lng"),
            fieldId = if (o.isNull("fieldId") || !o.has("fieldId")) null else o.optString("fieldId").ifBlank { null },
            kind = o.optString("kind", "Anmerkung"),
            text = o.optString("text", ""),
            createdAt = o.optLong("createdAt", 0),
            user = o.optString("user", ""),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            deleted = o.optBoolean("deleted", false)
        )
    }
}

/**
 * Schadensmeldung mit Foto(s). Wie eine Notiz, aber mit Bildern (als Dateinamen
 * auf dem Server) und einem Bearbeitungsstatus (neu/gesehen/erledigt).
 */
data class Meldung(
    var id: String,
    var lat: Double,
    var lng: Double,
    var fieldId: String? = null,
    var art: String = "Schaden",
    var text: String = "",
    var fotos: MutableList<String> = mutableListOf(),   // Dateinamen auf dem Server
    var status: String = "neu",                          // neu | gesehen | erledigt
    var createdAt: Long = System.currentTimeMillis(),
    var user: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
    var deleted: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("lat", lat); put("lng", lng)
        put("fieldId", fieldId ?: JSONObject.NULL)
        put("art", art); put("text", text); put("status", status)
        put("createdAt", createdAt); put("user", user)
        put("updatedAt", updatedAt); put("deleted", deleted)
        put("fotos", JSONArray().also { a -> fotos.forEach { a.put(it) } })
    }
    companion object {
        val ARTEN = listOf(
            "Wildschaden", "Hagelschaden", "Sturmschaden", "Pfosten-Schaden",
            "Zaun-Schaden", "Maschinen-Schaden", "Krankheit", "Sonstiger Schaden"
        )
        fun fromJson(o: JSONObject): Meldung {
            val fotos = mutableListOf<String>()
            val fa = o.optJSONArray("fotos") ?: JSONArray()
            for (i in 0 until fa.length()) fotos.add(fa.getString(i))
            return Meldung(
                id = o.getString("id"),
                lat = o.optDouble("lat", 0.0),
                lng = o.optDouble("lng", 0.0),
                fieldId = if (o.isNull("fieldId") || !o.has("fieldId")) null else o.optString("fieldId").ifBlank { null },
                art = o.optString("art", "Schaden"),
                text = o.optString("text", ""),
                fotos = fotos,
                status = o.optString("status", "neu"),
                createdAt = o.optLong("createdAt", 0),
                user = o.optString("user", ""),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                deleted = o.optBoolean("deleted", false)
            )
        }
    }
}

/**
 * Aufgabe (Arbeitsauftrag). Wird einem oder mehreren Feldern zugewiesen.
 * Pro Feld wird der Erledigt-Status samt Zeitstempel und Person festgehalten,
 * damit man auf der Karte sieht, wo eine Arbeit schon gemacht wurde.
 *
 * done:  fieldId -> {at: Zeitpunkt (ms), by: Name}  (nur erledigte Felder)
 */
data class Task(
    var id: String,
    var title: String,
    var typ: String = "",                                  // Aufgabentyp (z.B. Spritzen)
    var fieldIds: MutableList<String> = mutableListOf(),   // leer = alle Felder
    var note: String = "",
    var dueAt: Long = 0,                                    // 0 = keine Frist
    var done: MutableMap<String, FieldMark> = mutableMapOf(),
    var createdBy: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var user: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
    var deleted: Boolean = false
) {
    /** betroffene Felder: leere Liste bedeutet "alle" */
    fun appliesTo(allFieldIds: List<String>): List<String> =
        if (fieldIds.isEmpty()) allFieldIds else fieldIds

    fun isDone(fieldId: String) = done[fieldId]?.state == "fertig"
    fun inProgress(fieldId: String) = done[fieldId]?.state == "arbeit"
    /** Status eines Feldes: "offen" | "arbeit" | "fertig" */
    fun stateOf(fieldId: String) = done[fieldId]?.state ?: "offen"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("typ", typ); put("note", note)
        put("dueAt", dueAt); put("createdBy", createdBy); put("createdAt", createdAt)
        put("user", user); put("updatedAt", updatedAt); put("deleted", deleted)
        put("fieldIds", JSONArray().also { a -> fieldIds.forEach { a.put(it) } })
        put("done", JSONObject().also { d ->
            done.forEach { (fid, m) -> d.put(fid, JSONObject().put("state", m.state).put("at", m.at).put("by", m.by).put("since", m.since)) }
        })
    }
    companion object {
        fun fromJson(o: JSONObject): Task {
            val fids = mutableListOf<String>()
            val fa = o.optJSONArray("fieldIds") ?: JSONArray()
            for (i in 0 until fa.length()) fids.add(fa.getString(i))
            val dm = mutableMapOf<String, FieldMark>()
            val d = o.optJSONObject("done")
            if (d != null) {
                val keys = d.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val mo = d.getJSONObject(k)
                    // altes Format (nur at/by) -> state = fertig; since fällt auf at zurück
                    val at = mo.optLong("at", 0)
                    dm[k] = FieldMark(mo.optString("state", "fertig"), at, mo.optString("by", ""), mo.optLong("since", at))
                }
            }
            return Task(
                id = o.getString("id"),
                title = o.optString("title", "Aufgabe"),
                typ = o.optString("typ", ""),
                fieldIds = fids,
                note = o.optString("note", ""),
                dueAt = o.optLong("dueAt", 0),
                done = dm,
                createdBy = o.optString("createdBy", ""),
                createdAt = o.optLong("createdAt", 0),
                user = o.optString("user", ""),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                deleted = o.optBoolean("deleted", false)
            )
        }
    }
}

/**
 * Erledigungs-Vermerk eines Feldes in einer Aufgabe.
 *  state = "arbeit" | "fertig"
 *  at    = Zeitpunkt der letzten Statusänderung
 *          (bei "arbeit" = Beginn, bei "fertig" = Ende der Arbeit)
 *  since = Zeitpunkt, an dem das Feld erstmals auf "in Arbeit" ging.
 *          Bleibt beim Wechsel auf "fertig" erhalten, damit Start- UND
 *          Endzeit sichtbar sind. Fällt bei altem Datenbestand auf "at" zurück.
 */
data class FieldMark(val state: String, val at: Long, val by: String, val since: Long = at)

/** Geometrie-Hilfen (ohne externe Bibliothek) */
object Geo {
    const val R = 6371008.8

    fun distM(a: Pt, b: Pt): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val la = Math.toRadians(a.lat)
        val lb = Math.toRadians(b.lat)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(la) * Math.cos(lb) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }

    /** Flaeche in Hektar (aequidistante Projektion um den Schwerpunkt) */
    fun areaHa(coords: List<Pt>): Double {
        if (coords.size < 3) return 0.0
        val lat0 = coords.sumOf { it.lat } / coords.size
        val kx = cos(lat0 * PI / 180.0) * 111320.0
        val ky = 110540.0
        var sum = 0.0
        for (i in coords.indices) {
            val a = coords[i]
            val b = coords[(i + 1) % coords.size]
            val ax = a.lng * kx; val ay = a.lat * ky
            val bx = b.lng * kx; val by = b.lat * ky
            sum += ax * by - bx * ay
        }
        return abs(sum) / 2.0 / 10000.0
    }

    /** Punkt-in-Polygon (Ray Casting) -> automatische Felderkennung */
    fun inside(p: Pt, poly: List<Pt>): Boolean {
        if (poly.size < 3) return false
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]; val pj = poly[j]
            if ((pi.lat > p.lat) != (pj.lat > p.lat)) {
                val x = (pj.lng - pi.lng) * (p.lat - pi.lat) / (pj.lat - pi.lat) + pi.lng
                if (p.lng < x) c = !c
            }
            j = i
        }
        return c
    }

    /**
     * Rasterabdeckung eines Feldes durch eine Fahrspur.
     * Legt ein Raster (cellM × cellM) über das Feld-Polygon. Eine Zelle zählt als
     * "abgefahren", wenn ihr Mittelpunkt im Feld liegt UND höchstens halbe
     * Arbeitsbreite von der Spur entfernt ist.
     *
     * @return Anteil abgefahrener Feld-Zellen 0.0..1.0
     */
    fun coverage(poly: List<Pt>, track: List<Pt>, widthM: Double, cellM: Double = 0.0): Double {
        if (poly.size < 3 || track.isEmpty()) return 0.0
        // Rasterweite an die Arbeitsbreite anpassen (2..5 m), sonst verfehlen
        // schmale Bahnen die Rasterpunkte.
        val cell = if (cellM > 0) cellM else widthM.coerceIn(2.0, 5.0)
        // lokale meterbasierte Projektion um den Feldschwerpunkt
        val lat0 = poly.sumOf { it.lat } / poly.size
        val kx = cos(lat0 * PI / 180.0) * 111320.0
        val ky = 110540.0
        fun mx(p: Pt) = p.lng * kx
        fun my(p: Pt) = p.lat * ky

        // Bounding-Box des Feldes in Metern
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in poly) {
            val x = mx(p); val y = my(p)
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        // Spur in Meter-Koordinaten
        val tx = DoubleArray(track.size) { mx(track[it]) }
        val ty = DoubleArray(track.size) { my(track[it]) }
        val half = widthM / 2.0
        val half2 = half * half

        // quadrierter Abstand Punkt->Segment
        fun distSq(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) { val ex = px - ax; val ey = py - ay; return ex * ex + ey * ey }
            var t = ((px - ax) * dx + (py - ay) * dy) / len2
            if (t < 0) t = 0.0; if (t > 1) t = 1.0
            val cx = ax + t * dx; val cy = ay + t * dy
            val ex = px - cx; val ey = py - cy
            return ex * ex + ey * ey
        }

        // Feld-Polygon in Meter-Koordinaten für Punkt-in-Polygon
        val polyX = DoubleArray(poly.size) { mx(poly[it]) }
        val polyY = DoubleArray(poly.size) { my(poly[it]) }
        fun insideM(px: Double, py: Double): Boolean {
            var c = false; var j = poly.size - 1
            for (i in poly.indices) {
                if ((polyY[i] > py) != (polyY[j] > py)) {
                    val x = (polyX[j] - polyX[i]) * (py - polyY[i]) / (polyY[j] - polyY[i]) + polyX[i]
                    if (px < x) c = !c
                }
                j = i
            }
            return c
        }

        var total = 0; var covered = 0
        var cy = minY + cell / 2
        while (cy <= maxY) {
            var cx = minX + cell / 2
            while (cx <= maxX) {
                if (insideM(cx, cy)) {
                    total++
                    // liegt die Zelle nah genug an der Spur?
                    var near = false
                    var k = 0
                    while (k < track.size) {
                        val d = if (k + 1 < track.size)
                            distSq(cx, cy, tx[k], ty[k], tx[k + 1], ty[k + 1])
                        else { val ex = cx - tx[k]; val ey = cy - ty[k]; ex * ex + ey * ey }
                        if (d <= half2) { near = true; break }
                        k++
                    }
                    if (near) covered++
                }
                cx += cell
            }
            cy += cell
        }
        return if (total == 0) 0.0 else covered.toDouble() / total.toDouble()
    }
}
