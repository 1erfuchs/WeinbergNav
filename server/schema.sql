-- Reihen-Navigator – Datenbank
-- In phpMyAdmin importieren. Zeichensatz utf8mb4 (Umlaute + Emojis).

CREATE TABLE IF NOT EXISTS users (
  id        INT AUTO_INCREMENT PRIMARY KEY,
  username  VARCHAR(64) NOT NULL UNIQUE,
  pass_hash VARCHAR(255) NOT NULL,
  token     VARCHAR(64) DEFAULT NULL,
  created   BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Globaler, streng steigender Zähler. Jeder gespeicherte Datensatz bekommt
-- daraus eine eindeutige Nummer (srv). Der Abgleich läuft über diese Nummer,
-- NICHT über die Uhr der Handys -> keine Datenverluste bei Uhr-Versatz.
CREATE TABLE IF NOT EXISTS meta (
  k   VARCHAR(32) PRIMARY KEY,
  val BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO meta (k, val) VALUES ('seq', 0)
  ON DUPLICATE KEY UPDATE val = val;

-- Eine Tabelle je Datentyp, gleiches Muster:
--   id          = ID aus der App
--   srv         = servereigene Reihenfolge-Nummer (für den Abgleich)
--   updated_at  = Zeitstempel vom Gerät (für "neuere gewinnt" bei Konflikten)
--   user, deleted, data (kompletter Datensatz als JSON)

CREATE TABLE IF NOT EXISTS fields (
  id         VARCHAR(64) PRIMARY KEY,
  srv        BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  user       VARCHAR(64) NOT NULL DEFAULT '',
  deleted    TINYINT(1) NOT NULL DEFAULT 0,
  data       MEDIUMTEXT NOT NULL,
  INDEX idx_fields_srv (srv)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sessions (
  id         VARCHAR(64) PRIMARY KEY,
  srv        BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  user       VARCHAR(64) NOT NULL DEFAULT '',
  deleted    TINYINT(1) NOT NULL DEFAULT 0,
  data       MEDIUMTEXT NOT NULL,
  INDEX idx_sessions_srv (srv)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notes (
  id         VARCHAR(64) PRIMARY KEY,
  srv        BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  user       VARCHAR(64) NOT NULL DEFAULT '',
  deleted    TINYINT(1) NOT NULL DEFAULT 0,
  data       MEDIUMTEXT NOT NULL,
  INDEX idx_notes_srv (srv)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
