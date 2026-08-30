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
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import org.json.JSONObject
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
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var follow = true
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    /** Automatischer Serverabgleich alle 5 Minuten, solange die App offen ist. */
    private val syncIntervalMs = 5 * 60 * 1000L
    private val syncTick = object : Runnable {
        override fun run() {
            if (Sync.configured) autoSync()
            ui.postDelayed(this, syncIntervalMs)
        }
    }

    /** Alle 5 Minuten automatisch synchronisieren, solange eine Fahrt/Arbeit läuft. */
    private val periodicSync = object : Runnable {
        override fun run() {
            if (Sync.configured && TrackingService.running) autoSync()
            ui.postDelayed(this, 5 * 60 * 1000L)
        }
    }

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

        ui.postDelayed(periodicSync, 5 * 60 * 1000L)   // 5-Minuten-Sync während der Arbeit

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
        val statusWort = when (m.status) { "gesehen" -> "gesehen"; "erledigt" -> "erledigt"; else -> "neu" }
        val titel = "${m.art} · $statusWort" + if (m.text.isNotBlank()) "\n${m.text.take(60)}" else ""
        // WICHTIG: AlertDialog zeigt entweder eine Nachricht ODER eine Liste – daher
        // die Infos in den Titel, damit die Aktionsliste (mit "Foto ansehen") erscheint.
        val actions = mutableListOf<String>()
        if (m.fotos.isNotEmpty()) actions.add(if (m.fotos.size > 1) "📷 Fotos ansehen (${m.fotos.size})" else "📷 Foto ansehen")
        else actions.add("(Foto wird noch hochgeladen)")
        actions.add("Auf Karte zeigen")
        actions.add("Status ändern")
        actions.add("Löschen")
        AlertDialog.Builder(this)
            .setTitle(titel)
            .setItems(actions.toTypedArray()) { _, i ->
                when (actions[i]) {
                    "📷 Foto ansehen", "📷 Fotos ansehen (${m.fotos.size})" -> fotoAnsehen(m, 0)
                    "(Foto wird noch hochgeladen)" -> toast("Das Foto ist noch nicht am Server. Bitte später synchronisieren.")
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
            "\uD83C\uDF24 Spritzfenster (wann & was)",
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
                when (items[i]) {
                    "\uD83C\uDF24 Spritzfenster (wann & was)" -> spritzfensterDialog()
                    "Aufgaben verwalten" -> tasksMenu()
                    "Benutzer: ${Store.user}" -> textDialog("Benutzername", Store.user) { Store.user = it; Store.savePrefs() }
                    konto -> serverDialog()
                    "Jetzt synchronisieren" -> syncNow()
                    "RTK-Korrekturdienst (NTRIP)" -> ntripDialog()
                    "Daten exportieren" -> saveFile.launch("reihen-navigator-${SimpleDateFormat("yyyy-MM-dd", Locale.GERMAN).format(Date())}.json")
                    "Daten importieren" -> openFile.launch(arrayOf("application/json", "*/*"))
                    else -> when {
                        items[i].startsWith("Arbeitsbreite") -> widthDialog()
                        items[i].startsWith("GPS-Empfaenger") -> btDialog()
                    }
                }
            }.setPositiveButton("Schliessen", null).show()
    }

    // ---------- Spritzfenster (Entscheidungshilfe aus dem Server) ----------
    /** Zeigt die Entscheidungshilfe. Lädt im Hintergrund vom Server
     *  (api/spritzfenster_app.php) und zeigt bei fehlendem Netz den letzten Stand. */
    private fun spritzfensterDialog() {
        if (!Sync.configured) { toast("Kein Server verbunden – bitte zuerst anmelden (Mehr → Server)."); return }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(16), dpx(12), dpx(16), dpx(12))
        }
        val prefs = getSharedPreferences("spritzfenster", MODE_PRIVATE)
        val cached = prefs.getString("json", null)
        if (cached != null) buildSpritzfenster(container, cached, prefs.getLong("at", 0), true)
        else container.addView(sfText("Spritzfenster wird geladen …", 14f, 0xFF9AA0A6.toInt()))
        val dlg = AlertDialog.Builder(this)
            .setTitle("\uD83C\uDF24 Spritzfenster")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Schließen", null)
            .setNeutralButton("Aktualisieren", null)
            .show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { ladeSpritzfenster(container, dlg) }
        ladeSpritzfenster(container, dlg)
    }

    private fun ladeSpritzfenster(container: LinearLayout, dlg: AlertDialog) {
        Thread {
            val json = fetchSpritzfenster()
            runOnUiThread {
                if (!dlg.isShowing) return@runOnUiThread
                if (json != null) {
                    getSharedPreferences("spritzfenster", MODE_PRIVATE).edit()
                        .putString("json", json).putLong("at", System.currentTimeMillis()).apply()
                    buildSpritzfenster(container, json, System.currentTimeMillis(), false)
                } else {
                    val prefs = getSharedPreferences("spritzfenster", MODE_PRIVATE)
                    val c = prefs.getString("json", null)
                    if (c != null) buildSpritzfenster(container, c, prefs.getLong("at", 0), true)
                    else {
                        container.removeAllViews()
                        container.addView(sfText("Konnte nicht laden. Internet-/Serververbindung prüfen.", 14f, 0xFFE0625A.toInt()))
                    }
                }
            }
        }.start()
    }

    /** GET auf api/spritzfenster_app.php mit Bearer-Token. Null bei Fehler. */
    private fun fetchSpritzfenster(): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(Store.serverUrl.trimEnd('/') + "/spritzfenster_app.php").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("Authorization", "Bearer ${Store.serverToken}")
            }
            val code = c.responseCode
            if (code !in 200..299) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    // ----- Kachel-Bausteine -----
    private fun dpx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Ampelfarbe zu einer Stufe (Eignung oder Rhythmus). */
    private fun ampelColor(stufe: String): Int = when (stufe.trim().lowercase()) {
        "sehr gut", "gut", "ok" -> 0xFF5AC47A.toInt()
        "mäßig", "warn", "fällig" -> 0xFFE0B34A.toInt()
        "ungeeignet", "bad" -> 0xFFE0625A.toInt()
        else -> 0xFF7C8188.toInt()   // neutral / start / unbekannt
    }

    private fun sfText(s: String, size: Float = 14f, color: Int = 0xFFE6E6E6.toInt(), bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun sfHeader(s: String): TextView =
        TextView(this).apply {
            text = s; textSize = 12f; setTextColor(0xFF9AA0A6.toInt())
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.08f
            setPadding(dpx(2), dpx(12), 0, dpx(4))
        }

    /** Eine getönte Kachel mit farbigem Rand/Akzent. */
    private fun sfCard(accent: Int): LinearLayout {
        val a = accent and 0x00FFFFFF
        val bg = GradientDrawable().apply {
            cornerRadius = dpx(10).toFloat()
            setColor(a or 0x22000000)          // ~13 % getönt
            setStroke(dpx(1), a or 0x66000000)  // kräftigerer Rand
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dpx(12), dpx(10), dpx(12), dpx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dpx(4), 0, dpx(4)) }
        }
    }

    /** Baut die Kachel-Ansicht in den Container (ersetzt vorhandenen Inhalt). */
    private fun buildSpritzfenster(container: LinearLayout, json: String, atMillis: Long, stale: Boolean) {
        container.removeAllViews()
        try {
            val o = JSONObject(json)
            val hm = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN)
            val hhmm = SimpleDateFormat("HH:mm", Locale.GERMAN)
            val wd = SimpleDateFormat("EEE dd.MM.", Locale.GERMAN)
            fun tag(iso: String): String = try {
                wd.format(SimpleDateFormat("yyyy-MM-dd", Locale.GERMAN).parse(iso)!!)
            } catch (e: Exception) { iso }

            container.addView(sfText(
                (if (stale) "Stand ${hm.format(Date(atMillis))} · offline" else "Stand ${hm.format(Date(atMillis))}"),
                12f, 0xFF9AA0A6.toInt()))

            // --- WAS SPRITZEN ---
            container.addView(sfHeader("WAS SPRITZEN"))
            val e = o.optJSONObject("empfehlung")
            val wasCard = sfCard(0xFFE0B34A.toInt())
            if (e != null) {
                wasCard.addView(sfText(e.optString("kurz"), 15f, 0xFFF0F0F0.toInt(), true))
                val hw = e.optString("hinweis"); if (hw.isNotBlank() && hw != "null")
                    wasCard.addView(sfText("↳ $hw", 13f, 0xFFCDD2D6.toInt()).also { it.setPadding(0, dpx(4), 0, 0) })
                val q = e.optString("quelle")
                wasCard.addView(sfText("Stand ${tag(e.optString("datum"))}" + (if (q.isNotBlank() && q != "null") " · $q" else ""),
                    12f, 0xFF9AA0A6.toInt()).also { it.setPadding(0, dpx(4), 0, 0) })
            } else {
                wasCard.addView(sfText("Noch keine Empfehlung hinterlegt.", 14f, 0xFFCDD2D6.toInt()))
            }
            container.addView(wasCard)

            // --- HINWEISE ---
            val hin = o.optJSONArray("hinweise")
            if (hin != null && hin.length() > 0) {
                for (i in 0 until hin.length()) {
                    val h = hin.getJSONObject(i)
                    val col = ampelColor(h.optString("typ"))
                    val card = sfCard(col)
                    card.addView(sfText("⚠ ${h.optString("text")}", 13f, 0xFFF0F0F0.toInt()))
                    container.addView(card)
                }
            }

            // --- WER / WANN ---
            container.addView(sfHeader("WER / WANN"))
            val pers = o.optJSONArray("personen")
            if (pers != null) for (i in 0 until pers.length()) {
                val p = pers.getJSONObject(i)
                val rh = p.optJSONObject("rhythmus")
                val rStufe = rh?.optString("stufe") ?: "start"
                val card = sfCard(ampelColor(rStufe))
                card.addView(sfText("${p.optString("name")} — Bez. ${p.optString("bezirk")} · ${p.optInt("stunden")} h am Stück",
                    15f, 0xFFF0F0F0.toInt(), true))
                if (rh != null) card.addView(sfText("Rhythmus: ${rh.optString("text")}", 13f, ampelColor(rStufe))
                    .also { it.setPadding(0, dpx(3), 0, 0) })
                val faellig = p.optBoolean("faellig", true)
                val schwelle = o.optInt("schwelle", 8)
                val f = p.optJSONObject("fenster")
                val win: TextView = when {
                    !faellig -> {
                        val tage = rh?.optInt("tage", 0) ?: 0
                        val rest = (schwelle - tage).coerceAtLeast(0)
                        sfText("Wetterfenster ab Tag $schwelle – noch $rest Tag${if (rest == 1) "" else "e"}",
                            13f, 0xFF9AA0A6.toInt())
                    }
                    f == null -> sfText("Fenster: keine Prognose", 13f, 0xFF9AA0A6.toInt())
                    f.optBoolean("gefunden") -> {
                        val von = f.optLong("von") * 1000; val bis = f.optLong("bis") * 1000
                        val frueh = if (f.optBoolean("frueh")) "  · früher Start nötig" else ""
                        sfText("Fenster: ${wd.format(Date(von))} ${hhmm.format(Date(von))}–${hhmm.format(Date(bis))}" +
                               "  (${f.optString("stufe")})$frueh", 14f, ampelColor(f.optString("stufe")), true)
                    }
                    else -> sfText("Kein ${p.optInt("stunden")}-h-Fenster – längster Block ${f.optInt("laengste")} h",
                        13f, 0xFFE0B34A.toInt())
                }
                win.setPadding(0, dpx(4), 0, 0)
                card.addView(win)
                container.addView(card)
            }

            // --- WETTER NÄCHSTE TAGE (Ampelkacheln, tippbar) ---
            container.addView(sfHeader("WETTER NÄCHSTE TAGE"))
            val anyDue = o.optBoolean("anyDue", true)
            if (!anyDue) {
                val rest = if (o.isNull("minRest")) 0 else o.optInt("minRest", 0)
                val card = sfCard(0xFF7C8188.toInt())
                card.addView(sfText("Nächste Spritzung erst in ca. $rest Tag${if (rest == 1) "" else "en"} " +
                    "(Rhythmus 10–14 Tage).", 14f, 0xFFF0F0F0.toInt()))
                card.addView(sfText("Das Wetterfenster wird ab Tag ${o.optInt("schwelle", 8)} angezeigt.",
                    13f, 0xFF9AA0A6.toInt()).also { it.setPadding(0, dpx(3), 0, 0) })
                container.addView(card)
            } else {
                // Stundendaten nach Tag gruppieren (für die Kurven-Detailansicht)
                val stundenProTag = HashMap<String, MutableList<JSONObject>>()
                val sArr = o.optJSONArray("stunden")
                if (sArr != null) for (i in 0 until sArr.length()) {
                    val s = sArr.getJSONObject(i)
                    stundenProTag.getOrPut(s.optString("day")) { ArrayList() }.add(s)
                }
                val tage = o.optJSONArray("tage")
                if (tage != null) for (i in 0 until tage.length()) {
                    val t = tage.getJSONObject(i)
                    val stufe = t.optString("stufe")
                    val col = ampelColor(stufe)
                    val card = sfCard(col)
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    val dot = View(this).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) }
                        layoutParams = LinearLayout.LayoutParams(dpx(12), dpx(12)).apply { setMargins(0, dpx(5), dpx(10), 0) }
                    }
                    row.addView(dot)
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    box.addView(sfText("${tag(t.optString("tag"))}  ·  $stufe", 14f, 0xFFF0F0F0.toInt(), true))
                    val slot = if (!t.isNull("slotVon"))
                        "bestes Fenster ${"%02d".format(t.optInt("slotVon"))}–${"%02d".format(t.optInt("slotBis"))} Uhr"
                    else {
                        val g = t.optString("grund")
                        "kein gutes Fenster tagsüber" + if (g.isNotBlank() && g != "null") " ($g)" else ""
                    }
                    box.addView(sfText(slot, 13f, 0xFFCDD2D6.toInt()))
                    row.addView(box)
                    row.addView(sfText("›", 20f, 0xFF9AA0A6.toInt()))
                    card.addView(row)
                    val dayIso = t.optString("tag")
                    val dayHours = stundenProTag[dayIso]
                    if (dayHours != null && dayHours.isNotEmpty())
                        card.setOnClickListener { tagKurvenDialog(tag(dayIso), dayHours) }
                    container.addView(card)
                }
            }
            if (!o.optBoolean("wetterOk", true))
                container.addView(sfText("Wetterdaten konnten nicht geladen werden.", 13f, 0xFFE0625A.toInt())
                    .also { it.setPadding(0, dpx(6), 0, 0) })

            container.addView(sfText("Planungshilfe – maßgeblich sind Zulassung, Etikett und Wartezeiten.",
                12f, 0xFF9AA0A6.toInt()).also { it.setPadding(0, dpx(12), 0, 0) })
        } catch (ex: Exception) {
            container.addView(sfText("Anzeige konnte nicht erstellt werden.", 14f, 0xFFE0625A.toInt()))
        }
    }

    /** Detaildialog eines Tages mit nativ gezeichneten Wetterkurven. */
    private fun tagKurvenDialog(titel: String, hours: List<JSONObject>) {
        val chart = WeatherChartView(this, hours).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpx(360))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(8), dpx(8), dpx(8), dpx(8))
            addView(chart)
            addView(sfText("Grün = gute Spritzbedingung, rot = ungeeignet. Wind gestrichelt = Böen.",
                12f, 0xFF9AA0A6.toInt()).also { it.setPadding(dpx(6), dpx(8), dpx(6), 0) })
        }
        AlertDialog.Builder(this)
            .setTitle("Wetter am $titel")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun fmtNum(v: Double): String =
        if (Math.abs(v - Math.round(v)) < 0.05) Math.round(v).toString()
        else String.format(Locale.GERMAN, "%.1f", v)

    /** Zeichnet Temperatur-, Wind- und Regenkurve eines Tages (0–24 Uhr). */
    private inner class WeatherChartView(ctx: Context, hoursIn: List<JSONObject>) : View(ctx) {
        private val hours = hoursIn   // vom Server bereits in Stundenreihenfolge (0–23)
        private val d = resources.displayMetrics.density
        private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A8F98.toInt(); textSize = 10f * d }
        private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCDD2D6.toInt(); textSize = 12f * d; isFakeBoldText = true }
        private val pGrid = Paint().apply { color = 0xFF2A2E33.toInt(); strokeWidth = 1f }
        private val pAxis = Paint().apply { color = 0xFF3A3F45.toInt(); strokeWidth = 1f }
        private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
        private val pDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * d; pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        }
        private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            if (hours.isEmpty() || width == 0 || height == 0) return
            val w = width.toFloat()
            val L = 34f * d; val R = w - 12f * d
            val top0 = 18f * d; val bottomAxis = 22f * d; val gap = 26f * d
            val panelH = (height - top0 - bottomAxis - 2 * gap) / 3f
            val fullBottom = top0 + 3 * panelH + 2 * gap
            fun xF(hh: Float) = L + (R - L) * hh / 24f

            // Hintergrund nach Spritzbarkeit
            for (s in hours) {
                val sc = s.optInt("score")
                val c = if (sc >= 58) 0x225AC47A else if (sc < 38) 0x22E0625A else 0
                if (c != 0) {
                    pFill.color = c
                    canvas.drawRect(xF(s.optInt("h").toFloat()), top0, xF(s.optInt("h") + 1f), fullBottom, pFill)
                }
            }

            val panels = listOf(
                arrayOf("temp", "Temperatur", "°C", "0xFFE0B34A"),
                arrayOf("wind", "Wind", "km/h", "0xFF39D3E0"),
                arrayOf("regen", "Regen", "mm", "0xFF5AA9E0")
            )
            panels.forEachIndexed { pi, panel ->
                val key = panel[0]; val color = panel[3].substring(2).toLong(16).toInt()
                val pt = top0 + pi * (panelH + gap); val pb = pt + panelH
                val vals = ArrayList<Double>(hours.size)
                for (s in hours) vals.add(s.optDouble(key, 0.0))
                var mn = vals.minOrNull() ?: 0.0; var mx = vals.maxOrNull() ?: 1.0
                if (key == "regen") { mn = 0.0; mx = maxOf(1.0, mx) }
                if (mx - mn < 0.5) mx = mn + 1.0
                fun yF(v: Double) = (pb - (pb - pt) * ((v - mn) / (mx - mn))).toFloat()

                for (vv in listOf(mn, (mn + mx) / 2, mx)) {
                    val y = yF(vv)
                    canvas.drawLine(L, y, R, y, pGrid)
                    pText.textAlign = Paint.Align.RIGHT
                    canvas.drawText(fmtNum(vv), L - 4f * d, y + 3f * d, pText)
                }
                canvas.drawLine(L, pt, L, pb, pAxis)
                canvas.drawLine(L, pb, R, pb, pAxis)
                pLabel.textAlign = Paint.Align.LEFT
                canvas.drawText("${panel[1]} (${panel[2]})", L, pt - 5f * d, pLabel)

                if (key == "regen") {
                    pFill.color = color
                    var anyRain = false
                    for (s in hours) {
                        val v = s.optDouble("regen", 0.0); if (v <= 0) continue; anyRain = true
                        canvas.drawRect(xF(s.optInt("h") + 0.1f), yF(v), xF(s.optInt("h") + 0.9f), pb, pFill)
                    }
                    if (!anyRain) {
                        pText.textAlign = Paint.Align.CENTER
                        canvas.drawText("kein Regen erwartet", (L + R) / 2, (pt + pb) / 2, pText)
                    }
                } else {
                    if (key == "wind") {
                        val path = Path()
                        hours.forEachIndexed { idx, s ->
                            val x = xF(s.optInt("h").toFloat()); val y = yF(s.optDouble("boe", 0.0))
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        pDash.color = color; canvas.drawPath(path, pDash)
                    }
                    val path = Path()
                    hours.forEachIndexed { idx, s ->
                        val x = xF(s.optInt("h").toFloat()); val y = yF(s.optDouble(key, 0.0))
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    pLine.color = color; canvas.drawPath(path, pLine)
                }
            }
            pText.textAlign = Paint.Align.CENTER
            var hh = 0
            while (hh <= 24) {
                val x = xF(hh.toFloat())
                canvas.drawLine(x, fullBottom, x, fullBottom + 4f * d, pAxis)
                canvas.drawText(hh.toString(), x, fullBottom + 15f * d, pText)
                hh += 3
            }
        }
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
            val poly = Polygon(b.map).apply {
                points = f.coords.map { GeoPoint(it.lat, it.lng) }
                // Alle Felder einheitlich: die Farbe soll KEINEN Aufgabenstatus
                // andeuten - es koennen mehrere Aufgaben gleichzeitig laufen,
                // eine einzelne Farbe waere dann irrefuehrend.
                fillPaint.color = 0x1AE0B34A
                outlinePaint.color = 0xFFE0B34A.toInt()
                outlinePaint.strokeWidth = 3f
                title = f.name
                setOnClickListener { _, _, _ -> fieldTasksDialog(f); true }
            }
            b.map.overlays.add(poly)

            val center = GeoPoint(
                f.coords.sumOf { it.lat } / f.coords.size,
                f.coords.sumOf { it.lng } / f.coords.size
            )
            val lbl = Marker(b.map).apply {
                position = center
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = labelIcon("${f.name} · ${fmt(f.ha)} ha", false)
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
                    val cov = coverageOf(t, f.id)
                    val pct = if (cov > 0) " · ${(cov * 100).roundToInt()}%" else ""
                    "◐  ${t.title}   (in Arbeit seit ${SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN).format(Date(seit))}$pct)"
                }
                else -> "☐  ${t.title}${if (t.dueAt > 0) "   bis ${SimpleDateFormat("dd.MM.", Locale.GERMAN).format(Date(t.dueAt))}" else ""}"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${f.name} – Aufgaben")
            .setItems(labels) { _, i ->
                val t = tasks[i]
                if (t.typ.contains("Lese", true) || t.typ.contains("Ernte", true)) ernteDialog(t, f)
                else cycleTaskState(t, f)
            }
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

    /** Lese-Zählung für ein Feld: Boxen (Bez. 23) / Lesewägen (Bez. 21), mit Aufgaben-Summe. */
    private fun ernteDialog(t: Task, f: Field) {
        val units = when (f.bezirk.trim()) {
            "23" -> listOf("box"); "21" -> listOf("wag"); else -> listOf("box", "wag")
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(20), dpx(10), dpx(20), dpx(6))
        }
        val totalView = TextView(this).apply {
            textSize = 14f; setTextColor(0xFFCDD2D6.toInt()); setPadding(0, dpx(14), 0, dpx(4))
        }
        fun taskTotals(): Pair<Int, Int> {
            var b = 0; var w = 0
            for (e in Store.activeErnte()) if (e.taskId == t.id) { b += e.boxen; w += e.waegen }
            return b to w
        }
        fun refreshTotals() {
            val (b, w) = taskTotals()
            totalView.text = "Aufgabe gesamt:  $b Boxen · $w Lesewägen"
        }
        for (u in units) {
            val istBox = u == "box"
            root.addView(TextView(this).apply {
                text = if (istBox) "Boxen" else "Lesewägen"
                textSize = 16f; setTextColor(0xFFF0F0F0.toInt()); setPadding(0, dpx(12), 0, dpx(4))
            })
            val num = TextView(this).apply {
                textSize = 32f; setTextColor(0xFF5AC47A.toInt())
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            fun readVal(): Int = Store.ernteFor(t.id, f.id)?.let { if (istBox) it.boxen else it.waegen } ?: 0
            num.text = readVal().toString()
            fun apply(newVal: Int) {
                val e = Store.ernteFor(t.id, f.id)
                val b = if (istBox) newVal else (e?.boxen ?: 0)
                val w = if (!istBox) newVal else (e?.waegen ?: 0)
                Store.setErnte(t.id, f.id, b, w)
                num.text = readVal().toString(); refreshTotals()
                if (Sync.configured) autoSync()
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val minus = Button(this).apply {
                text = "−"; textSize = 24f
                layoutParams = LinearLayout.LayoutParams(dpx(64), dpx(60))
                setOnClickListener { apply((readVal() - 1).coerceAtLeast(0)) }
            }
            val plus = Button(this).apply {
                text = "+"; textSize = 24f
                layoutParams = LinearLayout.LayoutParams(dpx(64), dpx(60))
                setOnClickListener { apply(readVal() + 1) }
            }
            row.addView(minus); row.addView(num); row.addView(plus)
            root.addView(row)
            val setRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dpx(4), 0, 0)
            }
            val edit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = "Zahl direkt setzen"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val ok = Button(this).apply {
                text = "setzen"
                setOnClickListener {
                    edit.text.toString().toIntOrNull()?.let { apply(it.coerceAtLeast(0)); edit.setText("") }
                }
            }
            setRow.addView(edit); setRow.addView(ok)
            root.addView(setRow)
        }
        root.addView(totalView)
        refreshTotals()
        AlertDialog.Builder(this)
            .setTitle("Lese – ${f.name}")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Fertig", null)
            .setNeutralButton("Status ▸") { _, _ -> cycleTaskState(t, f) }
            .show()
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
        val fertig = ids.filter { t.stateOf(it) == "fertig" }
        val arbeit = ids.filter { t.stateOf(it) == "arbeit" }
        val offen = ids.filter { t.stateOf(it) == "offen" }
        val tf = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN)
        val body = buildString {
            append("Fortschritt: $done/${ids.size} Felder erledigt")
            if (arbeit.isNotEmpty() || offen.isNotEmpty())
                append("  ·  ${arbeit.size} in Arbeit · ${offen.size} offen")
            append("\n")
            if (t.note.isNotBlank()) append("Notiz: ${t.note}\n")
            if (fertig.isNotEmpty()) {
                append("\nErledigt:\n")
                fertig.forEach { fid ->
                    val name = Store.fieldById(fid)?.name ?: "?"
                    val m = t.done[fid]!!
                    val who = if (m.by.isNotBlank()) " (${m.by})" else ""
                    if (m.since > 0 && m.since < m.at)
                        append("• $name – ${tf.format(Date(m.since))} → ${tf.format(Date(m.at))}$who\n")
                    else
                        append("• $name – ${tf.format(Date(m.at))}$who\n")
                }
            }
            if (arbeit.isNotEmpty()) {
                append("\nIn Arbeit:\n")
                // neueste zuerst, damit die aktuelle Fläche oben steht
                arbeit.sortedByDescending { fid ->
                    t.done[fid]?.let { if (it.since > 0) it.since else it.at } ?: 0L
                }.forEach { fid ->
                    val name = Store.fieldById(fid)?.name ?: "?"
                    val m = t.done[fid]!!
                    val seit = if (m.since > 0) m.since else m.at
                    val cov = coverageOf(t, fid)
                    val pct = if (cov > 0) "  ·  ${(cov * 100).roundToInt()}% abgefahren" else ""
                    append("• $name – seit ${tf.format(Date(seit))}$pct\n")
                }
            }
            // Offene Felder nur als Anzahl (sonst füllen z. B. 24 Felder den Schirm)
            if (offen.isNotEmpty()) append("\nOffen: ${offen.size} Feld${if (offen.size != 1) "er" else ""}\n")
        }

        // Höhenbegrenzter Scrollbereich, damit die Knöpfe immer sichtbar bleiben
        val tv = TextView(this).apply { text = body; setPadding(48, 24, 48, 16); textSize = 14f }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) {
                val cap = (resources.displayMetrics.heightPixels * 0.45).toInt()
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            }
        }.apply { addView(tv) }

        // Karte auf die zuletzt begonnene Fläche dieser Aufgabe schwenken.
        // Reihenfolge: zuletzt "in Arbeit" genommen, sonst zuletzt erledigt,
        // sonst das erste Feld der Aufgabe.
        zoomToTaskFocus(t, ids)

        AlertDialog.Builder(this)
            .setTitle(t.title)
            .setView(scroll)
            .setPositiveButton("▶ Fahrt starten") { _, _ -> startDriveForTask(t) }
            .setNeutralButton("Schließen", null)
            .setNegativeButton("Löschen") { _, _ ->
                AlertDialog.Builder(this).setTitle("Löschen")
                    .setMessage("Aufgabe \"${t.title}\" wirklich löschen?")
                    .setPositiveButton("Löschen") { _, _ ->
                        t.deleted = true; t.updatedAt = System.currentTimeMillis()
                        Store.saveTasks(); if (Sync.configured) autoSync(); drawStatic(); toast("Aufgabe gelöscht")
                    }.setNegativeButton("Abbrechen", null).show()
            }.show()
    }

    /**
     * Schwenkt die Karte auf die Fläche einer Aufgabe, die gerade am ehesten
     * interessiert: das zuletzt auf "in Arbeit" gesetzte Feld (neuestes Datum
     * zuerst), sonst das zuletzt erledigte, sonst das erste Feld der Aufgabe.
     */
    private fun zoomToTaskFocus(t: Task, ids: List<String>) {
        fun zeit(fid: String): Long =
            t.done[fid]?.let { if (it.since > 0) it.since else it.at } ?: 0L
        val ziel =
            ids.filter { t.stateOf(it) == "arbeit" }.maxByOrNull { zeit(it) }
                ?: ids.filter { t.stateOf(it) == "fertig" }.maxByOrNull { zeit(it) }
                ?: ids.firstOrNull()
        Store.fieldById(ziel)?.let { zoomTo(it) }
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

    /**
     * Abdeckung eines Feldes in einer Aufgabe (0.0..1.0).
     * Bevorzugt den waehrend der Fahrt gespeicherten Wert; fehlt er (z. B. weil
     * das Feld mit einer aelteren App-Version markiert wurde), wird er aus den
     * Spuren der an die Aufgabe gekoppelten Fahrten berechnet.
     */
    private fun coverageOf(t: Task, fieldId: String): Double {
        val mark = t.done[fieldId]
        if (mark != null && mark.cov > 0) return mark.cov
        val f = Store.fieldById(fieldId) ?: return 0.0
        if (f.coords.size < 3) return 0.0
        val tracks = ArrayList<List<Pt>>()
        var breite = 0.0
        for (s in Store.sessions) {
            if (s.deleted || s.taskId != t.id || s.track.size < 2) continue
            tracks.add(s.track)
            if (s.widthM > breite) breite = s.widthM
        }
        if (tracks.isEmpty()) return 0.0
        if (breite <= 0.0) breite = Store.widthM
        return runCatching { Geo.coverageMulti(f.coords, tracks, breite) }.getOrDefault(0.0)
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
        // ... und danach alle 5 Minuten wiederholen, solange die App offen ist
        ui.removeCallbacks(syncTick)
        if (Sync.configured) ui.postDelayed(syncTick, syncIntervalMs)
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
        ui.removeCallbacks(syncTick)
    }

    override fun onDestroy() {
        TrackingService.listener = null
        super.onDestroy()
    }
}
