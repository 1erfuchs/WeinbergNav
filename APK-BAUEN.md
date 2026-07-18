# APK bauen – Schritt für Schritt

Du brauchst **kein Android Studio** und musst auf dem PC nichts installieren.
GitHub baut die APK kostenlos in der Cloud.

## 1. Dateien hochladen

Im GitHub-Repo (z. B. `weinberg`):

1. **Add file → Upload files**
2. Ziehe die Ordner `android` und `.github` hinein
3. Unten **Commit changes**

Wichtig: Die Ordnerstruktur muss erhalten bleiben:

```
dein-repo/
├─ index.html            (die Web-Version, bleibt wie sie ist)
├─ .github/
│  └─ workflows/
│     └─ android.yml
└─ android/
   ├─ settings.gradle.kts
   ├─ build.gradle.kts
   ├─ gradle.properties
   └─ app/
      ├─ build.gradle.kts
      ├─ proguard-rules.pro
      └─ src/main/
         ├─ AndroidManifest.xml
         ├─ java/at/weinbau/reihennav/*.kt
         └─ res/...
```

> Tipp: Der Browser-Upload kann komplette Ordner per Drag & Drop.
> Falls das zickt: Repo per **Code → Download ZIP** holen, Dateien im
> Explorer einsortieren, dann alles neu hochladen.

## 2. Bauen lassen

1. Oben im Repo auf **Actions**
2. Links **APK bauen** anklicken
3. Rechts **Run workflow → Run workflow**
4. Warten (beim ersten Mal ca. 5–10 Minuten)

- **Grüner Haken** = fertig.
- **Rotes Kreuz** = Fehler. Klick den Lauf an, öffne den Schritt *APK bauen*,
  kopiere die Fehlermeldung und schick sie mir. Beim ersten Bauversuch ist
  das normal.

## 3. APK herunterladen

1. Den grünen Lauf anklicken
2. Ganz unten unter **Artifacts** liegt `reihen-navigator-apk`
3. Herunterladen → ZIP entpacken → darin ist `app-debug.apk`
4. Die APK aufs Handy schicken (USB-Kabel, Mail an dich selbst, Cloud)

## 4. Auf dem Handy installieren

1. APK antippen
2. Android warnt: *„Aus dieser Quelle installieren nicht erlaubt“* →
   **Einstellungen** → Erlaubnis für den Datei-Manager/Browser erteilen
3. Installieren

Die Warnung ist normal: Die App ist nicht über den Play Store signiert.
Das ist genau das „einfach über die APK-Datei“, das du wolltest.

## 5. Erste Einrichtung

1. **Standort erlauben** → dann in den Android-App-Einstellungen auf
   **„Immer zulassen“** stellen. Nur damit läuft die Aufzeichnung bei
   ausgeschaltetem Display weiter.
2. **Akku-Optimierung ausschalten:** Android-Einstellungen → Apps →
   Reihen-Navigator → Akku → **Nicht optimiert / Uneingeschränkt**.
   Sonst killt Android die Aufzeichnung nach einiger Zeit.
   (Bei Samsung/Xiaomi/Huawei besonders wichtig.)
3. **Felder übernehmen:** In der Web-Version **⚙ → Exportieren**, Datei aufs
   Handy, dann in der App **Mehr → Daten importieren**. Alle Felder, Fahrten
   und Hindernisse kommen mit.

## 6. Externer GNSS-/RTK-Empfänger (optional)

1. Empfänger in den **Android-Bluetooth-Einstellungen koppeln**
   (nicht in der App – Android muss ihn zuerst kennen)
2. In der App: **Mehr → GPS-Empfänger** → Gerät auswählen
3. Für Zentimeter-Genauigkeit zusätzlich **Mehr → RTK-Korrekturdienst**
   ausfüllen (Zugangsdaten kommen vom Anbieter)

In der Statuszeile steht dann live, was wirklich anliegt:
`GPS (~3 m)` · `DGPS (~1 m)` · `RTK float (~30 cm)` · `RTK fix (1–2 cm)`

## Neue Version bauen

Jede Änderung im `android`-Ordner startet den Bau automatisch.
Neue APK aus Actions holen und drüber installieren – die Daten bleiben erhalten.
