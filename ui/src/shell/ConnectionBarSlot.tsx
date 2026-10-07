import { useEffect, useState } from 'react'
import { endpoint } from '../api/client'
import { ConnectionBar } from '../components/ui'
import { useConn } from '../store/conn'

/**
 * Platz fuer die Verbindungsleiste (36 px) ueber Navigation und Inhalt; nur im Shell-Zweig, nie im Spiel.
 * Zeigt die Leiste, solange store/conn offline meldet, mit Countdown bis zum naechsten Versuch.
 */
export function ConnectionBarSlot() {
  const offline = useConn((s) => s.offline)
  const retryAt = useConn((s) => s.retryAt)
  const retryNow = useConn((s) => s.retryNow)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!offline) return
    setNow(Date.now())
    const iv = window.setInterval(() => setNow(Date.now()), 250)
    return () => window.clearInterval(iv)
  }, [offline])
  if (!offline) return null
  const target = endpoint.mode === 'local' ? 'zur Engine' : 'zum Server'
  const secs = retryAt ? Math.max(0, Math.ceil((retryAt - now) / 1000)) : 0
  const text = retryAt && secs > 0 ? `Keine Verbindung ${target}. Neuer Versuch in ${secs} s.` : `Keine Verbindung ${target}. Verbinde …`
  return <ConnectionBar retryInSec={secs} onRetry={retryNow} text={text} />
}
