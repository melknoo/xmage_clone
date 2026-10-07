import { useEffect, useState } from 'react'
import { Button, Wordmark } from '../components/ui'
import { Icon } from '../lib/icons'

export type BootPhase = 'engine' | 'server' | 'account' | 'down'

export interface BootScreenProps {
  /** engine: lokale Engine startet · server: Server wird erreicht · account: Konto/Decks/laufendes Spiel laden · down: nicht erreichbar */
  phase: BootPhase
  mode: 'local' | 'server'
  /** App-Version (Electron), optional */
  version?: string
  /** down: "Jetzt versuchen" (Standard: Seite neu laden) */
  onRetry?: () => void
}

/** Zeitkonstante des Pseudo-Fortschritts (ms): lokal startet die Engine in Sekunden, fly braucht beim Kaltstart laenger */
const TAU = { local: 8_000, server: 40_000 }
/** Hinweiszeile erst nach dieser Wartezeit */
const HINT_AFTER_MS = 3_000

/**
 * Pseudo-Fortschritt ohne echtes Signal (nur /api/health-Polling): 1 - e^(-t/tau), nie endlos, nie 100 %.
 * Verbinden 0 -> 50 %, Konto laden 60 -> 95 %, nicht erreichbar 100 % in Karmin.
 */
function useProgress(phase: BootPhase, mode: 'local' | 'server'): { pct: number; elapsed: number } {
  const [start] = useState(() => Date.now())
  const [now, setNow] = useState(() => Date.now())
  // Beginn der aktuellen Phase (beim Phasenwechsel waehrend des Renderns nachziehen)
  const [cur, setCur] = useState(() => ({ phase, at: Date.now() }))
  if (cur.phase !== phase) setCur({ phase, at: Date.now() })
  useEffect(() => {
    if (phase === 'down') return
    const h = window.setInterval(() => setNow(Date.now()), 250)
    return () => window.clearInterval(h)
  }, [phase])
  const elapsed = Math.max(0, now - start)
  if (phase === 'down') return { pct: 100, elapsed }
  if (phase === 'account') {
    const t = cur.phase === phase ? Math.max(0, now - cur.at) : 0
    return { pct: 60 + 35 * (1 - Math.exp(-t / 1_500)), elapsed }
  }
  return { pct: 4 + 46 * (1 - Math.exp(-elapsed / TAU[mode])), elapsed }
}

/**
 * Start-/Ladebildschirm vor dem Login bzw. vor dem ersten Screen (Online-Prototyp "Laden"):
 * Wortmarke 34, 2-px-Balken 240 breit, Label Barlow 13. Electron-Splash (desktop/src/splash.html) sieht gleich aus.
 */
export function BootScreen({ phase, mode, version, onRetry }: BootScreenProps) {
  const local = mode === 'local'
  const { pct, elapsed } = useProgress(phase, mode)
  const down = phase === 'down'

  const label =
    phase === 'account' ? 'Lade Konto und Decks …' : local ? 'Engine startet – Kartendatenbank wird geladen …' : 'Verbinde mit Server …'
  const hint =
    phase === 'engine'
      ? 'Beim allerersten Start wird die Datenbank aufgebaut (ca. 1–2 Minuten).'
      : phase === 'server'
        ? 'Der Server startet gerade – das kann beim ersten Mal eine Minute dauern.'
        : null

  return (
    <div className="flex h-full flex-col items-center justify-center gap-[18px] bg-bg-1" data-testid="boot-screen" data-phase={phase}>
      <Wordmark size={34} />
      <div className="bar-track" style={{ width: 240, height: 2 }} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)}>
        <div
          className="bar-fill"
          style={{ width: `${pct}%`, background: down ? 'var(--color-attack)' : 'var(--color-ember)', transitionDuration: '300ms' }}
        />
      </div>
      {!down ? (
        <>
          <span className="font-display text-[13px] font-semibold uppercase leading-none tracking-[.12em] text-fg-3">{label}</span>
          {hint && (
            <p className="m-0 text-[12.5px] leading-[1.4] text-fg-4 transition-opacity duration-3" style={{ opacity: elapsed >= HINT_AFTER_MS ? 1 : 0 }}>
              {hint}
            </p>
          )}
        </>
      ) : (
        <>
          <div className="flex items-center gap-2 text-[13.5px] leading-[1.45] text-fg-2" role="alert">
            <Icon name={local ? 'warning' : 'disconnected'} size={16} className="text-attack" />
            {local ? 'Engine nicht erreichbar. Läuft der Java-Prozess?' : 'Server nicht erreichbar. Bitte später noch einmal versuchen.'}
          </div>
          <Button variant="secondary" size="sm" icon="rotate" onClick={onRetry ?? (() => window.location.reload())}>
            Jetzt versuchen
          </Button>
        </>
      )}
      {version && <div className="text-[12px] text-fg-4">{version}</div>}
    </div>
  )
}
