package at.weinbau.reihennav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.Locale

/**
 * Zeichnet die Fahrt auf - auch bei ausgeschaltetem Display und wenn die App
 * nicht im Vordergrund ist. Als Foreground-Service mit dauerhafter
 * Benachrichtigung, das ist der einzige Weg, den Android zuverlaessig
 * am Leben laesst.
 */
class TrackingService : Service(), LocationListener {

    companion object {
        const val CH = "tracking"
        const val NOTIF_ID = 42
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_TASK = "task"

        /** Zustand, den die Activity beobachtet */
        @Volatile var running = false
        @Volatile var session: Session? = null
        @Volatile var lastFix: Pt? = null
        @Volatile var lastQuality: String = "warte auf Signal"
        @Volatile var lastAccM: Double = 0.0
        @Volatile var speedKmh: Double = 0.0
        @Volatile var sourceText: String = "Handy-GPS"
        @Volatile var currentField: Field? = null
        var listener: (() -> Unit)? = null
        fun notifyUi() = listener?.invoke()
    }

    private var lm: LocationManager? = null
    private var wake: PowerManager.WakeLock? = null
    private var bt: BtNmea? = null
    private var ntrip: Ntrip? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTracking(); return START_NOT_STICKY }
            else -> startTracking(intent?.getStringExtra(EXTRA_TASK) ?: "")
        }
        return START_STICKY
    }

    private fun startTracking(task: String) {
        if (running) return
        val s = Session(
            id = Store.newId("s"),
            startedAt = System.currentTimeMillis(),
            task = task,
            widthM = Store.widthM,
            user = Store.user
        )
        session = s
        running = true

        startForeground(NOTIF_ID, buildNotification("Aufzeichnung laeuft", task.ifBlank { "Suche Standort …" }))

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reihennav:track")
            .also { runCatching { it.acquire(12 * 60 * 60 * 1000L) } }

        // Externer Empfaenger, falls eingerichtet - sonst Handy-GPS
        if (Store.btMac.isNotBlank()) {
            sourceText = Store.btName.ifBlank { "Externer Empfaenger" }
            val b = BtNmea(Store.btMac, { fix -> onNmea(fix) }, { st ->
                lastQuality = st; notifyUi(); updateNotification()
            })
            bt = b
            b.start()
            if (Store.ntripHost.isNotBlank()) {
                ntrip = Ntrip(
                    Store.ntripHost, Store.ntripPort, Store.ntripMount,
                    Store.ntripUser, Store.ntripPass,
                    ggaProvider = { b.lastGga },
                    onRtcm = { data, n -> b.writeRtcm(data, n) },
                    onState = { st -> lastQuality = st; notifyUi() }
                ).also { it.start() }
            }
        } else {
            sourceText = "Handy-GPS"
        }

        // Handy-GPS laeuft immer mit (Rueckfall, falls Bluetooth abreisst)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        }
        notifyUi()
    }

    private fun stopTracking() {
        if (!running) { stopSelf(); return }
        running = false
        runCatching { lm?.removeUpdates(this) }
        bt?.stop(); bt = null
        ntrip?.stop(); ntrip = null
        runCatching { if (wake?.isHeld == true) wake?.release() }
        wake = null

        session?.let { s ->
            s.endedAt = System.currentTimeMillis()
            s.updatedAt = s.endedAt
            if (s.track.size > 1) {
                Store.sessions.add(s)
                Store.saveSessions()
            }
        }
        session = null
        currentField = null
        notifyUi()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Position vom externen Empfaenger */
    private fun onNmea(fix: NmeaFix) {
        lastQuality = fix.qualityText
        lastAccM = when (fix.quality) { 4 -> 0.02; 5 -> 0.3; 2 -> 1.0; else -> 3.0 }
        fix.speedKmh?.let { speedKmh = it }
        addPoint(Pt(fix.lat, fix.lng))
    }

    /** Position vom Handy-GPS - nur nutzen, wenn kein externer Empfaenger da ist */
    override fun onLocationChanged(loc: Location) {
        if (bt?.connected == true) return
        lastQuality = "Handy-GPS (±${loc.accuracy.toInt()} m)"
        lastAccM = loc.accuracy.toDouble()
        speedKmh = loc.speed * 3.6
        addPoint(Pt(loc.latitude, loc.longitude))
    }

    private fun addPoint(p: Pt) {
        lastFix = p
        val s = session ?: return
        val prev = s.track.lastOrNull()
        // Ausreisser und Stillstands-Rauschen herausfiltern
        if (prev != null) {
            val d = Geo.distM(prev, p)
            if (d < 0.4) return
            if (d > 200) return
            s.distM += d
        }
        s.track.add(p)

        val f = Store.fieldAt(p)
        if (f?.id != currentField?.id) {
            currentField = f
            if (f != null && s.fieldId == null) { s.fieldId = f.id; s.fieldName = f.name }
            updateNotification()
        }
        notifyUi()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH, "Aufzeichnung", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        val s = session
        val ha = s?.ha ?: 0.0
        val text = String.format(
            Locale.GERMAN, "%s · %.2f ha · %s",
            currentField?.name ?: "ausserhalb", ha, lastQuality
        )
        runCatching {
            (getSystemService(NotificationManager::class.java))
                .notify(NOTIF_ID, buildNotification("Aufzeichnung laeuft", text))
        }
    }

    override fun onDestroy() {
        runCatching { lm?.removeUpdates(this) }
        bt?.stop(); ntrip?.stop()
        runCatching { if (wake?.isHeld == true) wake?.release() }
        super.onDestroy()
    }

    override fun onProviderDisabled(provider: String) {}
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
}
