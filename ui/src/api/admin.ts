// Konten-Verwaltung fuer Admins (Server-Modus, /api/admin/invites).
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
}
