-- eigene Reihenfolge der Decks innerhalb eines Ordners (NULL = neu, vorne nach updated_at)
ALTER TABLE decks ADD COLUMN sort_order INTEGER;
