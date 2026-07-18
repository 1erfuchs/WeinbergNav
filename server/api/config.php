<?php
/**
 * Reihen-Navigator – zentrale Konfiguration und Hilfsfunktionen.
 *
 * NUR DIESE DATEI musst du anpassen: die vier DB-Zugangsdaten unten.
 * Die bekommst du von deinem Webhosting (oft im Kundenmenü unter
 * "Datenbanken"). Danach nichts weiter ändern.
 */

// ============ HIER DEINE DATENBANK-ZUGANGSDATEN EINTRAGEN ============
const DB_HOST = 'localhost';          // meist localhost
const DB_NAME = 'DEINE_DATENBANK';
const DB_USER = 'DEIN_DB_BENUTZER';
const DB_PASS = 'DEIN_DB_PASSWORT';
// ====================================================================

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

// Nur HTTPS zulassen (Passwörter dürfen nicht unverschlüsselt reisen)
$https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
      || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https');
if (!$https) {
    http_response_code(400);
    echo json_encode(['error' => 'Nur über HTTPS erreichbar.']);
    exit;
}

function db(): PDO {
    static $pdo = null;
    if ($pdo === null) {
        try {
            $pdo = new PDO(
                'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
                DB_USER, DB_PASS,
                [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                 PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]
            );
        } catch (Throwable $e) {
            fail(500, 'Datenbank nicht erreichbar');
        }
    }
    return $pdo;
}

function body(): array {
    $raw = file_get_contents('php://input');
    $j = json_decode($raw, true);
    return is_array($j) ? $j : [];
}

function ok(array $data = []): void {
    echo json_encode(['ok' => true] + $data, JSON_UNESCAPED_UNICODE);
    exit;
}

function fail(int $code, string $msg): void {
    http_response_code($code);
    echo json_encode(['error' => $msg], JSON_UNESCAPED_UNICODE);
    exit;
}

/** Prüft den Token aus dem Authorization-Header und liefert den Benutzernamen */
function requireUser(): string {
    $hdr = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if (stripos($hdr, 'Bearer ') === 0) $hdr = substr($hdr, 7);
    $token = trim($hdr);
    if ($token === '') fail(401, 'Nicht angemeldet');

    $st = db()->prepare('SELECT username FROM users WHERE token = ? LIMIT 1');
    $st->execute([$token]);
    $row = $st->fetch();
    if (!$row) fail(401, 'Sitzung abgelaufen – bitte neu anmelden');
    return $row['username'];
}
