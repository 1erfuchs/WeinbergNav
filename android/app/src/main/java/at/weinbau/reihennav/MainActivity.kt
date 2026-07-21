package at.weinbau.reihennav

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
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

    private var fused: FusedLocationProviderClient? = null
    private var myPos: Pt? = null

    /** Aktuelle Position: laufende Aufzeichnung hat Vorrang, sonst eigene Ortung */
    private fun here(): Pt? = TrackingService.lastFix ?: myPos

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

    // Kamera: Foto wird in pendingPhotoFile aufgenommen; danach Meldungs-Dialog
    private var pendingPhotoFile: File? = null
    private var pendingPhotoPos: Pt? = null
    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok: Boolean ->
        val file = pendingPhotoFile
        if (ok && file != null && file.exists() && file.length() > 0) {
            meldungDialog(pendingPhotoPos ?: here(), file)
        } else {
            pendingPhotoFile?.let { runCatching { it.delete() } }
            pendingPhotoFile = null
            toast("Kein Foto aufgenommen")
        }
    }

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
        b.btnNote.setOnClickListener { noteMenu() }
        b.btnFields.setOnClickListener { fieldsDialog() }
        b.btnHist.setOnClickListener { historyDialog() }
        b.btnSet.setOnClickListener { settingsDialog() }
        b.btnLoc.setOnClickListener {
            follow = true
            here()?.let { center(it) }
            locateOnce()
        }
        b.map.setOnTouchListener { _, _ -> follow = false; false }

        fused = LocationServices.getFusedLocationProviderClient(this)

        TrackingService.listener = { ui.post { refresh() } }
        requestPerms()
        drawStatic()
        Store.activeFields().firstOrNull()?.let { zoomTo(it) }
        refresh()
    }

    /** Holt einmalig eine frische Position (fuer den ◎-Knopf, ohne Aufzeichnung) */
    @SuppressLint("MissingPermission")
    private fun locateOnce() {
        val f = fused ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) { toast("Standort-Berechtigung fehlt"); return }
        toast("Suche Standort …")
        f.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    myPos = Pt(loc.latitude, loc.longitude)
                    drawStatic(); if (follow) here()?.let { center(it) }
                } else toast("Kein Signal – bist du unter freiem Himmel?")
            }
            .addOnFailureListener { toast("Ortung fehlgeschlagen: ${it.message}") }
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
            .setPositiveButton("Weiter") { _, _ ->
                val base = if (tasks[pick] == "Ohne Thema") "" else tasks[pick]
                val ex = extra.text.toString().trim()
                val label = listOf(base, ex).filter { it.isNotBlank() }.joinToString(" · ")
                chooseTaskThenStart(label)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Optional eine offene Aufgabe an die Fahrt koppeln (automatisches Abhaken) */
    private fun chooseTaskThenStart(label: String) {
        val allIds = Store.activeFields().map { it.id }
        val offene = Store.activeTasks().filter { t ->
            t.appliesTo(allIds).any { t.stateOf(it) != "fertig" }
        }
        if (offene.isEmpty()) { startDrive(label, null); return }
        val namen = arrayOf("Keine Aufgabe koppeln") +
            offene.map { it.title + (if (it.typ.isNotBlank()) " (${it.typ})" else "") }.toTypedArray()
        var pick = 0
        AlertDialog.Builder(this)
            .setTitle("Aufgabe koppeln?")
            .setSingleChoiceItems(namen, 0) { _, w -> pick = w }
            .setPositiveButton("Fahrt starten") { _, _ ->
                val taskId = if (pick == 0) null else offene[pick - 1].id
                startDrive(label, taskId)
            }
            .setNegativeButton("Zurück", null)
            .show()
    }

    private fun startDrive(task: String, taskId: String? = null) {
        val i = Intent(this, TrackingService::class.java)
            .setAction(TrackingService.ACTION_START)
            .putExtra(TrackingService.EXTRA_TASK, task)
            .putExtra(TrackingService.EXTRA_TASK_ID, taskId)
        ContextCompat.startForegroundService(this, i)
        follow = true
        if (taskId != null) toast("Aufgabe gekoppelt – befahrene Felder werden automatisch markiert")
        ui.postDelayed({ refresh() }, 500)
    }

    private fun stopDrive() {
        startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        ui.postDelayed({ drawStatic(); refresh(); if (Sync.configured) autoSync() }, 700)
    }

    // ---------- Notizen ----------
    private fun noteMenu() {
        val n = Store.activeNotes().size
        val md = Store.activeMeldungen().size
        AlertDialog.Builder(this)
            .setTitle("Notizen & Meldungen")
            .setItems(arrayOf(
                "Neue Notiz an meiner Position",
                "\uD83D\uDCF7 Schadensmeldung mit Foto",
                "Alle Notizen anzeigen ($n)",
                "Alle Schadensmeldungen ($md)"
            )) { _, i ->
                when (i) {
                    0 -> addNoteDialog()
                    1 -> addMeldungPhoto()
                    2 -> notesListDialog()
                    3 -> meldungenListDialog()
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun meldungenListDialog() {
        val list = Store.activeMeldungen().sortedByDescending { it.createdAt }
        if (list.isEmpty()) { toast("Noch keine Schadensmeldungen."); return }
        val tf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN)
        val labels = list.map { m ->
            val st = when (m.status) { "gesehen" -> "gesehen"; "erledigt" -> "erledigt"; else -> "neu" }
            val fotoInfo = if (m.fotos.isNotEmpty()) "\uD83D\uDCF7${m.fotos.size}" else "(Foto folgt)"
            "${m.art} · $st · $fotoInfo\n${tf.format(Date(m.createdAt))}${if (m.text.isNotBlank()) " · " + m.text.take(30) else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Schadensmeldungen")
            .setItems(labels) { _, i -> meldungActions(list[i]) }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun meldungActions(m: Meldung) {
        val info = listOfNotNull(
            m.art,
            m.text.ifBlank { null },
            "Status: ${m.status}",
            if (m.fotos.isNotEmpty()) "${m.fotos.size} Foto(s)" else "Foto wird noch hochgeladen"
        ).joinToString("\n")
        // Aktionen abhängig davon, ob schon ein Foto am Server liegt
        val actions = mutableListOf<String>()
        if (m.fotos.isNotEmpty()) actions.add(if (m.fotos.size > 1) "Fotos ansehen (${m.fotos.size})" else "Foto ansehen")
        actions.add("Auf Karte zeigen")
        actions.add("Status ändern")
        actions.add("Löschen")
        AlertDialog.Builder(this)
            .setTitle("Meldung")
            .setMessage(info)
            .setItems(actions.toTypedArray()) { _, i ->
                when (actions[i]) {
                    "Foto ansehen", "Fotos ansehen (${m.fotos.size})" -> fotoAnsehen(m, 0)
                    "Auf Karte zeigen" -> { b.map.controller.animateTo(org.osmdroid.util.GeoPoint(m.lat, m.lng)); b.map.controller.setZoom(18.0) }
                    "Status ändern" -> {
                        val opt = arrayOf("neu", "gesehen", "erledigt")
                        AlertDialog.Builder(this).setTitle("Status")
                            .setItems(opt) { _, w ->
                                m.status = opt[w]; m.updatedAt = System.currentTimeMillis()
                                Store.saveMeldungen(); if (Sync.configured) autoSync()
                                toast("Status: ${opt[w]}")
                            }.show()
                    }
                    "Löschen" -> AlertDialog.Builder(this)
                        .setMessage("Meldung \"${m.art}\" wirklich löschen?")
                        .setPositiveButton("Löschen") { _, _ ->
                            m.deleted = true; m.updatedAt = System.currentTimeMillis()
                            Store.saveMeldungen(); if (Sync.configured) autoSync(); drawStatic()
                        }.setNegativeButton("Abbrechen", null).show()
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    /**
     * Foto einer Schadensmeldung anzeigen. Die Bilder liegen am Server unter
     * .../api/fotos/<dateiname>. Sie werden im Hintergrund geladen, verkleinert
     * und in einem Dialog gezeigt. Bei mehreren Fotos schaltet "Weiter" durch.
     */
    private fun fotoAnsehen(m: Meldung, index: Int) {
        if (m.fotos.isEmpty()) { toast("Für diese Meldung ist noch kein Foto am Server."); return }
        if (!Sync.configured) { toast("Kein Server verbunden – Foto kann nicht geladen werden."); return }
        val i = ((index % m.fotos.size) + m.fotos.size) % m.fotos.size
        val name = m.fotos[i]
        val url = Store.serverUrl.trimEnd('/') + "/fotos/" + name

        val iv = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = 480
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        val status = TextView(this).apply { text = "Foto wird geladen …"; setPadding(24, 16, 24, 16); textSize = 13f }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(status); addView(iv) }

        val zaehler = if (m.fotos.size > 1) "  (${i + 1}/${m.fotos.size})" else ""
        val builder = AlertDialog.Builder(this)
            .setTitle("${m.art}$zaehler")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Schließen", null)
            .setNeutralButton("Im Browser öffnen") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { toast("Kein Browser gefunden") }
            }
        if (m.fotos.size > 1) builder.setNegativeButton("Weiter ▶", null) // Klick unten selbst behandeln
        val dlg = builder.show()
        // "Weiter" soll den Dialog NICHT schließen, sondern das nächste Foto zeigen
        if (m.fotos.size > 1) {
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                dlg.dismiss(); fotoAnsehen(m, i + 1)
            }
        }

        Thread {
            val bmp = loadBitmap(url, Store.serverToken)
            runOnUiThread {
                if (!dlg.isShowing) return@runOnUiThread
                if (bmp != null) { iv.setImageBitmap(bmp); status.visibility = View.GONE }
                else { status.text = "Foto konnte nicht geladen werden.\nBesteht eine Internetverbindung?"; iv.setImageResource(android.R.drawable.ic_menu_report_image) }
            }
        }.start()
    }

    /** Bild von einer URL laden und auf max. ~1600 px verkleinern. Im Hintergrund aufrufen! */
    private fun loadBitmap(url: String, token: String): android.graphics.Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000; readTimeout = 60000
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            // erst nur die Maße lesen, dann passend heruntergerechnet dekodieren (spart Speicher)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val maxDim = 1600
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun notesListDialog() {
        val notes = Store.activeNotes().sortedByDescending { it.createdAt }
        if (notes.isEmpty()) { toast("Noch keine Notizen."); return }
        val tf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN)
        val lines = notes.map { nt ->
            val feld = Store.fieldById(nt.fieldId)?.name ?: "kein Feld"
            val who = if (nt.user.isNotBlank()) " · ${nt.user}" else ""
            "${nt.kind}${if (nt.text.isNotBlank()) ": ${nt.text}" else ""}\n$feld · ${tf.format(Date(nt.createdAt))}$who"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Alle Notizen (${notes.size})")
            .setItems(lines) { _, i ->
                val nt = notes[i]
                b.map.controller.setZoom(18.0)
                center(Pt(nt.lat, nt.lng))
                noteActions(nt)
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    /** Notiz-Knopf: an aktueller GPS-Position, sonst Hinweis zum Antippen der Karte */
    private fun addNoteDialog() {
        val p = here()
        if (p == null) {
            AlertDialog.Builder(this)
                .setTitle("Keine Position")
                .setMessage(
                    "Es liegt noch keine GPS-Position vor. Tippe zuerst unten rechts auf ◎, " +
                    "oder tippe lange auf die gewuenschte Stelle in der Karte, um dort eine " +
                    "Notiz zu setzen."
                )
                .setPositiveButton("OK", null).show()
            return
        }
        noteDialog(p, "Notiz an deiner Position")
    }

    /** Notiz an einem beliebigen Punkt (langer Tipp auf die Karte) */
    private fun noteDialog(p: Pt, title: String) {
        var pick = 0
        val txt = EditText(this).apply {
            hint = "Anmerkung (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
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

    /** Schadensmeldung mit Foto: Kamera starten (Position wird jetzt festgehalten) */
    private fun addMeldungPhoto() {
        val p = here()
        if (p == null) {
            AlertDialog.Builder(this)
                .setTitle("Keine Position")
                .setMessage("Es liegt noch keine GPS-Position vor. Tippe zuerst unten rechts auf \u25CE.")
                .setPositiveButton("OK", null).show()
            return
        }
        pendingPhotoPos = p
        val dir = File(Store.filesDir(), "fotos").apply { mkdirs() }
        val file = File(dir, "m" + System.currentTimeMillis() + ".jpg")
        pendingPhotoFile = file
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            toast("Kamera nicht verfuegbar: ${e.message}"); return
        }
        try {
            takePhoto.launch(uri)
        } catch (e: Exception) {
            toast("Keine Kamera-App gefunden")
        }
    }

    /** Nach der Aufnahme: Art + Text erfassen, Meldung anlegen, Foto hochladen */
    private fun meldungDialog(p: Pt?, photo: File) {
        var pick = 0
        val txt = EditText(this).apply {
            hint = "Beschreibung (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Schadensmeldung")
            .setSingleChoiceItems(Meldung.ARTEN.toTypedArray(), 0) { _, w -> pick = w }
            .setView(txt)
            .setPositiveButton("Speichern") { _, _ ->
                val pos = p ?: here() ?: Pt(0.0, 0.0)
                val m = Meldung(
                    id = Store.newId("md"),
                    lat = pos.lat, lng = pos.lng,
                    fieldId = Store.fieldAt(pos)?.id,
                    art = Meldung.ARTEN[pick],
                    text = txt.text.toString().trim(),
                    status = "neu",
                    user = Store.user
                )
                Store.meldungen.add(m); Store.saveMeldungen()
                drawStatic()
                // Foto im Hintergrund hochladen (mit Offline-Warteschlange)
                Thread {
                    runCatching { PhotoUpload.addAndUpload(m.id, photo.absolutePath) }
                    runOnUiThread {
                        toast(if (PhotoUpload.hasPending()) "Meldung gespeichert – Foto wird bei Verbindung hochgeladen"
                              else "Meldung mit Foto gespeichert")
                        drawStatic()
                    }
                }.start()
                pendingPhotoFile = null
            }
            .setNegativeButton("Abbrechen") { _, _ ->
                runCatching { photo.delete() }
                pendingPhotoFile = null
            }
            .show()
    }
    private fun noteActions(n: Note) {
        val info = listOfNotNull(
            n.kind,
            n.text.ifBlank { null },
            if (n.user.isNotBlank()) "von ${n.user}" else null,
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN).format(Date(n.createdAt))
        ).joinToString(" · ")
        AlertDialog.Builder(this)
            .setTitle("Notiz\n$info")
            .setItems(arrayOf("Text ändern", "Hierher verschieben (Kartenmitte)", "Löschen")) { _, i ->
                when (i) {
                    0 -> {
                        val e = EditText(this).apply { setText(n.text); setPadding(40, 20, 40, 20) }
                        AlertDialog.Builder(this).setTitle("Text").setView(e)
                            .setPositiveButton("Speichern") { _, _ ->
                                n.text = e.text.toString().trim()
                                n.updatedAt = System.currentTimeMillis()
                                Store.saveNotes(); if (Sync.configured) autoSync(); drawStatic()
                            }.setNegativeButton("Abbrechen", null).show()
                    }
                    1 -> {
                        n.lat = b.map.mapCenter.latitude
                        n.lng = b.map.mapCenter.longitude
                        n.updatedAt = System.currentTimeMillis()
                        Store.saveNotes(); if (Sync.configured) autoSync(); drawStatic()
                        toast("Verschoben. Du kannst die Nadel auch direkt ziehen.")
                    }
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Notiz löschen")
                        .setMessage("Notiz \"${n.kind}\" wirklich löschen?")
                        .setPositiveButton("Löschen") { _, _ ->
                            n.deleted = true; n.updatedAt = System.currentTimeMillis()
                            Store.saveNotes(); if (Sync.configured) autoSync(); drawStatic(); toast("Notiz gelöscht")
                        }.setNegativeButton("Abbrechen", null).show()
                }
            }
            .setNegativeButton("Schließen", null)
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
        val titel = f.name + if (f.bezirk.isNotBlank()) " · Bezirk ${f.bezirk}" else ""
        AlertDialog.Builder(this)
            .setTitle(titel)
            .setItems(arrayOf("Auf Karte zeigen", "Umbenennen", "Bezirk festlegen", "Löschen")) { _, i ->
                when (i) {
                    0 -> zoomTo(f)
                    1 -> {
                        val e = EditText(this).apply { setText(f.name); setPadding(40, 20, 40, 20) }
                        AlertDialog.Builder(this).setTitle("Name").setView(e)
                            .setPositiveButton("Speichern") { _, _ ->
                                f.name = e.text.toString().trim().ifBlank { f.name }
                                f.updatedAt = System.currentTimeMillis()
                                Store.saveFields(); if (Sync.configured) autoSync(); drawStatic()
                            }.setNegativeButton("Abbrechen", null).show()
                    }
                    2 -> {
                        val e = EditText(this).apply {
                            setText(f.bezirk); hint = "z. B. 21 oder 23"; setPadding(40, 20, 40, 20)
                        }
                        AlertDialog.Builder(this).setTitle("Bezirk").setView(e)
                            .setPositiveButton("Speichern") { _, _ ->
                                f.bezirk = e.text.toString().trim()
                                f.updatedAt = System.currentTimeMillis()
                                Store.saveFields(); if (Sync.configured) autoSync(); drawStatic()
                            }.setNegativeButton("Abbrechen", null).show()
                    }
                    3 -> AlertDialog.Builder(this)
                        .setMessage("Feld \"${f.name}\" wirklich löschen?")
                        .setPositiveButton("Löschen") { _, _ ->
                            f.deleted = true; f.updatedAt = System.currentTimeMillis()
                            Store.saveFields(); if (Sync.configured) autoSync(); drawStatic()
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
            val who = if (s.user.isNotBlank()) "  ·  ${s.user}" else ""
            lines.add("$day  ${tf.format(Date(s.startedAt))}\n${s.fieldName} · ${s.task.ifBlank { "ohne Thema" }} · ${fmt(s.ha)} ha$who")
            refs.add(s)
        }
        AlertDialog.Builder(this)
            .setTitle("Historie")
            .setItems(lines.toTypedArray()) { _, i -> sessionActions(refs[i]) }
            .setPositiveButton("Schliessen", null)
            .show()
    }

    private fun sessionActions(s: Session) {
        val sub = listOfNotNull(
            s.task.ifBlank { null },
            "${fmt(s.ha)} ha",
            if (s.user.isNotBlank()) "von ${s.user}" else null
        ).joinToString(" · ")
        AlertDialog.Builder(this)
            .setTitle(s.fieldName + if (sub.isNotBlank()) "\n$sub" else "")
            .setItems(arrayOf("Auf Karte zeigen", "Löschen")) { _, i ->
                when (i) {
                    0 -> { drawStatic(); drawTrack(s.track, s.widthM); s.track.firstOrNull()?.let { center(it) }; toast(if (s.track.isEmpty()) "Keine Spur aufgezeichnet" else "Auf Karte angezeigt") }
                    1 -> {
                        s.deleted = true; s.updatedAt = System.currentTimeMillis()
                        Store.saveSessions(); if (Sync.configured) autoSync(); drawStatic(); toast("Eintrag gelöscht")
                    }
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    // ---------- Einstellungen ----------
    private fun settingsDialog() {
        val konto = if (Sync.configured) "Server: angemeldet als ${Store.user}" else "Server & Konto: nicht verbunden"
        val items = arrayOf(
            "Aufgaben verwalten",
            "Arbeitsbreite: ${fmt(Store.widthM)} m",
            "Benutzer: ${Store.user}",
            konto,
            "Jetzt synchronisieren",
            "GPS-Empfaenger: ${if (Store.btMac.isBlank()) "Handy-GPS" else Store.btName}",
            "RTK-Korrekturdienst (NTRIP)",
            "Daten exportieren",
            "Daten importieren"
        )
        AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> tasksMenu()
                    1 -> widthDialog()
                    2 -> textDialog("Benutzername", Store.user) { Store.user = it; Store.savePrefs() }
                    3 -> serverDialog()
                    4 -> syncNow()
                    5 -> btDialog()
                    6 -> ntripDialog()
                    7 -> saveFile.launch("reihen-navigator-${SimpleDateFormat("yyyy-MM-dd", Locale.GERMAN).format(Date())}.json")
                    8 -> openFile.launch(arrayOf("application/json", "*/*"))
                }
            }.setPositiveButton("Schliessen", null).show()
    }

    private fun serverDialog() {
        if (Sync.configured) {
            AlertDialog.Builder(this)
                .setTitle("Server")
                .setMessage("Angemeldet als ${Store.user}\nServer: ${Store.serverUrl}")
                .setPositiveButton("Jetzt synchronisieren") { _, _ -> syncNow() }
                .setNeutralButton("Abmelden") { _, _ -> Sync.logout(); toast("Abgemeldet") }
                .setNegativeButton("Schliessen", null).show()
            return
        }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 10) }
        fun fld(hint: String, v: String, pw: Boolean = false): EditText {
            val e = EditText(this).apply {
                this.hint = hint; setText(v)
                if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            ll.addView(e); return e
        }
        val srv = fld("Serveradresse, z. B. https://deinserver.at/rn/api", Store.serverUrl.ifBlank { "https://" })
        val usr = fld("Dein Name", Store.user.takeIf { it != "Ich" } ?: "")
        val pw = fld("Passwort", "", true)
        AlertDialog.Builder(this)
            .setTitle("Server & Konto")
            .setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("Anmelden") { _, _ ->
                val s = srv.text.toString().trim()
                val u = usr.text.toString().trim()
                val p = pw.text.toString()
                if (s.isBlank() || u.isBlank() || p.isBlank()) { toast("Bitte alles ausfüllen"); return@setPositiveButton }
                if (!s.startsWith("https://")) { toast("Adresse muss mit https:// beginnen"); return@setPositiveButton }
                toast("Melde an …")
                Sync.login(s, u, p) { okLogin, msg ->
                    ui.post {
                        toast(msg)
                        if (okLogin) syncNow()
                    }
                }
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun syncNow() {
        if (!Sync.configured) { toast("Erst unter Server & Konto anmelden"); return }
        Sync.sync { changed, msg ->
            ui.post {
                toast(msg)
                if (changed) { drawStatic(); refresh() }
            }
        }
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

        // Langer Tipp auf die Karte -> Notiz an dieser Stelle setzen
        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) noteDialog(Pt(p.latitude, p.longitude), "Notiz an dieser Stelle")
                return true
            }
        })
        b.map.overlays.add(events)

        for (f in Store.activeFields()) {
            val open = Store.openTasksFor(f.id).size
            val poly = Polygon(b.map).apply {
                points = f.coords.map { GeoPoint(it.lat, it.lng) }
                // Felder mit offenen Aufgaben werden hervorgehoben
                fillPaint.color = if (open > 0) 0x3339D3E0 else 0x1AE0B34A
                outlinePaint.color = if (open > 0) 0xFF39D3E0.toInt() else 0xFFE0B34A.toInt()
                outlinePaint.strokeWidth = 3f
                title = f.name
                setOnClickListener { _, _, _ -> fieldTasksDialog(f); true }
            }
            b.map.overlays.add(poly)

            val center = GeoPoint(
                f.coords.sumOf { it.lat } / f.coords.size,
                f.coords.sumOf { it.lng } / f.coords.size
            )
            val badge = if (open > 0) "  ⚑$open" else ""
            val lbl = Marker(b.map).apply {
                position = center
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = labelIcon("${f.name} · ${fmt(f.ha)} ha$badge", open > 0)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> fieldTasksDialog(f); true }
            }
            b.map.overlays.add(lbl)
        }

        for (n in Store.activeNotes()) {
            val m = Marker(b.map).apply {
                position = GeoPoint(n.lat, n.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = n.kind
                isDraggable = true
                setInfoWindow(null)   // keine Standard-Sprechblase -> Tipp oeffnet mein Menue
                // Kurz antippen -> Menue (bearbeiten / verschieben / loeschen)
                setOnMarkerClickListener { _, _ -> noteActions(n); true }
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker?) {}
                    override fun onMarkerDragStart(marker: Marker?) {}
                    override fun onMarkerDragEnd(marker: Marker?) {
                        marker?.position?.let {
                            n.lat = it.latitude; n.lng = it.longitude
                            n.updatedAt = System.currentTimeMillis()
                            Store.saveNotes()
                        }
                    }
                })
            }
            b.map.overlays.add(m)
        }

        // Schadensmeldungen (rot, mit Kamera-Hinweis)
        for (md in Store.activeMeldungen()) {
            val marker = Marker(b.map).apply {
                position = GeoPoint(md.lat, md.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = md.art
                setInfoWindow(null)
                icon = labelIcon("\u26A0 ${md.art}", false).let { it }
                setOnMarkerClickListener { _, _ -> meldungActions(md); true }
            }
            b.map.overlays.add(marker)
        }

        // Eigene aktuelle Position als kleiner Punkt
        here()?.let { p ->
            val me = Marker(b.map).apply {
                position = GeoPoint(p.lat, p.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dotIcon()
                setInfoWindow(null)
                title = "Meine Position"
            }
            b.map.overlays.add(me)
        }

        b.map.invalidate()
    }

    /** kleiner goldener Positionspunkt */
    private fun dotIcon(): android.graphics.drawable.Drawable {
        val s = (resources.displayMetrics.density * 16).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF12161C.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = 0xFFE0B34A.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f - s * 0.18f, p)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    /** Textmarke für ein Feld (Name + ha, optional hervorgehoben bei offenen Aufgaben) */
    private fun labelIcon(text: String, highlight: Boolean): android.graphics.drawable.Drawable {
        val d = resources.displayMetrics.density
        val tp = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = 12f * d
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val pad = (6 * d).toInt()
        val w = (tp.measureText(text) + pad * 2).toInt()
        val h = (tp.fontMetrics.let { it.descent - it.ascent } + pad * 2).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC12161C.toInt() }
        val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f * d
            color = if (highlight) 0xFF39D3E0.toInt() else 0xFFE0B34A.toInt()
        }
        val r = android.graphics.RectF(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        cv.drawRoundRect(r, 6f * d, 6f * d, bg)
        cv.drawRoundRect(r, 6f * d, 6f * d, border)
        cv.drawText(text, pad.toFloat(), pad - tp.fontMetrics.ascent, tp)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    // ---------- Aufgaben ----------
    /** Wird beim Antippen eines Feldes gezeigt: offene und erledigte Aufgaben */
    private fun fieldTasksDialog(f: Field) {
        val allIds = Store.activeFields().map { it.id }
        val tasks = Store.activeTasks().filter { f.id in it.appliesTo(allIds) }
            .sortedWith(compareBy({ it.isDone(f.id) }, { it.dueAt.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }))
        if (tasks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(f.name)
                .setMessage("Keine Aufgaben für dieses Feld.")
                .setPositiveButton("Neue Aufgabe") { _, _ -> newTaskDialog(preselect = f.id) }
                .setNegativeButton("Schließen", null).show()
            return
        }
        val labels = tasks.map { t ->
            when (t.stateOf(f.id)) {
                "fertig" -> {
                    val m = t.done[f.id]!!
                    val tf = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN)
                    val zeit = if (m.since > 0 && m.since < m.at) "${tf.format(Date(m.since))} → ${tf.format(Date(m.at))}"
                               else "erledigt ${tf.format(Date(m.at))}"
                    "☑  ${t.title}   ($zeit${if (m.by.isNotBlank()) " · ${m.by}" else ""})"
                }
                "arbeit" -> {
                    val m = t.done[f.id]!!
                    val seit = if (m.since > 0) m.since else m.at
                    "◐  ${t.title}   (in Arbeit seit ${SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN).format(Date(seit))})"
                }
                else -> "☐  ${t.title}${if (t.dueAt > 0) "   bis ${SimpleDateFormat("dd.MM.", Locale.GERMAN).format(Date(t.dueAt))}" else ""}"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${f.name} – Aufgaben")
            .setItems(labels) { _, i -> cycleTaskState(tasks[i], f) }
            .setPositiveButton("Neue Aufgabe") { _, _ -> newTaskDialog(preselect = f.id) }
            .setNegativeButton("Schließen", null)
            .show()
    }

    /** Status durchschalten: offen -> in Arbeit -> erledigt -> offen */
    private fun cycleTaskState(t: Task, f: Field) {
        val next = when (t.stateOf(f.id)) {
            "offen" -> "arbeit"
            "arbeit" -> "fertig"
            else -> "offen"
        }
        val now = System.currentTimeMillis()
        if (next == "offen") t.done.remove(f.id)
        else {
            // Arbeitsbeginn (since) erhalten: beim Start = jetzt, beim Erledigen der bisherige Wert
            val since = if (next == "arbeit") now else (t.done[f.id]?.since?.takeIf { it > 0 } ?: now)
            t.done[f.id] = FieldMark(next, now, Store.user, since)
        }
        t.updatedAt = now
        Store.saveTasks(); if (Sync.configured) autoSync(); drawStatic()
        val wort = when (next) { "arbeit" -> "in Arbeit"; "fertig" -> "erledigt"; else -> "offen" }
        toast("\"${t.title}\" für ${f.name}: $wort")
    }

    /** Aufgaben-Menue (im Mehr-Menue) */
    private fun tasksMenu() {
        val open = Store.activeTasks().count { t ->
            val ids = t.appliesTo(Store.activeFields().map { it.id })
            ids.any { !t.isDone(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("Aufgaben")
            .setItems(arrayOf("Neue Aufgabe anlegen", "Alle Aufgaben ($open offen)")) { _, i ->
                if (i == 0) newTaskDialog() else allTasksDialog()
            }
            .setNegativeButton("Schließen", null).show()
    }

    private fun newTaskDialog(preselect: String? = null) {
        val fields = Store.activeFields()
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 8) }
        val title = EditText(this).apply {
            hint = "Was ist zu tun? z. B. Laubschneiden"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        ll.addView(title)
        val hint = TextView(this).apply {
            text = "Für welche Felder? Nichts auswählen = alle Felder."
            textSize = 12f; setPadding(0, 16, 0, 6)
        }
        ll.addView(hint)
        val checks = fields.map { f ->
            CheckBox(this).apply {
                text = "${f.name} · ${fmt(f.ha)} ha"
                isChecked = (preselect != null && f.id == preselect)
            }.also { ll.addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("Neue Aufgabe")
            .setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("Anlegen") { _, _ ->
                val ti = title.text.toString().trim()
                if (ti.isBlank()) { toast("Bitte einen Titel eingeben"); return@setPositiveButton }
                val chosen = fields.filterIndexed { idx, _ -> checks[idx].isChecked }.map { it.id }.toMutableList()
                val task = Task(
                    id = Store.newId("t"), title = ti,
                    fieldIds = chosen,             // leer = alle
                    createdBy = Store.user, user = Store.user
                )
                Store.tasks.add(task); Store.saveTasks()
                if (Sync.configured) autoSync()
                drawStatic()
                val n = if (chosen.isEmpty()) fields.size else chosen.size
                toast("Aufgabe \"$ti\" für $n Feld${if (n != 1) "er" else ""} angelegt")
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun allTasksDialog() {
        val tasks = Store.activeTasks().sortedByDescending { it.createdAt }
        if (tasks.isEmpty()) { toast("Noch keine Aufgaben."); return }
        val allIds = Store.activeFields().map { it.id }
        val labels = tasks.map { t ->
            val ids = t.appliesTo(allIds)
            val done = ids.count { t.isDone(it) }
            val scope = if (t.fieldIds.isEmpty()) "alle Felder" else "${t.fieldIds.size} Feld${if (t.fieldIds.size != 1) "er" else ""}"
            "${t.title}\n$scope · erledigt $done/${ids.size}${if (t.createdBy.isNotBlank()) " · von ${t.createdBy}" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Alle Aufgaben (${tasks.size})")
            .setItems(labels) { _, i -> taskDetailDialog(tasks[i]) }
            .setNegativeButton("Schließen", null).show()
    }

    private fun taskDetailDialog(t: Task) {
        val allIds = Store.activeFields().map { it.id }
        val ids = t.appliesTo(allIds)
        val done = ids.count { t.isDone(it) }
        val body = buildString {
            append("Fortschritt: $done/${ids.size} Felder\n")
            if (t.note.isNotBlank()) append("Notiz: ${t.note}\n")
            val fertig = ids.filter { t.stateOf(it) == "fertig" }
            val arbeit = ids.filter { t.stateOf(it) == "arbeit" }
            val offen = ids.filter { t.stateOf(it) == "offen" }
            val tf = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN)
            if (fertig.isNotEmpty()) {
                append("\nErledigt:\n")
                fertig.forEach { fid ->
                    val name = Store.fieldById(fid)?.name ?: "?"
                    val m = t.done[fid]!!
                    val who = if (m.by.isNotBlank()) " (${m.by})" else ""
                    // Start- und Endzeit, wenn ein Arbeitsbeginn bekannt ist
                    if (m.since > 0 && m.since < m.at) {
                        append("• $name – ${tf.format(Date(m.since))} → ${tf.format(Date(m.at))}$who\n")
                    } else {
                        append("• $name – ${tf.format(Date(m.at))}$who\n")
                    }
                }
            }
            if (arbeit.isNotEmpty()) {
                append("\nIn Arbeit:\n")
                arbeit.forEach { fid ->
                    val name = Store.fieldById(fid)?.name ?: "?"
                    val m = t.done[fid]!!
                    val seit = if (m.since > 0) m.since else m.at
                    append("• $name – seit ${tf.format(Date(seit))}\n")
                }
            }
            if (offen.isNotEmpty()) {
                append("\nOffen:\n")
                offen.forEach { fid -> append("• ${Store.fieldById(fid)?.name ?: "?"}\n") }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(t.title)
            .setMessage(body)
            .setNeutralButton("▶ Fahrt starten") { _, _ -> startDriveForTask(t) }
            .setPositiveButton("Schließen", null)
            .setNegativeButton("Aufgabe löschen") { _, _ ->
                AlertDialog.Builder(this).setTitle("Löschen")
                    .setMessage("Aufgabe \"${t.title}\" wirklich löschen?")
                    .setPositiveButton("Löschen") { _, _ ->
                        t.deleted = true; t.updatedAt = System.currentTimeMillis()
                        Store.saveTasks(); if (Sync.configured) autoSync(); drawStatic(); toast("Aufgabe gelöscht")
                    }.setNegativeButton("Abbrechen", null).show()
            }.show()
    }

    /**
     * Fahrt direkt aus einer Aufgabe heraus starten. Die GPS-Fahrt wird an die
     * Aufgabe gekoppelt: befahrene Felder springen automatisch auf "in Arbeit"
     * bzw. ab 90 % Abdeckung auf "erledigt".
     */
    private fun startDriveForTask(t: Task) {
        if (TrackingService.running) {
            AlertDialog.Builder(this)
                .setTitle("Fahrt läuft bereits")
                .setMessage("Es läuft schon eine Fahrt. Bitte zuerst beenden, dann aus der Aufgabe neu starten.")
                .setPositiveButton("OK", null).show()
            return
        }
        val label = listOf(t.typ, t.title).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { t.title }
        startDrive(label, t.id)
        // Karte auf das erste Feld der Aufgabe schwenken
        val allIds = Store.activeFields().map { it.id }
        t.appliesTo(allIds).firstNotNullOfOrNull { Store.fieldById(it) }?.let { zoomTo(it) }
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
        // Beim Öffnen automatisch mit dem Server abgleichen (falls eingerichtet)
        if (Sync.configured && !TrackingService.running) autoSync()
    }

    private fun autoSync() {
        Sync.sync { changed, _ ->
            if (changed) ui.post { drawStatic(); refresh() }
            // offene Foto-Uploads nachreichen (Meldungen)
            if (PhotoUpload.hasPending()) {
                Thread { runCatching { PhotoUpload.processQueue() }
                    runOnUiThread { drawStatic() } }.start()
            }
        }
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
