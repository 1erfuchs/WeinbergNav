<?php
/**
 * Nutzerverwaltung im Browser.
 * Aufruf:  https://deinserver/rn/api/admin.php
 *
 * Geschützt durch den ADMIN_KEY unten. Setz ihn auf etwas Eigenes,
 * das nur du kennst. Ohne den Schlüssel kommt niemand rein.
 */
require __DIR__ . '/config.php';

// ============ HIER EIGENEN ADMIN-SCHLÜSSEL SETZEN ============
const ADMIN_KEY = 'BITTE-AENDERN-langes-eigenes-passwort';
// ============================================================

// Diese Seite liefert HTML, kein JSON
header('Content-Type: text/html; charset=utf-8');

$key = $_POST['key'] ?? $_GET['key'] ?? '';
$authed = hash_equals(ADMIN_KEY, $key);
$msg = '';

if ($authed && ($_POST['action'] ?? '') === 'add') {
    $u = trim($_POST['username'] ?? '');
    $p = $_POST['password'] ?? '';
    if ($u === '' || strlen($p) < 4) {
        $msg = 'Name fehlt oder Passwort zu kurz (min. 4 Zeichen).';
    } else {
        try {
            $st = db()->prepare('INSERT INTO users (username, pass_hash, created) VALUES (?,?,?)');
            $st->execute([$u, password_hash($p, PASSWORD_DEFAULT), (int)(microtime(true) * 1000)]);
            $msg = "Nutzer \"$u\" angelegt.";
        } catch (Throwable $e) {
            $msg = 'Fehler: Name schon vergeben?';
        }
    }
}
if ($authed && ($_POST['action'] ?? '') === 'del') {
    $st = db()->prepare('DELETE FROM users WHERE username = ?');
    $st->execute([$_POST['username'] ?? '']);
    $msg = 'Nutzer gelöscht.';
}

$users = [];
if ($authed) {
    $users = db()->query('SELECT username, created, (token IS NOT NULL) AS online FROM users ORDER BY username')->fetchAll();
}
?>
<!DOCTYPE html>
<html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Reihen-Navigator – Nutzer</title>
<style>
  body{font-family:system-ui,sans-serif;background:#12161c;color:#eef2f7;max-width:560px;margin:0 auto;padding:20px}
  h1{color:#e0b34a;font-size:20px}
  input{width:100%;padding:11px;margin:5px 0;border-radius:10px;border:1px solid #323c4a;background:#232b37;color:#fff;font-size:15px;box-sizing:border-box}
  button{background:#e0b34a;color:#1a1305;border:0;border-radius:10px;padding:11px 16px;font-weight:700;font-size:15px;cursor:pointer}
  .row{display:flex;align-items:center;gap:10px;padding:10px;border:1px solid #323c4a;border-radius:10px;margin:6px 0}
  .row .grow{flex:1}
  .del{background:#e0625a;color:#2a0806}
  .msg{background:#232b37;border:1px solid #5ac47a;border-radius:10px;padding:10px;margin:10px 0}
  small{color:#93a0b2}
</style></head><body>
<h1>Reihen-Navigator · Nutzerverwaltung</h1>
<?php if ($msg): ?><div class="msg"><?= htmlspecialchars($msg) ?></div><?php endif; ?>
<?php if (!$authed): ?>
  <form method="post">
    <p>Bitte Admin-Schlüssel eingeben:</p>
    <input type="password" name="key" placeholder="Admin-Schlüssel" autofocus>
    <button type="submit">Anmelden</button>
  </form>
<?php else: ?>
  <form method="post">
    <input type="hidden" name="key" value="<?= htmlspecialchars($key) ?>">
    <input type="hidden" name="action" value="add">
    <p><b>Neuen Nutzer anlegen</b></p>
    <input name="username" placeholder="Name (z. B. Franz)" autocomplete="off">
    <input name="password" placeholder="Passwort (min. 4 Zeichen)" autocomplete="off">
    <button type="submit">Anlegen</button>
  </form>
  <p><b>Vorhandene Nutzer</b></p>
  <?php foreach ($users as $u): ?>
    <div class="row">
      <div class="grow"><?= htmlspecialchars($u['username']) ?>
        <?php if ($u['online']): ?><small>· angemeldet</small><?php endif; ?></div>
      <form method="post" onsubmit="return confirm('Nutzer wirklich löschen?')">
        <input type="hidden" name="key" value="<?= htmlspecialchars($key) ?>">
        <input type="hidden" name="action" value="del">
        <input type="hidden" name="username" value="<?= htmlspecialchars($u['username']) ?>">
        <button class="del" type="submit">Löschen</button>
      </form>
    </div>
  <?php endforeach; ?>
  <p><small>Die App-Adresse für deine Leute lautet:<br>
  <b>https://<?= htmlspecialchars($_SERVER['HTTP_HOST'] . dirname($_SERVER['REQUEST_URI'])) ?></b></small></p>
<?php endif; ?>
</body></html>
