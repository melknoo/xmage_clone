// Lobby und Tische (Server-Modus). Synchronisation per Polling.
import { api } from './client'
import type { DeckSpec, Tempo } from './types'

export type SeatKind = 'OPEN' | 'HUMAN' | 'BOT'

export interface TableSeat {
  kind: SeatKind
  userId?: number
  name?: string
  me?: boolean
  /** eigene Deck-Angabe (nur fuer den eigenen Platz bzw. Bots des Gastgebers) */
  deck?: DeckSpec
  /** bisheriges Anzeigeformat "Name (Commander)"; null = noch kein Deck gewaehlt */
  deckName?: string | null
  ready?: boolean
  // Deck-Infos fuer jeden besetzten Platz (auch fremde); fehlen, solange kein Deck gewaehlt ist
  /** Deckname ohne Commander (Zufall: "Zufälliges Deck") */
  deckTitle?: string
  /** Commander-Namen, mehrere mit " & " verbunden */
  commander?: string
  /** Farbidentitaet wie SampleDeck.colors (z.B. "WUB") */
  colors?: string
  /** Druck des (ersten) Commanders fuer die Kunst (cardImageUrl {set, num}) */
  commanderSet?: string
  commanderNum?: string
}

export interface TableChat {
  ts: number
  userId: number
  name: string
  text: string
}

export interface Table {
  id: string
  name: string
  hostUserId: number
  hostName: string
  tempo: Tempo
  state: 'LOBBY' | 'RUNNING'
  gameId?: string | null
  lastGameId?: string | null
  createdAt: number
  updatedAt: number
  seats: TableSeat[]
  mySeat: number | null
  host: boolean
  humans: number
  /** nur fuer Sitzende gefuellt, sonst [] */
  chat: TableChat[]
  /** nur bei state=RUNNING: Zug des laufenden Spiels (0 = noch kein State) */
  turn?: number
  /** Zuschauen moeglich: RUNNING, ich sitze nicht selbst am Tisch, weniger als 8 Zuschauer (immer gesetzt) */
  canSpectate?: boolean
  /** nur bei state=RUNNING: Anzahl Zuschauer (0-8) */
  spectators?: number
}

export const tablesApi = {
  list: () => api.get<Table[]>('/api/tables'),
  mine: () => api.get<Table>('/api/tables/mine'),
  get: (id: string) => api.get<Table>(`/api/tables/${encodeURIComponent(id)}`),
  create: (name?: string, tempo?: Tempo) => api.post<Table>('/api/tables', { name, tempo }),
  join: (id: string) => api.post<Table>(`/api/tables/${encodeURIComponent(id)}/join`),
  leave: (id: string) => api.post<{ left: boolean; closed: boolean }>(`/api/tables/${encodeURIComponent(id)}/leave`),
  setMyDeck: (id: string, deck: DeckSpec | null) => api.put<Table>(`/api/tables/${encodeURIComponent(id)}/seat`, { deck }),
  setSeat: (id: string, n: number, kind: SeatKind, deck?: DeckSpec | null) => api.put<Table>(`/api/tables/${encodeURIComponent(id)}/seats/${n}`, { kind, deck: deck ?? null }),
  update: (id: string, patch: { name?: string; tempo?: Tempo }) => api.put<Table>(`/api/tables/${encodeURIComponent(id)}`, patch),
  start: (id: string) => api.post<Table>(`/api/tables/${encodeURIComponent(id)}/start`),
  chat: (id: string, text: string) => api.post<Table>(`/api/tables/${encodeURIComponent(id)}/chat`, { text }),
}

export function tableLink(id: string): string {
  return `${window.location.origin}${window.location.pathname}#table=${id}`
}

/** Tisch-id aus dem Link (#table=XXXXXX) lesen und aus der URL entfernen. */
export function takeTableFromUrl(): string | null {
  const m = window.location.hash.match(/table=([A-Za-z0-9]+)/)
  if (!m) return null
  try {
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
  } catch {
    /* egal */
  }
  return m[1].toUpperCase()
}
