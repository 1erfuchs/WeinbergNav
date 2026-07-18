<?php
/**
 * Kurzer Selbsttest. Im Browser aufrufen:
 *   https://deinserver.at/rn/api/ping.php
 * Zeigt, ob PHP läuft, HTTPS aktiv ist und die Datenbank erreichbar ist.
 */
require __DIR__ . '/config.php';

$out = ['php' => PHP_VERSION, 'https' => true];
try {
    $n = (int)db()->query('SELECT COUNT(*) FROM users')->fetchColumn();
    $out['datenbank'] = 'verbunden';
    $out['nutzer_angelegt'] = $n;
    $out['bereit'] = $n > 0;
    $out['hinweis'] = $n > 0
        ? 'Alles bereit. Leute können sich in der App anmelden.'
        : 'Server läuft. Jetzt in admin.php Nutzer anlegen.';
} catch (Throwable $e) {
    http_response_code(500);
    $out['datenbank'] = 'FEHLER – Tabellen angelegt? schema.sql importiert?';
    $out['bereit'] = false;
}
echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
