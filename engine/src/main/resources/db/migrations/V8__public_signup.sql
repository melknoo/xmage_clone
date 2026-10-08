-- Oeffentliche Registrierung: Konto-Art (friend = eingeladen/Owner, public = selbst registriert),
-- E-Mail-Bestaetigung, Tokens fuer Bestaetigung/Passwort-Reset, Laufzeit-Budget pro Monat
ALTER TABLE users ADD COLUMN tier TEXT NOT NULL DEFAULT 'friend';
ALTER TABLE users ADD COLUMN email_verified_at INTEGER;
ALTER TABLE users ADD COLUMN created_ip TEXT;

CREATE TABLE email_tokens (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     TEXT    NOT NULL,
    token_hash  TEXT    NOT NULL UNIQUE,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL
);
CREATE INDEX email_tokens_user ON email_tokens (user_id, purpose);

CREATE TABLE uptime_month (
    month       TEXT    PRIMARY KEY,
    minutes     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE kv (
    key         TEXT    PRIMARY KEY,
    value       TEXT
);
