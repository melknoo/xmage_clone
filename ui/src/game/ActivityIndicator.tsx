import { useEffect, useState, type ReactNode } from 'react'
import { useGame } from '../store/game'

/** Ohne Herzschlag so lange -> Verbindung/Engine weg. */
const STALE_MS = 4000
/** Ab dieser CPU-Last (% eines Kerns) gilt die Engine als "rechnet wirklich". */
const WORKING_CPU = 10

/**
 * Statuszeile, solange kein Prompt offen ist: arbeitet die Engine gerade wirklich?
 * Modus aus dem Herzschlag (1/s) plus gemessene CPU-Last; Zahnrad dreht sich nur bei echter Rechenlast.
 * Ohne Herzschlag (aeltere Engine, Verbindungsaufbau) wird {@code fallback} gezeigt.
 */
export function ActivityLine({ fallback }: { fallback: ReactNode }) {
  const a = useGame((s) => s.activity)
  const at = useGame((s) => s.activityAt)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const iv = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(iv)
  }, [])

  if (!a) return <>{fallback}</>
  const stale = now - at > STALE_MS
  const cpu = a.cpu
  const working = !stale && cpu >= WORKING_CPU && a.mode !== 'you'

  let label: string
  let tone: 'work' | 'wait' | 'warn'
  if (stale) {
    label = 'Keine Verbindung zur Engine …'
    tone = 'warn'
  } else if (a.mode === 'stuck') {
    label = `Keine Aktivität seit ${Math.round(a.idleMs / 1000)} s – Spielverlauf prüfen oder Spiel beenden`
    tone = 'warn'
  } else if (a.mode === 'you') {
    label = 'Wartet auf dich'
    tone = 'wait'
  } else if (a.mode === 'bot') {
    label = working ? `${a.who ?? 'Bot'} rechnet …` : `Warte auf ${a.who ?? 'Bot'} …`
    tone = working ? 'work' : 'wait'
  } else if (a.mode === 'engine') {
    label = 'Engine arbeitet …'
    tone = 'work'
  } else {
    label = 'Gegner sind am Zug …'
    tone = 'wait'
  }

  const color = tone === 'work' ? 'text-arcane-400' : tone === 'warn' ? 'text-blood-400' : 'text-ink-300'
  const title = [
    'Engine-Aktivität (jede Sekunde aktualisiert).',
    cpu >= 0 ? `CPU-Last der Engine: ${cpu} % eines Kerns – über ${WORKING_CPU} % wird wirklich gerechnet.` : 'CPU-Last nicht messbar.',
    a.recovered > 0 ? `Verlorene Antworten automatisch neu zugestellt: ${a.recovered}.` : null,
  ]
    .filter(Boolean)
    .join('\n')

  return (
    <div className={`flex min-w-0 items-center gap-2 text-sm ${color}`} title={title}>
      <span className={`inline-block w-4 shrink-0 text-center leading-none ${working ? 'animate-spin' : tone === 'warn' ? '' : 'animate-pulse'}`} style={working ? { animationDuration: '1.6s' } : undefined}>
        {tone === 'warn' ? '⚠' : '⚙'}
      </span>
      <span className="min-w-0 truncate">{label}</span>
      {!stale && cpu >= 0 && a.mode !== 'you' && (
        <span className="flex shrink-0 items-center gap-1.5 text-[11px] text-ink-400">
          <span className="h-1.5 w-12 overflow-hidden rounded-full bg-ink-700">
            <span className={`block h-full rounded-full transition-[width] duration-700 ${working ? 'bg-arcane-400' : 'bg-ink-400'}`} style={{ width: `${Math.min(100, cpu / 2)}%` }} />
          </span>
          <span className="tabular-nums">CPU {cpu} %</span>
        </span>
      )}
    </div>
  )
}
