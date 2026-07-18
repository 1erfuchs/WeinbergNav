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
    var updatedAt: Long = System.currentTimeMillis(),
    var user: String = "",
    var deleted: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("ha", ha)
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
        put("task", task); put("widthM", widthM); put("distM", distM)
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
}
