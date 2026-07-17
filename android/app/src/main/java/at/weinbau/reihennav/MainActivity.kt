package at.weinbau.reihennav

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import at.weinbau.reihennav.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var follow = true
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private var boundaryMode = false
    private val boundaryPts = mutableListOf<Pt>()

    /** Luftbild von Esri - kein Schluessel noetig */
    private val esri = object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "© Esri, Maxar, Earthstar Geographics"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return "$baseUrl$z/$y/$x"
        }
    }

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { askBackground() }

    private val openFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { doImport(it) } }

    private val saveFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { doExport(it) } }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Store.init(this)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.map.setTileSource(esri)
        b.map.setMultiTouchControls(true)
        b.map.setUseDataConnection(true)
        b.map.controller.setZoom(16.0)
        b.map.controller.setCenter(GeoPoint(48.21, 16.37))

        b.btnDrive.setOnClickListener { if (TrackingService.running) stopDrive() else askTask() }
        b.btnNote.setOnClickListener { addNoteDialog() }
        b.btnFields.setOnClickListener { fieldsDialog() }
        b.btnHist.setOnClickListener { historyDialog() }
        b.btnSet.setOnClickListener { settingsDialog() }
        b.btnLoc.setOnClickListener {
            follow = true
            TrackingService.lastFix?.let { center(it) }
        }
        b.map.setOnTouchListener { _, _ -> follow = false; false }

        TrackingService.listener = { ui.post { refresh() } }
        requestPerms()
        drawStatic()
        Store.activeFields().firstOrNull()?.let { zoomTo(it) }
        refresh()
    }

    // ---------- Berechtigungen ----------
    private fun requestPerms() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) perms.launch(missing.toTypedArray()) else askBackground()
    }

    private fun askBackground() {
        if (Build.VERSION.SDK_INT < 29) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            == PackageManager.PERMISSION_GRANTED) return
        AlertDialog.Builder(this)
            .setTitle("Aufzeichnung im Hintergrund")
            .setMessage(
                "Damit die Fahrt auch bei ausgeschaltetem Display weiterlaeuft, " +
                "braucht die App die Standortfreigabe \"Immer zulassen\".\n\n" +
                "Android zeigt das nur in den Einstellungen an."
            )
            .setPositiveButton("Einstellungen") { _, _ ->
                startActivity(Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                ))
            }
            .setNegativeButton("Spaeter", null)
            .show()
    }

    // ---------- Fahrt ----------
    private fun askTask() {
        val tasks = arrayOf(
            "Spritzen", "Laubschneiden", "Mulchen", "Ausbrechen", "Heften",
            "Gipfeln", "Bodenbearbeitung", "Duengen", "Ernte", "Ohne Thema"
        )
        var pick = 0
        val extra = EditText(this).apply {
            hint = "Zusatz, z. B. 6. Durchgang"
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Welche Arbeit?")
            .setSingleChoiceItems(tasks, 0) { _, w -> pick = w }
            .setView(extra)
            .setPositiveButton("Fahrt starten") { _, _ ->
                val base = if (tasks[pick] == "Ohne Thema") "" else tasks[pick]
                val ex = extra.text.toString().trim()
                startDrive(listOf(base, ex).filter { it.isNotBlank() }.joinToString(" · "))
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun startDrive(task: String) {
        val i = Intent(this, TrackingService::class.java)
            .setAction(TrackingService.ACTION_START)
            .putExtra(TrackingService.EXTRA_TASK, task)
        ContextCompat.startForegroundService(this, i)
        follow = true
        ui.postDelayed({ refresh() }, 500)
    }

    private fun stopDrive() {
        startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        ui.postDelayed({ drawStatic(); refresh() }, 700)
    }

    // ---------- Notizen ----------
    private fun addNoteDialog() {
        val p = TrackingService.lastFix ?: Pt(
            b.map.mapCenter.latitude, b.map.mapCenter.longitude
        )
        var pick = 0
        val txt = EditText(this).apply {
            hint = "Anmerkung (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Notiz an dieser Position")
            .setSingleChoiceItems(Note.KINDS.toTypedArray(), 0) { _, w -> pick = w }
            .setView(txt)
            .setPositiveButton("Speichern") { _, _ ->
                val n = Note(
                    id = Store.newId("n"), lat = p.lat, lng = p.lng,
                    fieldId = Store.fieldAt(p)?.id,
                    kind = Note.KINDS[pick], text = txt.text.toString().trim(),
                    user = Store.user
                )
                Store.notes.add(n); Store.saveNotes()
                drawStatic()
                toast("Notiz gespeichert: ${n.kind}")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------- Felder ----------
    private fun fieldsDialog() {
        val fs = Store.activeFields()
        val names = fs.map { "${it.name}  ·  ${fmt(it.ha)} ha" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Felder (${fs.size})")
            .setItems(names) { _, i -> fieldActions(fs[i]) }
            .setPositiveButton("Grenze abfahren") { _, _ -> startBoundary() }
            .setNeutralButton("Schliessen", null)
            .show()
    }

    private fun fieldActions(f: Field) {
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(arrayOf("Auf Karte zeigen", "Umbenennen", "Loeschen")) { _, i ->
                when (i) {
                    0 -> zoomTo(f)
                    1 -> {
                        val e = EditText(this).apply { setText(f.name); setPadding(40, 20, 40, 20) }
                        AlertDialog.Builder(this).setTitle("Name").setView(e)
                            .setPositiveButton("Speichern") { _, _ ->
                                f.name = e.text.toString().trim().ifBlank { f.name }
                                f.updatedAt = System.currentTimeMillis()
                                Store.saveFields(); drawStatic()
                            }.setNegativeButton("Abbrechen", null).show()
                    }
                    2 -> AlertDialog.Builder(this)
                        .setMessage("Feld \"${f.name}\" wirklich loeschen?")
                        .setPositiveButton("Loeschen") { _, _ ->
                            f.deleted = true; f.updatedAt = System.currentTimeMillis()
                            Store.saveFields(); drawStatic()
                        }.setNegativeButton("Abbrechen", null).show()
                }
            }.show()
    }

    /** Feldgrenze durch Abfahren aufnehmen */
    private fun startBoundary() {
        boundaryPts.clear()
        boundaryMode = true
        toast("Fahre die Grenze ab. Am Ende auf \"Grenze fertig\" tippen.")
        b.btnFields.text = "Grenze\nfertig"
        b.btnFields.setOnClickListener { finishBoundary() }
        if (!TrackingService.running) startDrive("Grenze aufnehmen")
    }

    private fun finishBoundary() {
        boundaryMode = false
        b.btnFields.text = getString(R.string.fields)
        b.btnFields.setOnClickListener { fieldsDialog() }
        val pts = boundaryPts.toList()
        if (pts.size < 3) { toast("Zu wenige Punkte aufgenommen."); return }
        val e = EditText(this).apply { hint = "Name des Feldes"; setPadding(40, 20, 40, 20) }
        AlertDialog.Builder(this)
            .setTitle("Feld speichern · ${fmt(Geo.areaHa(pts))} ha")
            .setView(e)
            .setPositiveButton("Speichern") { _, _ ->
                val f = Field(
                    id = Store.newId("f"),
                    name = e.text.toString().trim().ifBlank { "Feld ${Store.activeFields().size + 1}" },
                    coords = pts.toMutableList(),
                    ha = Geo.areaHa(pts),
                    user = Store.user
                )
                Store.fields.add(f); Store.saveFields(); drawStatic(); zoomTo(f)
            }
            .setNegativeButton("Verwerfen", null)
            .show()
    }

    // ---------- Historie ----------
    private fun historyDialog() {
        val ss = Store.activeSessions().sortedByDescending { it.startedAt }
        if (ss.isEmpty()) { toast("Noch keine Fahrten aufgezeichnet."); return }
        val df = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMAN)
        val tf = SimpleDateFormat("HH:mm", Locale.GERMAN)
        val lines = mutableListOf<String>()
        val refs = mutableListOf<Session>()
        var lastDay = ""
        for (s in ss) {
            val day = df.format(Date(s.startedAt))
            if (day != lastDay) { lastDay = day }
            lines.add("$day  ${tf.format(Date(s.startedAt))}\n${s.fieldName} · ${s.task.ifBlank { "ohne Thema" }} · ${fmt(s.ha)} ha")
            refs.add(s)
        }
        AlertDialog.Builder(this)
            .setTitle("Historie")
            .setItems(lines.toTypedArray()) { _, i -> sessionActions(refs[i]) }
            .setPositiveButton("Schliessen", null)
            .show()
    }

    private fun sessionActions(s: Session) {
        AlertDialog.Builder(this)
            .setTitle(s.fieldName)
            .setItems(arrayOf("Auf Karte zeigen", "Loeschen")) { _, i ->
                when (i) {
                    0 -> { drawStatic(); drawTrack(s.track, s.widthM); s.track.firstOrNull()?.let { center(it) } }
                    1 -> {
                        s.deleted = true; s.updatedAt = System.currentTimeMillis()
                        Store.saveSessions(); drawStatic(); toast("Eintrag geloescht")
                    }
                }
            }.show()
    }

    // ---------- Einstellungen ----------
    private fun settingsDialog() {
        val items = arrayOf(
            "Arbeitsbreite: ${fmt(Store.widthM)} m",
            "Benutzer: ${Store.user}",
            "GPS-Empfaenger: ${if (Store.btMac.isBlank()) "Handy-GPS" else Store.btName}",
            "RTK-Korrekturdienst (NTRIP)",
            "Daten exportieren",
            "Daten importieren"
        )
        AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> widthDialog()
                    1 -> textDialog("Benutzername", Store.user) { Store.user = it; Store.savePrefs() }
                    2 -> btDialog()
                    3 -> ntripDialog()
                    4 -> saveFile.launch("reihen-navigator-${SimpleDateFormat("yyyy-MM-dd", Locale.GERMAN).format(Date())}.json")
                    5 -> openFile.launch(arrayOf("application/json", "*/*"))
                }
            }.setPositiveButton("Schliessen", null).show()
    }

    private fun widthDialog() {
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Store.widthM.toString()); setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this).setTitle("Arbeitsbreite in Metern").setView(e)
            .setPositiveButton("Speichern") { _, _ ->
                e.text.toString().replace(",", ".").toDoubleOrNull()?.let {
                    if (it in 0.3..20.0) { Store.widthM = it; Store.savePrefs(); drawStatic() }
                }
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun textDialog(title: String, cur: String, ok: (String) -> Unit) {
        val e = EditText(this).apply { setText(cur); setPadding(40, 20, 40, 20) }
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("Speichern") { _, _ -> ok(e.text.toString().trim()) }
            .setNegativeButton("Abbrechen", null).show()
    }

    @SuppressLint("MissingPermission")
    private fun btDialog() {
        val ad = BluetoothAdapter.getDefaultAdapter()
        if (ad == null) { toast("Dieses Geraet hat kein Bluetooth."); return }
        val bonded = try { ad.bondedDevices.toList() } catch (e: SecurityException) {
            toast("Bluetooth-Berechtigung fehlt."); return
        }
        if (bonded.isEmpty()) {
            toast("Koppel den Empfaenger zuerst in den Android-Bluetooth-Einstellungen.")
            return
        }
        val names = (listOf("Handy-GPS verwenden") + bonded.map { "${it.name}  (${it.address})" }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("GPS-Empfaenger")
            .setItems(names) { _, i ->
                if (i == 0) { Store.btMac = ""; Store.btName = "" }
                else { Store.btMac = bonded[i - 1].address; Store.btName = bonded[i - 1].name ?: "Empfaenger" }
                Store.savePrefs()
                toast(if (Store.btMac.isBlank()) "Handy-GPS aktiv" else "Empfaenger: ${Store.btName}")
            }.show()
    }

    private fun ntripDialog() {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        fun fld(hint: String, v: String, num: Boolean = false): EditText {
            val e = EditText(this).apply {
                this.hint = hint; setText(v)
                if (num) inputType = InputType.TYPE_CLASS_NUMBER
            }
            ll.addView(e); return e
        }
        val h = fld("Host, z. B. rtk.example.at", Store.ntripHost)
        val p = fld("Port (meist 2101)", Store.ntripPort.toString(), true)
        val m = fld("Mountpoint", Store.ntripMount)
        val u = fld("Benutzer", Store.ntripUser)
        val pw = fld("Passwort", Store.ntripPass)
        val info = TextView(this).apply {
            text = "Zugangsdaten bekommst du vom Korrekturdienst " +
                    "(in Oesterreich z. B. EPOSA oder APOS/BEV – kostenpflichtig). " +
                    "Ohne diese Daten laeuft der Empfaenger ohne RTK."
            textSize = 12f; setPadding(0, 20, 0, 0)
        }
        ll.addView(info)
        AlertDialog.Builder(this)
            .setTitle("RTK-Korrekturdienst")
            .setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("Speichern") { _, _ ->
                Store.ntripHost = h.text.toString().trim()
                Store.ntripPort = p.text.toString().toIntOrNull() ?: 2101
                Store.ntripMount = m.text.toString().trim()
                Store.ntripUser = u.text.toString().trim()
                Store.ntripPass = pw.text.toString()
                Store.savePrefs()
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun doExport(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(Store.exportJson().toByteArray()) }
            toast("Gesichert: ${Store.activeFields().size} Felder, ${Store.activeSessions().size} Fahrten")
        }.onFailure { toast("Export fehlgeschlagen: ${it.message}") }
    }

    private fun doImport(uri: Uri) {
        runCatching {
            val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val (nf, ns, nn) = Store.importJson(txt)
            drawStatic()
            Store.activeFields().lastOrNull()?.let { zoomTo(it) }
            toast("Importiert: $nf Felder, $ns Fahrten, $nn Notizen")
        }.onFailure { toast("Import fehlgeschlagen: ${it.message}") }
    }

    // ---------- Karte zeichnen ----------
    private fun drawStatic() {
        b.map.overlays.clear()
        for (f in Store.activeFields()) {
            val poly = Polygon(b.map).apply {
                points = f.coords.map { GeoPoint(it.lat, it.lng) }
                fillPaint.color = 0x1AE0B34A
                outlinePaint.color = 0xFFE0B34A.toInt()
                outlinePaint.strokeWidth = 3f
                title = "${f.name} · ${fmt(f.ha)} ha"
            }
            b.map.overlays.add(poly)
        }
        for (n in Store.activeNotes()) {
            val m = Marker(b.map).apply {
                position = GeoPoint(n.lat, n.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = n.kind
                snippet = listOf(n.text, n.user).filter { it.isNotBlank() }.joinToString(" · ")
            }
            b.map.overlays.add(m)
        }
        b.map.invalidate()
    }

    /** Spurbreite massstabsgetreu: Meter -> Pixel beim aktuellen Zoom */
    private fun pxForMeters(m: Double): Float {
        val lat = b.map.mapCenter.latitude
        val mpp = 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(b.map.zoomLevelDouble)
        return (m / mpp).toFloat().coerceIn(2f, 400f)
    }

    private fun drawTrack(track: List<Pt>, widthM: Double) {
        if (track.size < 2) return
        val pl = Polyline(b.map).apply {
            setPoints(track.map { GeoPoint(it.lat, it.lng) })
            outlinePaint.color = 0xAA39D3E0.toInt()
            outlinePaint.strokeWidth = pxForMeters(widthM)
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        b.map.overlays.add(pl)
        b.map.invalidate()
    }

    private fun zoomTo(f: Field) {
        if (f.coords.isEmpty()) return
        b.map.controller.setZoom(17.5)
        b.map.controller.setCenter(GeoPoint(
            f.coords.sumOf { it.lat } / f.coords.size,
            f.coords.sumOf { it.lng } / f.coords.size
        ))
    }

    private fun center(p: Pt) = b.map.controller.animateTo(GeoPoint(p.lat, p.lng))

    // ---------- Anzeige aktualisieren ----------
    private fun refresh() {
        val run = TrackingService.running
        b.btnDrive.text = if (run) getString(R.string.stop) else getString(R.string.start)
        b.stats.visibility = if (run) View.VISIBLE else View.GONE

        val s = TrackingService.session
        if (run && s != null) {
            val sec = (System.currentTimeMillis() - s.startedAt) / 1000
            b.stTime.text = String.format(Locale.GERMAN, "%d:%02d", sec / 60, sec % 60)
            b.stDist.text = fmt(s.distM / 1000) + " km"
            b.stSpeed.text = String.format(Locale.GERMAN, "%.1f km/h", TrackingService.speedKmh)
            b.stArea.text = fmt(s.ha) + " ha"
            b.tvTitle.text = TrackingService.currentField?.let { "Im Feld: ${it.name}" }
                ?: "Aufzeichnung laeuft"
            b.tvSub.text = "${TrackingService.sourceText} · ${TrackingService.lastQuality}" +
                    (if (s.task.isNotBlank()) " · ${s.task}" else "")

            TrackingService.lastFix?.let { p ->
                if (boundaryMode) {
                    val last = boundaryPts.lastOrNull()
                    if (last == null || Geo.distM(last, p) > 2.0) boundaryPts.add(p)
                }
                if (follow) center(p)
            }
            drawStatic()
            drawTrack(s.track, s.widthM)
        } else {
            b.tvTitle.text = getString(R.string.ready)
            b.tvSub.text = getString(R.string.sub_ready)
        }
    }

    private fun fmt(d: Double) = String.format(Locale.GERMAN, "%.2f", d)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        b.map.onResume()
        TrackingService.listener = { ui.post { refresh() } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        b.map.onPause()
    }

    override fun onDestroy() {
        TrackingService.listener = null
        super.onDestroy()
    }
}
