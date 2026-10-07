// Statistik und Verlauf (GET /api/stats/overview, /api/stats/decks, /api/stats/decks/{id}/cards, /api/history).
import { api } from './client'

/** GET /api/stats/overview (Spiele mit Fehlerende zaehlen nie) */
export interface StatsOverview {
  totals: {
    games: number
    wins: number
    avgPlace: number | null
    avgTurns: number | null
    avgDurationMs: number | null
    avgMulligans: number | null
    gamesWithMulligan: number
    xp: number
    /** Summe aller Spieldauern (ms) - "Spielzeit gesamt"; null ohne Spiele */
    totalDurationMs?: number | null
    /** Ende des ersten Spiels (ms) - "seit ..."; null ohne Spiele */
    firstEndedAt?: number | null
  }
  byTempo: { tempo: string; games: number; wins: number; avgPlace: number }[]
  byMulligans: { mulligans: number; games: number; wins: number }[]
  /** Bot-Commander (max. 15, nach Spielen) */
  opponents: {
    commander: string
    games: number
    humanWins: number
    avgPlace: number
    /** wie oft dieser Gegner vor dir platziert war ("N x vor dir") */
    aheadOfYou?: number
  }[]
  /** letzte 30 Spiele, neueste zuerst */
  recentPlaces: { placement: number; result: string; endedAt: number }[]
}

/** Zeile von GET /api/stats/decks (deckId -1 = vorgefertigtes/zufaelliges Deck) */
export interface DeckStat {
  deckId: number
  deckName: string
  commander: string
  games: number
  wins: number
  avgPlace: number
  avgTurns: number
  avgMulligans: number
  lastPlayed: number
  /** nur eigene Decks */
  masteryXp?: number
  /** nur eigene Decks: Stufe 1..10 */
  masteryLevel?: number
  /** nur eigene Decks: XP-Schwelle der naechsten Stufe */
  masteryNext?: number
  colors?: string
  commanderSet?: string
  commanderNum?: string
}

/** Karte in GET /api/stats/decks/{id}/cards */
export interface CardStat {
  name: string
  /** Summe: in Starthaenden */
  opening: number
  /** Summe: gezogen */
  drawn: number
  /** Spiele, in denen gewirkt */
  gamesCast: number
  /** Summe: Wirkungen */
  cast: number
  avgFirstCastTurn: number | null
  winsWhenCast: number
  gamesSeen: number
  /** Spiele, in denen die Karte auf der Hand war (Starthand oder gezogen) */
  gamesInHand?: number
}

/** GET /api/stats/decks/{id}/cards */
export interface DeckCardStats {
  games: number
  cards: CardStat[]
  commander: { avgFirstCastTurn?: number | null }
}

/** Sitz eines Verlaufs-Spiels (nach Platz sortiert) */
export interface HistorySeat {
  name: string
  /** 1 = Mensch, 0 = Bot (SQLite-Integer) */
  human: number
  deckName?: string | null
  commander: string
  placement: number
  eliminatedTurn?: number | null
  life: number
}

/** Zeile von GET /api/history?limit=n (max. 200), neueste zuerst; enthaelt auch Spiele mit Fehlerende */
export interface HistoryGame {
  id: string
  startedAt?: number
  endedAt: number
  durationMs: number
  turns: number
  /** eigenes Deck (fuer Kunst ueber /api/decks), sonst null */
  deckId?: number | null
  deckName: string
  commander: string
  result: string
  placement: number
  tempo: string
  mulligans: number
  xp: number
  endReason: string
  seats: HistorySeat[]
}

export const statsApi = {
  overview: () => api.get<StatsOverview>('/api/stats/overview'),
  decks: () => api.get<DeckStat[]>('/api/stats/decks'),
  deckCards: (deckId: number) => api.get<DeckCardStats>(`/api/stats/decks/${deckId}/cards`),
  history: (limit = 50) => api.get<HistoryGame[]>(`/api/history?limit=${limit}`),
}
