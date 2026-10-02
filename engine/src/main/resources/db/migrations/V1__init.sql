CREATE TABLE profile (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    name        TEXT    NOT NULL,
    xp_total    INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
);

CREATE TABLE decks (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT    NOT NULL,
    commanders     TEXT    NOT NULL,
    colors         TEXT    NOT NULL DEFAULT '',
    commander_set  TEXT,
    commander_num  TEXT,
    source         TEXT    NOT NULL DEFAULT 'text',
    source_url     TEXT,
    dck            TEXT    NOT NULL,
    card_count     INTEGER NOT NULL DEFAULT 0,
    valid          INTEGER NOT NULL DEFAULT 1,
    validation     TEXT,
    mastery_xp     INTEGER NOT NULL DEFAULT 0,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

CREATE TABLE games (
    id           TEXT PRIMARY KEY,
    started_at   INTEGER NOT NULL,
    ended_at     INTEGER,
    duration_ms  INTEGER,
    turns        INTEGER,
    deck_id      INTEGER,
    deck_name    TEXT,
    commander    TEXT,
    result       TEXT,
    placement    INTEGER,
    tempo        TEXT,
    mulligans    INTEGER,
    xp_awarded   INTEGER NOT NULL DEFAULT 0,
    end_reason   TEXT
);
CREATE INDEX games_deck ON games (deck_id);

CREATE TABLE game_seats (
    game_id          TEXT    NOT NULL,
    seat             INTEGER NOT NULL,
    name             TEXT,
    is_human         INTEGER NOT NULL DEFAULT 0,
    deck_name        TEXT,
    commander        TEXT,
    placement        INTEGER,
    eliminated_turn  INTEGER,
    life_end         INTEGER,
    mulligans        INTEGER,
    PRIMARY KEY (game_id, seat)
);

CREATE TABLE game_card_stats (
    game_id          TEXT    NOT NULL,
    deck_id          INTEGER,
    card_name        TEXT    NOT NULL,
    opening          INTEGER NOT NULL DEFAULT 0,
    drawn            INTEGER NOT NULL DEFAULT 0,
    cast             INTEGER NOT NULL DEFAULT 0,
    first_cast_turn  INTEGER,
    PRIMARY KEY (game_id, card_name)
);
CREATE INDEX card_stats_deck ON game_card_stats (deck_id, card_name);

CREATE TABLE xp_ledger (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    game_id  TEXT,
    deck_id  INTEGER,
    source   TEXT    NOT NULL,
    amount   INTEGER NOT NULL,
    ts       INTEGER NOT NULL
);

CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    json  TEXT NOT NULL
);
