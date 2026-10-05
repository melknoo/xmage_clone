// Verbindung zur Engine.
// Lokal (Electron oder ?port=): http://127.0.0.1:<port> mit Token. Sonst (Server-Modus, Vite-Proxy): dieselbe
// Origin ohne Token; die Anmeldung laeuft ueber ein Cookie.

declare global {
  interface Window {
    magelite?: { port: number; token: string | null; fetchText?: (url: string) => Promise<string> }
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

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(apiUrl(path), {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) {
    if (res.status === 401 && path !== '/api/auth/login' && path !== '/api/me') onUnauthorized?.()
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
