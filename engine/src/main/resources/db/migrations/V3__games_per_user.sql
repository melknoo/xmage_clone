CREATE TABLE games_new (
    id           TEXT    NOT NULL,
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
    end_reason   TEXT,
    user_id      INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (id, user_id)
);
INSERT INTO games_new (id, started_at, ended_at, duration_ms, turns, deck_id, deck_name, commander, result, placement, tempo, mulligans, xp_awarded, end_reason, user_id)
    SELECT id, started_at, ended_at, duration_ms, turns, deck_id, deck_name, commander, result, placement, tempo, mulligans, xp_awarded, end_reason, user_id FROM games;
DROP TABLE games;
ALTER TABLE games_new RENAME TO games;
CREATE INDEX games_deck ON games (deck_id);
CREATE INDEX games_user ON games (user_id, ended_at);

CREATE TABLE game_card_stats_new (
    game_id          TEXT    NOT NULL,
    user_id          INTEGER NOT NULL DEFAULT 1,
    deck_id          INTEGER,
    card_name        TEXT    NOT NULL,
    opening          INTEGER NOT NULL DEFAULT 0,
    drawn            INTEGER NOT NULL DEFAULT 0,
    cast             INTEGER NOT NULL DEFAULT 0,
    first_cast_turn  INTEGER,
    PRIMARY KEY (game_id, user_id, card_name)
);
INSERT INTO game_card_stats_new (game_id, user_id, deck_id, card_name, opening, drawn, "cast", first_cast_turn)
    SELECT game_id, 1, deck_id, card_name, opening, drawn, "cast", first_cast_turn FROM game_card_stats;
DROP TABLE game_card_stats;
ALTER TABLE game_card_stats_new RENAME TO game_card_stats;
CREATE INDEX card_stats_deck ON game_card_stats (deck_id, card_name);
