CREATE TABLE users (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,
    code_hash   TEXT    UNIQUE,
    is_admin    INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL,
    last_seen   INTEGER
);
INSERT INTO users (id, name, code_hash, is_admin, created_at) VALUES (1, 'lokal', NULL, 1, 0);

ALTER TABLE decks ADD COLUMN user_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE games ADD COLUMN user_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE xp_ledger ADD COLUMN user_id INTEGER NOT NULL DEFAULT 1;
CREATE INDEX decks_user ON decks (user_id, updated_at);
CREATE INDEX games_user ON games (user_id, ended_at);

CREATE TABLE profile_new (
    id          INTEGER PRIMARY KEY,
    name        TEXT    NOT NULL,
    xp_total    INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
);
INSERT INTO profile_new (id, name, xp_total, created_at) SELECT id, name, xp_total, created_at FROM profile;
DROP TABLE profile;
ALTER TABLE profile_new RENAME TO profile;
