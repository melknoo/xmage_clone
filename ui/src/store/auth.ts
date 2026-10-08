import { create } from 'zustand'
import { api, ApiError, setBudgetHandler, setUnauthorizedHandler } from '../api/client'

export interface Me {
  id: number
  name: string
  admin: boolean
  /** gesichertes Konto: E-Mail + Passwort gesetzt; sonst "Gast" (nur Einladungscode) */
  email: string | null
  hasPassword: boolean
  /** friend: eingeladen/Owner · public: selbst registriert (keine Server-Spiele, faellt unters Monatsbudget) */
  tier?: 'friend' | 'public'
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
  /** Anmeldeweg (verify/reset: per Link aus der Mail) */
  via: 'code' | 'password' | 'verify' | 'reset'
}

/** GET /api/auth/options (oeffentlich): Stand der Selbstregistrierung */
export interface AuthOptions {
  signup: 'open' | 'closed' | 'full' | 'daily' | 'budget'
  /** Cloudflare Turnstile; null = kein Captcha (Dev) */
  turnstileSiteKey: string | null
  /** "Passwort vergessen" per Mail moeglich */
  forgot: boolean
}

export type ServerMode = 'local' | 'server'

export interface MeResponse {
  mode: ServerMode
  user: Me
  /** nur Server-Modus mit Session-Cookie; sonst null */
  session?: SessionInfo | null
  /** Server-Modus: meine MageLite-App ist angebunden (Host-Link) -> Tische auf dem eigenen Rechner moeglich */
  hostLink?: boolean
  /** Server-Modus mit Monatsbudget: limited = mein (oeffentliches) Konto ist bis resetsAt gesperrt */
  budget?: { limited: boolean; resetsAt: number }
}

interface AuthStore {
  /** null, bis /api/me einmal geantwortet hat */
  mode: ServerMode | null
  me: Me | null
  /** Host-Link aktiv (Stand des letzten /api/me) */
  hostLink: boolean
  /** 'login': Server-Modus ohne gueltiges Cookie */
  status: 'unknown' | 'ok' | 'login'
  error: string | null
  busy: boolean
  /** Hinweis "Konto sichern?" auf der Startseite weggeklickt (pro Konto, localStorage) */
  secureDismissed: boolean
  /** oeffentliches Konto, Server-Kontingent fuer diesen Monat aufgebraucht */
  budgetLimited: boolean
  budgetResetsAt: number | null
  /** Login scheiterte an unbestaetigter E-Mail (fuer "Mail erneut senden") */
  unverifiedEmail: string | null
  /** Reset-Token aus dem Mail-Link (#reset=…): Login-Screen zeigt "Neues Passwort" */
  resetToken: string | null
  options: AuthOptions | null
  load: () => Promise<void>
  loadOptions: () => Promise<void>
  /** Registrierung; liefert Fehlertext oder null (dann ist die Bestaetigungsmail unterwegs) */
  signup: (p: { name: string; email: string; password: string; captcha: string }) => Promise<string | null>
  /** Bestaetigungslink einloesen und anmelden; Fehlertext oder null */
  verify: (token: string) => Promise<string | null>
  resend: (email: string, captcha: string) => Promise<string | null>
  forgot: (email: string, captcha: string) => Promise<string | null>
  /** neues Passwort per Reset-Link; danach angemeldet */
  resetPassword: (password: string) => Promise<string | null>
  setResetToken: (token: string | null) => void
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

function isUnverified(e: unknown): boolean {
  return e instanceof ApiError && e.status === 403 && (e.data as { unverified?: boolean } | null)?.unverified === true
}

export const useAuth = create<AuthStore>((set, get) => {
  setUnauthorizedHandler(() => {
    if (get().mode === 'server') set({ status: 'login', me: null })
  })
  setBudgetHandler(() => {
    if (get().me?.tier === 'public') set({ budgetLimited: true })
  })
  const apply = (r: MeResponse) =>
    set({
      mode: r.mode,
      me: { ...r.user, session: r.session ?? null },
      hostLink: r.hostLink === true,
      status: 'ok',
      error: null,
      unverifiedEmail: null,
      secureDismissed: loadDismissed(r.user.id),
      budgetLimited: r.budget?.limited === true,
      budgetResetsAt: r.budget?.resetsAt ?? null,
    })
  /** POST ohne MeResponse; Fehlertext oder null */
  const call = async (path: string, body: unknown): Promise<string | null> => {
    set({ busy: true })
    try {
      await api.post(path, body)
      return null
    } catch (e) {
      return errorText(e, 'Nicht angemeldet.')
    } finally {
      set({ busy: false })
    }
  }
  return {
    mode: null,
    me: null,
    hostLink: false,
    status: 'unknown',
    error: null,
    busy: false,
    secureDismissed: false,
    budgetLimited: false,
    budgetResetsAt: null,
    unverifiedEmail: null,
    resetToken: null,
    options: null,

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
      set({ busy: true, error: null, unverifiedEmail: null })
      try {
        apply(await api.post<MeResponse>('/api/auth/login', { email, password }))
        return true
      } catch (e) {
        set({ error: errorText(e, 'E-Mail oder Passwort stimmen nicht.'), unverifiedEmail: isUnverified(e) ? email : null })
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

    loadOptions: async () => {
      try {
        set({ options: await api.get<AuthOptions>('/api/auth/options') })
      } catch {
        set({ options: { signup: 'closed', turnstileSiteKey: null, forgot: false } })
      }
    },

    signup: (p) => call('/api/auth/signup', p),

    verify: async (token) => {
      const err = await call('/api/auth/verify', { token })
      if (err) return err
      await get().load()
      return null
    },

    resend: (email, captcha) => call('/api/auth/resend', { email, captcha }),

    forgot: (email, captcha) => call('/api/auth/forgot', { email, captcha }),

    resetPassword: async (password) => {
      const token = get().resetToken
      if (!token) return 'Der Link ist ungültig oder abgelaufen'
      const err = await call('/api/auth/reset', { token, password })
      if (err) return err
      set({ resetToken: null })
      await get().load()
      return null
    },

    setResetToken: (token) => set({ resetToken: token }),

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
  return takeHashParam('invite')
}

/** Parameter aus dem Link-Hash (#invite=…, #verify=…, #reset=…) lesen und aus der URL entfernen. */
export function takeHashParam(name: 'invite' | 'verify' | 'reset'): string | null {
  const m = window.location.hash.match(new RegExp(`${name}=([^&]+)`))
  if (!m) return null
  try {
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
  } catch {
    /* egal */
  }
  return decodeURIComponent(m[1])
}
