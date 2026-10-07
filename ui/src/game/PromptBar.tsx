import type { ReactNode } from 'react'
import type { GameState, RichSeg } from '../api/types'
import { Button, Chip } from '../components/ui'
import { Rich } from '../lib/mana'
import { useGame } from '../store/game'
import { useUi } from '../store/ui'
import { ActivityIndicator } from './ActivityIndicator'
import { shortName } from './format'
import type { Interaction } from './interaction'
import type { BoardLayoutState } from './layout'
import { stepIndex, stepLabel } from './PhaseBar'
import { isOpeningHandAsk, SKIP_LABEL, SKIPS, usePromptButtons, type PromptButton } from './promptActions'

export interface PromptBarProps {
  inter: Interaction
  /** promptH, compact (Kontext-Label, F4/F9 nur im vollen Layout) */
  layout: BoardLayoutState
}

type StatusTone = 'turn' | 'target' | 'outline'

interface Status {
  text: string
  tone: StatusTone
}

const CONTEXT_CLS = 'whitespace-nowrap font-display text-[13px] font-semibold uppercase leading-none tracking-[.1em] text-fg-3'

function plain(segs?: RichSeg[]): string {
  return (segs ?? []).map((s) => (s.br ? ' ' : (s.text ?? ''))).join('')
}

/** Genitiv fuer "Kotoris Zug" / "Ob Nixilis' Zug" */
function genitive(name: string): string {
  return /[sßxz]$/i.test(name) ? `${name}'` : `${name}s`
}

/** Phase bzw. Zug fuer das Kontext-Label: eigener Zug "Main 1", sonst "Kotoris Zug" */
function phaseContext(s: GameState, spectator: boolean): { left: string; mine: boolean } {
  const idx = stepIndex(s.step)
  if (idx < 0) return { left: s.turn > 0 ? `Zug ${s.turn}` : 'Vor Zug 1', mine: false }
  const active = s.players.find((p) => p.id === s.activePlayerId)
  if (active?.me && !spectator) return { left: idx >= 4 && idx <= 8 ? 'Kampf' : stepLabel(s.step), mine: true }
  return { left: active ? `${genitive(shortName(active.name))} Zug` : stepLabel(s.step), mine: false }
}

/**
 * Aktionsleiste unter der Hand: Status-Chip, Kontext (nur volles Layout), Engine-Text (eine Zeile, Ellipse, Hover-Zoom
 * auf Kartenlinks), Passen-Knoepfe, Prompt-Knoepfe (Primaer rechts). Ohne Prompt: Wartezustand (ActivityIndicator).
 * Zuschauer: Chip "Zuschauer", "Du schaust {Tisch} zu", "Zuschauen beenden" (Esc).
 */
