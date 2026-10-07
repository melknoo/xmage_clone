import { useEffect, useState } from 'react'
import { Button, Chip } from '../components/ui'
import type { IconName } from '../lib/icons'
import { useGame } from '../store/game'
import { shortName } from './format'

/** Ohne Herzschlag so lange -> Verbindung/Engine weg. */
const STALE_MS = 4000
/** Ab dieser CPU-Last (% eines Kerns) gilt die Engine als "rechnet wirklich". */
const WORKING_CPU = 10

export interface ActivityInfo {
  /** Status-Chip (Kontur) */
  chip: string
  chipIcon?: IconName
  /** Zeile in der Aktionsleiste */
  label: string
  tone: 'work' | 'wait' | 'warn'
  /** Engine rechnet wirklich (CPU ueber der Schwelle) */
  working: boolean
  /** CPU-Last in % eines Kerns; null = nicht anzeigen */
  cpu: number | null
  title?: string
}

/**
 * Was passiert gerade, solange kein Prompt offen ist? Modus aus dem Herzschlag der Engine (1/s) plus gemessene CPU-Last.
 * Ohne Herzschlag (Verbindungsaufbau, alte Engine) gilt status thinking/waitingFor.
 */
export function useActivity(): ActivityInfo {
  const a = useGame((s) => s.activity)
  const at = useGame((s) => s.activityAt)
  const thinking = useGame((s) => s.thinking)
  const waitingFor = useGame((s) => s.waitingFor)
  // Wartet das Spiel auf einen getrennten Mitspieler? (Name -> Sitz -> Verbindungszustand)
  const disconnectedMs = useGame((s) => {
    if (s.activity?.mode !== 'human' || !s.activity.who) return 0
    const seat = s.hello?.seats.find((x) => x.name === s.activity?.who)
    const c = seat ? s.seatConn[seat.playerId] : undefined
    return c && !c.connected ? c.disconnectedMs : 0
  })
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const iv = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(iv)
  }, [])

  if (!a) {
    return thinking || waitingFor
      ? { chip: 'Warten', label: `Warte auf ${waitingFor ?? 'Bot'} …`, tone: 'wait', working: false, cpu: null }
      : { chip: 'Warten', label: 'Gegner sind am Zug …', tone: 'wait', working: false, cpu: null }
  }
  const stale = now - at > STALE_MS
  const cpu = a.cpu
  const working = !stale && cpu >= WORKING_CPU && a.mode !== 'you'
  const who = a.who ? shortName(a.who) || a.who : null

  let info: Omit<ActivityInfo, 'working' | 'cpu' | 'title'>
  if (stale) {
    info = { chip: 'Getrennt', chipIcon: 'disconnected', label: 'Keine Verbindung zur Engine …', tone: 'warn' }
  } else if (a.mode === 'stuck') {
    info = { chip: 'Keine Aktivität', chipIcon: 'warning', label: `Keine Aktivität seit ${Math.round(a.idleMs / 1000)} s – Spielverlauf prüfen oder Spiel beenden`, tone: 'warn' }
  } else if (a.mode === 'you') {
    info = { chip: 'Warten', label: 'Wartet auf dich', tone: 'wait' }
  } else if (a.mode === 'bot') {
    info = working
      ? { chip: 'Denkt', chipIcon: 'thinking', label: `${who ?? 'Bot'} rechnet …`, tone: 'work' }
      : { chip: 'Warten', label: `Warte auf ${who ?? 'Bot'} …`, tone: 'wait' }
  } else if (a.mode === 'human') {
    info =
      disconnectedMs > 0
        ? { chip: 'Getrennt', chipIcon: 'disconnected', label: `${who ?? 'Mitspieler'} ist dran – getrennt seit ${Math.round(disconnectedMs / 1000)} s`, tone: 'warn' }
        : { chip: 'Warten', chipIcon: 'human', label: `${who ?? 'Mitspieler'} ist dran …`, tone: 'wait' }
  } else if (a.mode === 'engine') {
    info = { chip: 'Denkt', chipIcon: 'thinking', label: 'Engine arbeitet …', tone: 'work' }
  } else {
    info = { chip: 'Warten', label: 'Gegner sind am Zug …', tone: 'wait' }
  }

  const title = [
    'Engine-Aktivität (jede Sekunde aktualisiert).',
    cpu >= 0 ? `CPU-Last der Engine: ${cpu} % eines Kerns – über ${WORKING_CPU} % wird wirklich gerechnet.` : 'CPU-Last nicht messbar.',
    a.recovered > 0 ? `Verlorene Antworten automatisch neu zugestellt: ${a.recovered}.` : null,
  ]
    .filter(Boolean)
    .join('\n')

  return { ...info, working, cpu: !stale && cpu >= 0 && a.mode !== 'you' ? cpu : null, title }
}

/**
 * Wartezustand der Aktionsleiste (kein Prompt offen): Kontur-Chip, optional Kontext, Aktivitaetszeile,
 * CPU-Balken 2 px und ein Platzhalter an der Stelle des Primaer-Buttons ("Bot rechnet").
 * Rendert die Kinder der Leiste direkt (Fragment), die Leiste selbst liegt in PromptBar.
 */
export function ActivityIndicator({ context }: { context: string | null }) {
  const info = useActivity()
  return (
    <>
      <Chip tone="outline" icon={info.chipIcon} style={{ letterSpacing: '.1em' }}>
        {info.chip}
      </Chip>
      {context && <span className="whitespace-nowrap font-display text-[13px] font-semibold uppercase leading-none tracking-[.1em] text-fg-3">{context}</span>}
      <span className="min-w-0 flex-1 truncate pl-1 text-body-l font-medium" style={{ color: info.tone === 'warn' ? 'var(--color-attack)' : 'var(--color-fg-2)' }} title={info.title}>
        {info.label}
      </span>
      {info.cpu !== null && (
        <span className="flex shrink-0 items-center gap-2" title={info.title}>
          <span className="bar-track h-[2px] w-12" aria-hidden>
            <span className="bar-fill bg-fg-2" style={{ width: `${Math.min(100, info.cpu / 2)}%` }} />
          </span>
          <span className="font-mono text-kbd font-medium tabular-nums text-fg-4">CPU {info.cpu} %</span>
        </span>
      )}
      <Button variant="wait" game icon={info.tone === 'work' ? 'thinking' : undefined} className="shrink-0">
        {info.tone === 'work' ? 'Bot rechnet' : 'Warten'}
      </Button>
    </>
  )
}
