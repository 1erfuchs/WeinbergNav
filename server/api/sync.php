<?php
/**
 * Synchronisierung.  POST mit Token im Authorization-Header.
 *
 * Eingang:
 * {
 *   "since": 0,                       // hoechste bereits erhaltene srv-Nummer
 *   "fields":[...], "sessions":[...], "notes":[...]   // lokale Aenderungen
 * }
 *
 * 1. PUSH: gesendete Datensaetze werden uebernommen, aber nur wenn ihr
 *    Geraete-Zeitstempel NEUER ist als der auf dem Server ("neuere gewinnt").
 *    Jeder gespeicherte Datensatz erhaelt eine neue, streng steigende
 *    Server-Nummer (srv).
 * 2. PULL: alle Datensaetze mit srv > since werden zurueckgegeben; jeder
 *    traegt seine srv als "_srv". Das Handy merkt sich die hoechste erhaltene
 *    srv als neues "since". Dadurch haengt der Abgleich NICHT an den Uhren
 *    der Geraete -> keine Datenverluste bei Uhr-Versatz.
 */
require __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') fail(405, 'POST erwartet');

$user = requireUser();
$in = body();
$since = (int)($in['since'] ?? 0);
$tables = ['fields', 'sessions', 'notes'];
$pdo = db();

/** naechste globale Nummer, atomar */
function nextSeq(PDO $pdo): int {
    $pdo->prepare("UPDATE meta SET val = val + 1 WHERE k = 'seq'")->execute();
    return (int)$pdo->query("SELECT val FROM meta WHERE k = 'seq'")->fetchColumn();
}

// ---------- 1. PUSH ----------
$pushed = 0;
$pdo->beginTransaction();
try {
    foreach ($tables as $t) {
        $rows = $in[$t] ?? [];
        if (!is_array($rows)) continue;

        $sel = $pdo->prepare("SELECT updated_at FROM $t WHERE id = ?");
        $ins = $pdo->prepare(
            "INSERT INTO $t (id, srv, updated_at, user, deleted, data)
             VALUES (:id, :srv, :ua, :user, :del, :data)
             ON DUPLICATE KEY UPDATE
               srv=VALUES(srv), updated_at=VALUES(updated_at),
               user=VALUES(user), deleted=VALUES(deleted), data=VALUES(data)"
        );
        foreach ($rows as $r) {
            $id = (string)($r['id'] ?? '');
            if ($id === '') continue;
            $ua = (int)($r['updatedAt'] ?? 0);

            $sel->execute([$id]);
            $cur = $sel->fetchColumn();
            if ($cur !== false && (int)$cur >= $ua) continue;   // vorhandener Stand ist neuer

            // _srv nicht mitspeichern, nur die echten Felder
            unset($r['_srv']);
            $ins->execute([
                ':id'=>$id, ':srv'=>nextSeq($pdo), ':ua'=>$ua,
                ':user'=>(string)($r['user'] ?? $user),
                ':del'=>!empty($r['deleted']) ? 1 : 0,
                ':data'=>json_encode($r, JSON_UNESCAPED_UNICODE),
            ]);
            $pushed++;
        }
    }
    $pdo->commit();
} catch (Throwable $e) {
    $pdo->rollBack();
    fail(500, 'Speichern fehlgeschlagen');
}

// ---------- 2. PULL ----------
$out = [];
foreach ($tables as $t) {
    $st = $pdo->prepare("SELECT srv, data FROM $t WHERE srv > ? ORDER BY srv ASC");
    $st->execute([$since]);
    $list = [];
    foreach ($st as $row) {
        $obj = json_decode($row['data'], true);
        if (is_array($obj)) { $obj['_srv'] = (int)$row['srv']; $list[] = $obj; }
    }
    $out[$t] = $list;
}

ok(['pushed'=>$pushed, 'fields'=>$out['fields'],
    'sessions'=>$out['sessions'], 'notes'=>$out['notes']]);
