<?php
/**
 * Anmeldung.  POST { "username": "...", "password": "..." }
 * Antwort:    { "ok": true, "token": "...", "username": "..." }
 *
 * Der Token wird in der App gespeichert und bei jeder Synchronisierung
 * mitgeschickt. So muss das Passwort nur einmal eingegeben werden.
 */
require __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') fail(405, 'POST erwartet');

$in = body();
$u = trim($in['username'] ?? '');
$p = $in['password'] ?? '';
if ($u === '' || $p === '') fail(400, 'Name und Passwort nötig');

$st = db()->prepare('SELECT username, pass_hash FROM users WHERE username = ? LIMIT 1');
$st->execute([$u]);
$row = $st->fetch();

// Immer prüfen (auch bei unbekanntem Namen), um Rückschlüsse zu vermeiden
$hash = $row['pass_hash'] ?? '$2y$10$invalidinvalidinvalidinvalidinvalidinvalidinva';
if (!$row || !password_verify($p, $hash)) {
    fail(401, 'Name oder Passwort falsch');
}

$token = bin2hex(random_bytes(24));
$up = db()->prepare('UPDATE users SET token = ? WHERE username = ?');
$up->execute([$token, $row['username']]);

ok(['token' => $token, 'username' => $row['username']]);
