// Held (GET/PUT /api/profile).
import { api } from './client'

/** Held: Level, Titel, XP und Kennzahlen */
export interface Profile {
  name: string
  level: number
  title: string
  xpTotal: number
  xpIntoLevel: number
  xpForNext: number
  games: number
  wins: number
  /** aktuelle Siegesserie */
  streak: number
  /** naechster Titel; fehlt beim hoechsten Titel */
  nextTitle?: { level: number; title: string }
}

export const profileApi = {
  get: () => api.get<Profile>('/api/profile'),
  /** leerer Name -> 'Planeswalker' setzt der Aufrufer */
  rename: (name: string) => api.put<Profile>('/api/profile', { name }),
}
