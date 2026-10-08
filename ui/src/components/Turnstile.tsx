import { useEffect, useRef } from 'react'

/** Cloudflare Turnstile (Captcha) fuer Registrierung, "Passwort vergessen" und "Mail erneut senden". */

interface TurnstileApi {
  render: (el: HTMLElement, opts: Record<string, unknown>) => string
  remove: (id: string) => void
}

declare global {
  interface Window {
    turnstile?: TurnstileApi
  }
}

const SCRIPT = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'
let loading: Promise<TurnstileApi> | null = null

function loadTurnstile(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile)
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const s = document.createElement('script')
      s.src = SCRIPT
      s.async = true
      s.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error('turnstile')))
      s.onerror = () => {
        loading = null
        reject(new Error('turnstile'))
      }
      document.head.appendChild(s)
    })
  }
  return loading
}

/**
 * Widget; meldet das Token ueber onToken ('' bei Ablauf/Fehler). Tokens gelten nur einmal: nach einem Versuch
 * per neuem `key` neu einhaengen. Ohne siteKey (Dev, Server ohne Captcha) wird nichts gezeigt.
 */
export function Turnstile({ siteKey, onToken }: { siteKey: string | null; onToken: (token: string) => void }) {
  const box = useRef<HTMLDivElement>(null)
  const cb = useRef(onToken)
  cb.current = onToken

  useEffect(() => {
    if (!siteKey || !box.current) return
    let id: string | null = null
    let alive = true
    loadTurnstile()
      .then((ts) => {
        if (!alive || !box.current) return
        id = ts.render(box.current, {
          sitekey: siteKey,
          theme: 'dark',
          language: 'de',
          callback: (t: string) => cb.current(t),
          'expired-callback': () => cb.current(''),
          'error-callback': () => cb.current(''),
        })
      })
      .catch(() => cb.current(''))
    return () => {
      alive = false
      if (id && window.turnstile) window.turnstile.remove(id)
    }
  }, [siteKey])

  if (!siteKey) return null
  return <div ref={box} className="min-h-[65px]" data-testid="captcha" />
}
