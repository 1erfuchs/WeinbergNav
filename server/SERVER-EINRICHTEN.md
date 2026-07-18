# Server einrichten – Schritt für Schritt

Einmalige Einrichtung, ca. 15 Minuten. Danach synchronisieren alle Handys
automatisch.

## 1. Datenbank anlegen

Im Kundenmenü deines Webhostings (oft „Datenbanken“ / „MySQL“):

1. Neue Datenbank erstellen. Notiere dir **Datenbankname, Benutzer, Passwort, Host**.
2. **phpMyAdmin** öffnen (ist bei fast jedem Webhosting dabei).
3. Deine Datenbank links auswählen → Reiter **SQL** → Inhalt von
   `schema.sql` einfügen → **OK**. Damit entstehen die Tabellen.

## 2. Dateien anpassen

In `api/config.php` ganz oben die **vier DB-Zugangsdaten** eintragen
(aus Schritt 1). Sonst nichts ändern.

In `api/admin.php` den **ADMIN_KEY** auf ein eigenes langes Passwort setzen –
das brauchst du nur du, um Nutzer anzulegen.

## 3. Hochladen (hier nutzt du dein FTP)

Lade den Ordner `api` auf deinen Webspace, z. B. nach `rn/api`, sodass
erreichbar ist:

```
https://deinserver.at/rn/api/login.php
https://deinserver.at/rn/api/sync.php
https://deinserver.at/rn/api/admin.php
```

> Tipp: Am besten in einen eigenen Ordner (`rn`), nicht ins Hauptverzeichnis.

## 4. Nutzer anlegen

Im Browser öffnen:

```
https://deinserver.at/rn/api/admin.php
```

Admin-Schlüssel eingeben → für jede Person **Name + Passwort** anlegen.
Das sind die Zugänge, die deine Leute in der App eingeben.

## 5. In der App verbinden

Jede Person in der App:

1. **Mehr → Server & Konto**
2. Serveradresse eintragen: `https://deinserver.at/rn/api`
3. Eigenen Namen + Passwort eingeben → **Anmelden**

Fertig. Ab jetzt lädt jedes Handy beim Öffnen und nach jeder Fahrt
automatisch hoch und runter. Jeder sieht die Felder, Fahrten und Notizen
der anderen – mit Namen dabei, wer was gemacht hat.

## Sicherheit – kurz und ehrlich

- Passwörter werden **nie im Klartext** gespeichert (nur ein Hash).
- Alles läuft über **HTTPS**, also verschlüsselt.
- Der `admin.php`-Schlüssel ist dein Generalschlüssel – gib ihn niemandem
  und wähle ihn lang.
- Wenn du einen Nutzer entfernst, kommt dieses Handy nicht mehr an neue Daten;
  die bereits lokal gespeicherten bleiben aber auf dem Gerät.
