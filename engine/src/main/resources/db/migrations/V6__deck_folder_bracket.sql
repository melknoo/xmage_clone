-- Deck-Ordner (eine Ebene, '' = ohne Ordner) und Commander-Bracket
ALTER TABLE decks ADD COLUMN folder TEXT NOT NULL DEFAULT '';
-- manuell gesetzt (1-5) oder NULL = Vorschlag gilt
ALTER TABLE decks ADD COLUMN bracket INTEGER;
-- Vorschlag aus BracketAnalyzer (2-4) und seine Gruende (JSON)
ALTER TABLE decks ADD COLUMN bracket_auto INTEGER;
ALTER TABLE decks ADD COLUMN bracket_info TEXT;
