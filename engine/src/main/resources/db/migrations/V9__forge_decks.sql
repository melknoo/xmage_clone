-- Decktext-Format: 1 = XMage .dck (bis 0.1.x), 2 = MageLite-Text v2 (Forge)
ALTER TABLE decks ADD COLUMN deck_format INTEGER NOT NULL DEFAULT 1;
-- alter XMage-Text nach der Umstellung (DeckMigration), sonst NULL
ALTER TABLE decks ADD COLUMN dck_legacy TEXT;
