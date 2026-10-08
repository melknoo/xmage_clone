// Verbindung zur Engine.
// Lokal (Electron oder ?port=): http://127.0.0.1:<port> mit Token. Sonst (Server-Modus, Vite-Proxy): dieselbe
// Origin ohne Token; die Anmeldung laeuft ueber ein Cookie.

declare global {
  interface Window {
    magelite?: { port: number; token: string | null; fetchText?: (url: string) => Promise<string>; openOnline?: () => void; serverUrl?: string }
    /** Electron-Fenster zeigt eine fremde Seite (den Online-Server): zurueck zur lokalen App */
    mageliteDesktop?: { version: string; openLocal: () => void }
  }
}

export type EndpointMode = 'local' | 'origin'

function resolveEndpoint(): { base: string; token: string | null; mode: EndpointMode } {
  const params = new URLSearchParams(window.location.search)
  const injected = window.magelite
  if (injected || params.has('port') || params.has('token')) {
    const port = injected?.port ?? Number(params.get('port') ?? 7317)
    const token = injected?.token ?? params.get('token')
    return { base: `http://127.0.0.1:${port}`, token, mode: 'local' }
  }
  return { base: window.location.origin, token: null, mode: 'origin' }
}

export const endpoint = resolveEndpoint()

export function apiUrl(path: string): string {
  const sep = path.includes('?') ? '&' : '?'
  return endpoint.token ? `${endpoint.base}${path}${sep}token=${endpoint.token}` : `${endpoint.base}${path}`
}

export function wsUrl(path: string): string {
  return apiUrl(path).replace(/^http/, 'ws')
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status = 0,
    public readonly data: unknown = null,
  ) {
    super(message)
  }
}

let onUnauthorized: (() => void) | null = null

/** Wird bei jeder 401-Antwort aufgerufen (Server-Modus: Cookie ungueltig -> Login zeigen). */
export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn
}

let onBudget: (() => void) | null = null

/** Wird bei 503 mit {budget: true} aufgerufen (oeffentliches Konto, Monatsbudget des Servers erschoepft). */
export function setBudgetHandler(fn: (() => void) | null) {
  onBudget = fn
}

let onConnection: ((ok: boolean) => void) | null = null

/**
 * Verbindungs-Beobachter (store/conn.ts): false bei Netzfehler (fetch-TypeError) oder Proxy-Fehler (502/503/504,
 * HTML-Fehlerseite ab 500), true bei jeder anderen Antwort. Als Listener statt Import, damit kein Import-Zyklus entsteht.
 */
export function setConnectionListener(fn: ((ok: boolean) => void) | null) {
  onConnection = fn
}

function reportConnection(ok: boolean) {
  try {
    onConnection?.(ok)
  } catch {
    /* Beobachter darf Anfragen nie stoeren */
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response
  try {
    res = await fetch(apiUrl(path), {
      method,
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
  } catch (e) {
    // TypeError: Netz weg, Server nicht erreichbar (Abbruch per AbortError zaehlt nicht)
    if (e instanceof TypeError) reportConnection(false)
    throw new ApiError('Keine Verbindung zum Server', 0)
  }
  const text = await res.text()
  let data: any = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    // z.B. HTML-Fehlerseite des fly-Proxys waehrend eines Neustarts
    reportConnection(!(res.status >= 500))
    throw new ApiError(res.ok ? 'Unerwartete Antwort vom Server' : `Server nicht erreichbar (${res.status}) – bitte gleich nochmal versuchen`, res.status)
  }
  // 503 mit budget = gewollte Sperre, kein Verbindungsproblem
  const budget = res.status === 503 && data?.budget === true
  reportConnection(!(res.status === 502 || (res.status === 503 && !budget) || res.status === 504))
  if (!res.ok) {
    if (res.status === 401 && path !== '/api/auth/login' && path !== '/api/me') onUnauthorized?.()
    if (budget) onBudget?.()
    throw new ApiError(data?.error ?? `${res.status} ${res.statusText}`, res.status, data)
  }
  return data as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  del: <T>(path: string) => request<T>('DELETE', path),
}

/** Kartenbild ueber den Engine-Proxy (Scryfall + lokaler Cache). */
export function cardImageUrl(card: { set?: string; num?: string; token?: boolean; image?: string; imageNum?: number; name?: string }, opts?: { back?: boolean; size?: 'normal' | 'large' | 'art_crop' | 'small' }): string | null {
  const size = opts?.size ?? 'normal'
  if (card.token) {
    const q = new URLSearchParams({ name: card.image || card.name || '', set: card.set ?? '', n: String(card.imageNum ?? 0), size })
    return apiUrl(`/img/token?${q.toString()}`)
  }
  if (!card.set || !card.num) {
    return card.name ? apiUrl(`/img/named?${new URLSearchParams({ name: card.name, size }).toString()}`) : null
  }
  const face = opts?.back ? '&face=back' : ''
  const name = card.name ? `&name=${encodeURIComponent(card.name)}` : ''
  return apiUrl(`/img/card/${encodeURIComponent(card.set)}/${encodeURIComponent(card.num)}?size=${size}${face}${name}`)
}
