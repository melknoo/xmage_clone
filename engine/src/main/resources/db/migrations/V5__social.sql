-- Freunde (Anfrage + Annehmen) und Lobby-Chat-Einstellung pro Konto
CREATE TABLE friendships (
    a            INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    b            INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_by INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    accepted_at  INTEGER,
    PRIMARY KEY (a, b),
    CHECK (a < b)
);
CREATE INDEX friendships_b ON friendships (b);

ALTER TABLE users ADD COLUMN lobby_chat INTEGER NOT NULL DEFAULT 1;
