// Admin-Bereich (Server-Modus): Einladungen (/api/admin/invites), Nutzer, Server-Uebersicht, Eingriffe.
import { api } from './client'

/** Konto in GET /api/admin/invites */
export interface Account {
  id: number
  name: string
  admin: boolean
  createdAt: number
  lastSeen: number | null
  email: string | null
  hasPassword: boolean
}

/** neues Konto mit Einladungscode (POST /api/admin/invites); der Code wird nur hier einmal gezeigt */
export interface CreatedAccount {
  id: number
  name: string
  code: string
}

export const adminApi = {
  list: () => api.get<Account[]>('/api/admin/invites'),
  /** Name 1-24 Zeichen */
  create: (name: string) => api.post<CreatedAccount>('/api/admin/invites', { name }),
  /** neuer Einladungscode (alter wird ungueltig) */
  rotate: (id: number) => api.post<{ id: number; code: string }>(`/api/admin/invites/${id}/rotate`),
  /** nicht das eigene Konto */
  remove: (id: number) => api.del<{ deleted: boolean }>(`/api/admin/invites/${id}`),
  /** alle Konten mit Kennzahlen und Status */
  users: () => api.get<AdminUser[]>('/api/admin/users'),
  user: (id: number) => api.get<AdminUserDetail>(`/api/admin/users/${id}`),
  /** alle Sessions beenden; der Code bleibt gueltig */
  logout: (id: number) => api.post<{ ok: boolean }>(`/api/admin/users/${id}/logout`),
  server: () => api.get<ServerInfo>('/api/admin/server'),
  abortGame: (id: string) => api.post<{ ok: boolean }>(`/api/admin/games/${id}/abort`),
  closeTable: (id: string) => api.del<{ ok: boolean }>(`/api/admin/tables/${id}`),
}

export type PresenceStatus = 'online' | 'table' | 'game' | 'offline'

/** Zeile in GET /api/admin/users */
export interface AdminUser extends Account {
  xp: number
  level: number
  title: string
  games: number
  wins: number
  lastGameAt: number | null
  decks: number
  sessions: number
  status: PresenceStatus
  tableName: string | null
}

export interface AdminGameRow {
  id: string
  startedAt: number
  endedAt: number | null
  durationMs: number | null
  turns: number | null
  deckName: string | null
  commander: string | null
  result: string | null
  placement: number | null
  tempo: string | null
  endReason: string | null
  xp: number
}

export interface AdminDeckRow {
  id: number
  name: string
  commanders: string
  cards: number
  valid: boolean
  masteryXp: number
  updatedAt: number
}

export interface AdminSessionRow {
  id: number
  /** "code" | "password" */
  via: string
  createdAt: number
  lastSeen: number | null
}

/** GET /api/admin/users/{id} */
export interface AdminUserDetail {
  user: AdminUser
  games: AdminGameRow[]
  decks: AdminDeckRow[]
  sessions: AdminSessionRow[]
}

export interface AdminSeat {
  userId: number
  name: string
  connected: boolean
  conceded: boolean
}

/** laufendes Spiel; table = Tischname oder null (allein gegen Bots) */
export interface AdminGame {
  id: string
  table: string | null
  tempo: string
  startedAt: number
  turn: number
  bots: number
  spectators: number
  humans: AdminSeat[]
  /** Name des Gastgebers, wenn das Spiel auf dessen Rechner laeuft (Host-Link); sonst null/fehlt */
  remoteHost?: string | null
}

export interface AdminTable {
  id: string
  name: string
  hostName: string
  /** LOBBY | RUNNING */
  state: string
  humans: number
  bots: number
  open: number
  gameId: string | null
  createdAt: number
  hosting?: 'SERVER' | 'REMOTE'
  locked?: boolean
}

/** GET /api/admin/server */
export interface ServerInfo {
  version: string
  startedAt: number
  uptimeMs: number
  heapUsed: number
  heapMax: number
  maxGames: number
  running: number
  online: number
  tableCount: number
  games: AdminGame[]
  tables: AdminTable[]
  /** angebundene Engines von Gastgebern (Host-Link) */
  hostLinks?: number
}
