import { create } from 'zustand'
import { api, ApiError, setUnauthorizedHandler } from '../api/client'

export interface Me {
  id: number
  name: string
  admin: boolean
}

export type ServerMode = 'local' | 'server'

interface MeResponse {
  mode: ServerMode
  user: Me
}

interface AuthStore {
  /** null, bis /api/me einmal geantwortet hat */
  mode: ServerMode | null
  me: Me | null
  /** 'login': Server-Modus ohne gueltiges Cookie */
  status: 'unknown' | 'ok' | 'login'
  error: string | null
  busy: boolean
  load: () => Promise<void>
  login: (code: string) => Promise<boolean>
  logout: () => Promise<void>
  markLoggedOut: () => void
}

export const useAuth = create<AuthStore>((set, get) => {
  setUnauthorizedHandler(() => {
    if (get().mode === 'server') set({ status: 'login', me: null })
  })
  return {
    mode: null,
    me: null,
    status: 'unknown',
    error: null,
    busy: false,

    load: async () => {
      try {
        const r = await api.get<MeResponse>('/api/me')
        set({ mode: r.mode, me: r.user, status: 'ok', error: null })
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
          set({ mode: 'server', me: null, status: 'login' })
          return
        }
        throw e
      }
    },

    login: async (code) => {
      set({ busy: true, error: null })
      try {
        const r = await api.post<MeResponse>('/api/auth/login', { code })
        set({ mode: r.mode, me: r.user, status: 'ok' })
        return true
      } catch (e) {
        const msg = e instanceof ApiError && e.status === 401 ? 'Dieser Code ist nicht (mehr) gültig.' : e instanceof Error ? e.message : String(e)
        set({ error: msg })
        return false
      } finally {
        set({ busy: false })
      }
    },

    logout: async () => {
      try {
        await api.post('/api/auth/logout')
      } catch {
        /* egal */
      }
      window.location.reload()
    },

    markLoggedOut: () => set({ status: 'login', me: null }),
  }
})

/** Einladungscode aus dem Link (#invite=XXXX-XXXX-XXXX-XXXX) lesen und aus der URL entfernen. */
export function takeInviteFromUrl(): string | null {
  const m = window.location.hash.match(/invite=([^&]+)/)
  if (!m) return null
  try {
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
  } catch {
    /* egal */
  }
  return decodeURIComponent(m[1])
}