export function PromptBar({ inter, layout }: PromptBarProps) {
  const p = inter.prompt
  const buttons = usePromptButtons(inter)
  const action = useGame((s) => s.action)
  const state = useGame((s) => s.state)
  const setHover = useGame((s) => s.setHover)
  const objects = useGame((s) => s.objects)
  const clearMarks = useGame((s) => s.clearMarks)
  const spectator = useGame((s) => s.spectator)
  const tableName = useGame((s) => s.hello?.tableName)
  const stopSpectating = useGame((s) => s.stopSpectating)
  const gameOver = useGame((s) => s.gameOver)
  const myId = useGame((s) => s.hello?.myPlayerId)
  const menuOpen = useGame((s) => s.menuOpen)
  const viewer = useGame((s) => s.viewer)
  const dialogMinimized = useUi((s) => s.dialogMinimized)
  const marks = inter.marked.size
  const attacking = (state?.combat ?? []).reduce((n, g) => n + g.attackers.length, 0)
  const stackSize = state?.stack.length ?? 0
  // laufendes F-Tasten-Passen (z.B. F9 "bis zu meinem Zug") - jederzeit abbrechbar
  const skips = state?.players.find((pl) => pl.me)?.skips
  const skipText = !spectator && skips && skips.length > 0 ? (SKIP_LABEL[skips[0]] ?? 'Passe automatisch') : null
  const full = !layout.compact

  const bar = (children: ReactNode) => (
    <div className="flex shrink-0 items-center gap-[10px] border-t border-line-2 bg-bg-3" style={{ height: layout.promptH + 1, padding: '0 12px 0 14px' }} data-testid="prompt-bar">
      {children}
    </div>
  )
  const status = (st: Status) => (
    <Chip tone={st.tone} fill={st.tone === 'target'} style={{ letterSpacing: '.1em' }}>
      {st.text}
    </Chip>
  )
  const context = (text: string | null) => (full && text ? <span className={CONTEXT_CLS}>{text}</span> : null)

  // ---- Zuschauer ----
  if (spectator) {
    const winner = gameOver?.placements.find((x) => x.place === 1)
    return bar(
      <>
        <Chip tone="outline" icon="spectate" style={{ letterSpacing: '.1em' }}>
          Zuschauer
        </Chip>
        {gameOver && context(`Zug ${gameOver.turns}`)}
        <span className="min-w-0 flex-1 truncate pl-1 text-body-l font-medium text-fg-1">
          {gameOver ? (winner ? `${winner.name} gewinnt` : 'Spiel beendet') : tableName ? `Du schaust ${tableName} zu` : 'Du schaust zu'}
        </span>
        <Button variant="secondary" kbd="Esc" game className="shrink-0" title="Zuschauen beenden und zurück (Esc)" onClick={stopSpectating}>
          Zuschauen beenden
        </Button>
      </>,
    )
  }

  // ---- Spielende ----
  if (gameOver) {
    const mine = gameOver.placements.find((x) => x.playerId === myId)
    return bar(
      <>
        {status({ text: 'Spielende', tone: 'outline' })}
        {context(`Zug ${gameOver.turns}`)}
        <span className="min-w-0 flex-1 truncate pl-1 text-body-l font-medium text-fg-1">{mine ? (mine.place === 1 ? 'Du hast gewonnen' : `Platz ${mine.place}`) : 'Spiel beendet'}</span>
      </>,
    )
  }

  const ctx = state ? phaseContext(state, spectator) : { left: '', mine: false }
  const stopBtn = skipText ? (
    <Button variant="secondary" kbd="F3" game className="shrink-0" title="Automatisches Passen beenden – du bekommst wieder Priorität (z. B. in der Endphase eines Gegners)" onClick={() => action('PASS_PRIORITY_CANCEL_ALL_ACTIONS')}>
      Stopp
    </Button>
  ) : null

  // ---- kein Prompt: Wartezustand bzw. automatisches Passen ----
  if (!p) {
    if (menuOpen) {
      return bar(
        <>
          {status({ text: 'Pause', tone: 'outline' })}
          <span className="min-w-0 flex-1 truncate pl-1 text-body-l font-medium text-fg-2">Das Spiel läuft im Hintergrund weiter</span>
          {stopBtn}
        </>,
      )
    }
    if (skipText) {
      return bar(
        <>
          <Chip tone="outline" icon="autoPass" style={{ letterSpacing: '.1em' }}>
            Passe automatisch
          </Chip>
          {context(ctx.left)}
          <span className="min-w-0 flex-1 truncate pl-1 text-body-l font-medium text-fg-2" title="F3 oder „Stopp“ hält wieder an">
            {skipText}
          </span>
          {stopBtn}
        </>,
      )
    }
    return bar(<ActivityIndicator context={full ? ctx.left || null : null} />)
  }

  // ---- offener Prompt ----
  const step = state?.step
  const dialog = inter.mode === 'dialog' || inter.needsCardModal || isOpeningHandAsk(p, step) || dialogMinimized || !!viewer
  const sourceName = p.sourceId ? objects.get(p.sourceId)?.name : undefined
  let st: Status
  let detail: string | null = null
  if (menuOpen) st = { text: 'Pause', tone: 'outline' }
  else if (p.kind === 'ASK' && p.mulligan) st = { text: 'Starthand', tone: 'turn' }
  else if (dialog) st = { text: 'Dialog offen', tone: 'outline' }
  else if (inter.mode === 'attack') {
    st = marks > 0 ? { text: 'Verteidiger wählen', tone: 'target' } : attacking > 0 ? { text: 'Angriff erklärt', tone: 'turn' } : { text: 'Angreifer wählen', tone: 'turn' }
    detail = 'Angreifer'
  } else if (inter.mode === 'block') {
    st = marks > 0 ? { text: 'Angreifer wählen', tone: 'target' } : { text: 'Blocker wählen', tone: 'turn' }
    detail = 'Blocker'
  } else if (inter.mode === 'target') {
    st = { text: p.defenderPick ? 'Verteidiger wählen' : 'Ziel wählen', tone: 'target' }
    detail = sourceName ?? null
  } else if (inter.mode === 'mana') {
    st = { text: 'Mana zahlen', tone: 'turn' }
    detail = sourceName ?? null
  } else if (inter.mode === 'priority') {
    st = { text: 'Wartet auf dich', tone: 'turn' }
    detail = ctx.mine ? 'Priorität' : stepLabel(step) || null
  } else if (p.kind === 'ASK') {
    st = { text: 'Frage', tone: 'turn' }
  } else st = { text: 'Wartet auf dich', tone: 'turn' }
  if (!detail && !ctx.mine && (inter.mode === 'target' || inter.mode === 'mana')) detail = stepLabel(step) || null
  const ctxText = p.kind === 'ASK' && p.mulligan ? 'Vor Zug 1' : [ctx.left, detail].filter(Boolean).join(' · ')

  // Engine-Text; Markierungen ersetzen ihn (wie bisher), Zweittext haengt grau an, Shift-Hinweise im Tooltip
  const combat = inter.mode === 'attack' || inter.mode === 'block'
  const hint = combat ? `Shift+Klick: mehrere markieren${inter.mode === 'attack' && attacking > 0 ? ' · Klick auf einen Angreifer nimmt ihn zurück' : ''}` : null
  const title = [plain(p.message) || p.messageText, plain(p.secondMessage), hint].filter(Boolean).join('\n')

  // Passen-Knoepfe nur bei Prioritaet; F10 erst ab 2 Stapelobjekten; kompakt nur F5
  const priority = p.kind === 'SELECT' && p.mode === 'priority'
  const skipBtns = priority ? SKIPS.filter((s) => (s.action === 'PASS_PRIORITY_UNTIL_STACK_RESOLVED' ? full && stackSize >= 2 : full || s.hotkey === 'F5')) : []
  const primary = buttons.filter((b) => b.kind === 'primary')
  const others = buttons.filter((b) => b.kind !== 'primary')

  return bar(
    <>
      {status(st)}
      {context(ctxText)}
      <span className="min-w-0 flex-1 truncate pl-1 text-body-l font-medium text-fg-1" title={title || undefined}>
        {marks > 0 ? (
          <>
            {marks} markiert – {inter.mode === 'attack' ? 'Gegner oder Planeswalker anklicken: alle greifen ihn an' : 'Angreifer anklicken: alle blocken ihn'}
          </>
        ) : (
          <Rich segs={p.message} onObject={(id) => setHover(objects.get(id) ?? null)} />
        )}
        {marks === 0 && p.secondMessage && (
          <span className="text-[13px] text-fg-3">
            {' · '}
            <Rich segs={p.secondMessage} onObject={(id) => setHover(objects.get(id) ?? null)} />
          </span>
        )}
      </span>
      {stopBtn}
      {skipBtns.map((s) => (
        <Button key={s.action} variant="secondary" kbd={s.hotkey} game className="shrink-0" title={`${s.title} (${s.hotkey})`} onClick={() => action(s.action)}>
          {s.label}
        </Button>
      ))}
      {marks > 0 && (
        <Button variant="ghost" kbd="Esc" game className="shrink-0" title="Markierung aufheben (Esc)" onClick={clearMarks}>
          Markierung aufheben
        </Button>
      )}
      {[...others, ...primary].map((b) => (
        <PromptBtn key={`${p.id}:${b.label}`} b={b} />
      ))}
    </>,
  )
}

function PromptBtn({ b }: { b: PromptButton }) {
  return (
    <Button
      variant={b.kind}
      kbd={b.hotkey}
      confirm={b.confirm}
      testId={b.testId}
      title={b.title}
      disabled={b.disabled}
      game
      className="shrink-0"
      onClick={b.run}
    >
      {b.label}
    </Button>
  )
}
