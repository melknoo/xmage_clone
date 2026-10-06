-- Konto sichern: optionale E-Mail + Passwort (PBKDF2) und Sessions statt Code im Cookie
ALTER TABLE users ADD COLUMN email TEXT COLLATE NOCASE;
ALTER TABLE users ADD COLUMN pw_hash TEXT;
ALTER TABLE users ADD COLUMN pw_set_at INTEGER;
CREATE UNIQUE INDEX users_email ON users (email) WHERE email IS NOT NULL;

CREATE TABLE sessions (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT    NOT NULL UNIQUE,
    via         TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    last_seen   INTEGER
);
CREATE INDEX sessions_user ON sessions (user_id);
