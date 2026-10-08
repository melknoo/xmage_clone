// Eigene Decks und Import-Vorschau (GET/POST /api/decks, /api/decks/parse, /api/decks/url).
import { api } from './client'

/** Gespeichertes eigenes Deck (GET /api/decks, POST /api/decks) */
export interface StoredDeck {
  id: number
  name: string
  commanders: string[]
  /** Farbidentitaet, z.B. "WUB" ("" = farblos) */
  colors: string
  commanderSet?: string
  commanderNum?: string
  /** 'text' | 'archidekt' | 'moxfield' */
  source: string
  sourceUrl?: string
  cardCount: number
  valid: boolean
  validation?: string
  masteryXp: number
  createdAt: number
  updatedAt: number
  /** Meisterschaftsstufe 1..10 (Engine-Progression) */
  masteryLevel?: number
  /** XP-Schwelle der naechsten Stufe (wie Reward.masteryNext) */
  masteryNext?: number
  /** nur in der Liste (GET /api/decks): Spiele ohne Fehlerende mit diesem Deck */
  games?: number
  /** nur in der Liste: davon gewonnen */
  wins?: number
  /** Ordner (eine Ebene); '' = ohne Ordner */
  folder?: string
  /** manuell gesetzte Commander-Bracket 1-5; fehlt = Vorschlag gilt */
  bracket?: number
  /** Vorschlag der Engine (2-4) */
  bracketAuto?: number
  /** Gruende des Vorschlags */
  bracketInfo?: BracketReason[]
  /** eigene Reihenfolge im Ordner (Drag & Drop); fehlt = neu, steht vorne */
  sortOrder?: number
}

/** Grund fuer einen Bracket-Vorschlag (Engine: BracketAnalyzer) */
export interface BracketReason {
  kind: 'gameChanger' | 'combo' | 'mld' | 'extraTurn' | 'tutor'
  cards: string[]
}

/** grobe Kartenart der Vorschau (Rangfolge creature > land > planeswalker > battle > instant > sorcery > artifact > enchantment > other) */
export type DeckCardType = 'creature' | 'land' | 'planeswalker' | 'battle' | 'instant' | 'sorcery' | 'artifact' | 'enchantment' | 'other'

/** Problemzeile der Import-Vorschau (nur Vorschau, nie beim Speichern) */
export interface DeckIssue {
  /** 1-basierte Zeile im Rohtext (Leer- und Kopfzeilen mitgezaehlt) */
  line: number
  count: number
  name: string
  /** "Meintest du ...?" - fehlt ohne guten Treffer */
  suggestion?: string
  /** unknown = Karte unbekannt; unfinished = in XMage noch nicht umgesetzt */
  kind: 'unknown' | 'unfinished'
}

/** Ergebnis von POST /api/decks/parse bzw. /api/decks/url */
export interface DeckPreview {
  name: string
  commanders: { name: string; set: string; number: string; count: number; commander?: boolean }[]
  cardCount: number
  /** "1 Name" je unbekannter Zeile (bleibt fuer alte Aufrufer; neu: issues) */
  unknown: string[]
  unfinished: string[]
  /** mit Zeilennummer und Vorschlag; fehlt bei alten Engines */
  issues?: DeckIssue[]
  needsCommander: boolean
  candidates: string[]
  cards: { name: string; set: string; num: string; count: number; type?: DeckCardType }[]
  valid: boolean
  /** XMage-Validierung (englisch) */
  validation?: string
  colors?: string
  commanderSet?: string
  commanderNum?: string
  /** Bracket-Vorschlag der Engine (2-4) und Gruende */
  bracketAuto?: number
  bracketInfo?: BracketReason[]
  /** nur /api/decks/url: vom Deck-Autor gesetzte Bracket (Archidekt/Moxfield) */
  bracket?: number
  /** nur /api/decks/url: Text der Liste */
  text?: string
  source?: string
  sourceUrl?: string
}

/** Speichern (POST /api/decks) */
export interface DeckSaveRequest {
  id?: number
  name: string
  text: string
  commanders?: string[]
  source?: string
  sourceUrl?: string
  folder?: string
  /** 0 = Vorschlag gilt, 1-5 manuell */
  bracket?: number
}

export const decksApi = {
  list: () => api.get<StoredDeck[]>('/api/decks'),
  text: (id: number) => api.get<{ text: string }>(`/api/decks/${id}/text`),
  save: (req: DeckSaveRequest) => api.post<StoredDeck>('/api/decks', req),
  /** Ordner/Bracket ohne Neuspeichern; bracket 0 = Vorschlag gilt */
  meta: (id: number, meta: { folder?: string; bracket?: number }) => api.post<StoredDeck>(`/api/decks/${id}/meta`, meta),
  /** Reihenfolge eines Ordners; die Decks landen dabei in diesem Ordner */
  order: (folder: string, ids: number[]) => api.post<{ decks: number }>('/api/decks/order', { folder, ids }),
  /** to '' loest den Ordner auf */
  renameFolder: (from: string, to: string) => api.post<{ decks: number }>('/api/decks/folders/rename', { from, to }),
  remove: (id: number) => api.del<{ deleted: boolean }>(`/api/decks/${id}`),
  parse: (text: string, name?: string, commanders?: string[]) => api.post<DeckPreview>('/api/decks/parse', { text, name, commanders }),
  /** json: vom Client geholte Rohantwort (Cloudflare-Sperre: 409 {blocked, apiUrl}), sonst laedt die Engine selbst */
  fromUrl: (url: string, json?: string) => api.post<DeckPreview>('/api/decks/url', json === undefined ? { url } : { url, json }),
}
