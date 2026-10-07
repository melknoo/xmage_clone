import { create } from 'zustand'
import { api, ApiError, setUnauthorizedHandler } from '../api/client'

export interface Me {
  id: number
  name: string
  admin: boolean
  /** gesichertes Konto: E-Mail + Passwort gesetzt; sonst "Gast" (nur Einladungscode) */
  email: string | null
  hasPassword: boolean
  /**
   * Aktuelle Session ("angemeldet seit"). Die Engine sendet sie NICHT im user-Objekt, sondern als MeResponse.session;
   * der Store muss sie beim Uebernehmen hineinkopieren (noch offen). null/fehlt: lokal oder altes Code-Cookie.
   */
  session?: SessionInfo | null
}

/** Session-Info aus /api/me, /api/auth/login, /api/auth/register, PUT /api/auth/account */
export interface SessionInfo {
  /** Anmeldezeitpunkt (ms) */
  since: number
  /** Anmeldeweg */
  via: 'code' | 'password'
}

export type ServerMode = 'local' | 'server'

export interface MeResponse {
  mode: ServerMode
  user: Me
  /** nur Server-Modus mit Session-Cookie; sonst null */
  session?: SessionInfo | null
}

interface AuthStore {
  /** null, bis /api/me einmal geantwortet hat */
  mode: ServerMode | null
  me: Me | null
  /** 'login': Server-Modus ohne gueltiges Cookie */
  status: 'unknown' | 'ok' | 'login'
  error: string | null
  busy: boolean
  /** Hinweis "Konto sichern?" auf der Startseite weggeklickt (pro Konto, localStorage) */
  secureDismissed: boolean
  load: () => Promise<void>
  login: (code: string) => Promise<boolean>
  loginEmail: (email: string, password: string) => Promise<boolean>
  /** Konto sichern (E-Mail + Passwort); liefert Fehlertext oder null */
  register: (email: string, password: string) => Promise<string | null>
  /** E-Mail/Passwort aendern; braucht das aktuelle Passwort; liefert Fehlertext oder null */
  updateAccount: (patch: { current: string; email?: string; password?: string }) => Promise<string | null>
  dismissSecure: () => void
  logout: () => Promise<void>
  markLoggedOut: () => void
}

const dismissKey = (id: number) => `magelite.secureDismissed.${id}`

function loadDismissed(id: number): boolean {
  try {
    return localStorage.getItem(dismissKey(id)) === '1'
  } catch {
    return false
  }
}

function errorText(e: unknown, unauthorized: string): string {
  if (e instanceof ApiError && e.status === 401) return unauthorized
  return e instanceof Error ? e.message : String(e)
}

export const useAuth = create<AuthStore>((set, get) => {
  setUnauthorizedHandler(() => {
    if (get().mode === 'server') set({ status: 'login', me: null })
  })
  const apply = (r: MeResponse) => set({ mode: r.mode, me: { ...r.user, session: r.session ?? null }, status: 'ok', error: null, secureDismissed: loadDismissed(r.user.id) })
  return {
    mode: null,
    me: null,
    status: 'unknown',
    error: null,
    busy: false,
    secureDismissed: false,

    load: async () => {
      try {
        apply(await api.get<MeResponse>('/api/me'))
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
        apply(await api.post<MeResponse>('/api/auth/login', { code }))
        return true
      } catch (e) {
        set({ error: errorText(e, 'Dieser Code ist nicht (mehr) gültig.') })
        return false
      } finally {
        set({ busy: false })
      }
    },

    loginEmail: async (email, password) => {
      set({ busy: true, error: null })
      try {
        apply(await api.post<MeResponse>('/api/auth/login', { email, password }))
        return true
      } catch (e) {
        set({ error: errorText(e, 'E-Mail oder Passwort stimmen nicht.') })
        return false
      } finally {
        set({ busy: false })
      }
    },

    register: async (email, password) => {
      set({ busy: true })
      try {
        apply(await api.post<MeResponse>('/api/auth/register', { email, password }))
        return null
      } catch (e) {
        return errorText(e, 'Nicht angemeldet.')
      } finally {
        set({ busy: false })
      }
    },

    updateAccount: async (patch) => {
      set({ busy: true })
      try {
        apply(await api.put<MeResponse>('/api/auth/account', patch))
        return null
      } catch (e) {
        return errorText(e, 'Das aktuelle Passwort stimmt nicht.')
      } finally {
        set({ busy: false })
      }
    },

    dismissSecure: () => {
      const me = get().me
      if (me) {
        try {
          localStorage.setItem(dismissKey(me.id), '1')
        } catch {
          /* egal */
        }
      }
      set({ secureDismissed: true })
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
