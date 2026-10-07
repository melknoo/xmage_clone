import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Placement, PlayerState, Reward } from '../api/types'
import { BoardModal } from '../components/BoardModal'
import { Avatar, Button, Chip, ProgressBar, XpRing, useCountUp } from '../components/ui'
import { clock } from '../lib/format'
import { Icon } from '../lib/icons'
import { enter } from '../lib/motion'
import { useGame } from '../store/game'
import { useNav } from '../store/nav'
import { useTable } from '../store/table'
import { commanderArt, shortName, signed } from './format'
import { useBoardLayout } from './layout'

const COUNT_DELAY_MS = 500
const COUNT_MS = 1200

const reducedMotion = () => typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

/** Ring-Zustand zum Zaehlerstand: vor der Schwelle altes Level, danach neues (Wrap bei Level-up). */
function ringAt(r: Reward, shown: number) {
  const gained = Math.max(0, r.xpGained)
  const needBefore = Math.max(1, r.xpForNextBefore ?? r.xpForNext)
  const before =
    r.xpIntoLevelBefore ?? (r.levelUp ? Math.max(0, Math.min(needBefore, needBefore - (gained - r.xpIntoLevel))) : Math.max(0, r.xpIntoLevel - gained))
  const final = { level: r.level, progress: r.xpForNext > 0 ? r.xpIntoLevel / r.xpForNext : 1, reached: true }
  if (shown >= gained) return final
  if (!r.levelUp) return { level: r.level, progress: Math.min(1, (before + shown) / needBefore), reached: false }
  const toLevel = needBefore - before
  if (shown < toLevel) return { level: r.levelBefore, progress: (before + shown) / needBefore, reached: false }
  return { level: r.level, progress: r.xpForNext > 0 ? Math.min(r.xpIntoLevel, shown - toLevel) / r.xpForNext : 1, reached: true }
}

function placeMeta(p: Placement, winnerId?: string) {
  const base = p.place === 1 && p.playerId === winnerId ? 'Sieger' : p.eliminatedTurn ? `raus in Zug ${p.eliminatedTurn}` : `${p.life} Leben`
  return p.mulligans > 0 ? `${base} · ${p.mulligans}× Mulligan` : base
}

function placeNumColor(place: number, isMe: boolean) {
  if (isMe) return 'var(--color-ember)'
  if (place <= 2) return 'var(--color-fg-2)'
  return 'var(--color-fg-3)'
}

function XpBox({ r, instant }: { r: Reward; instant: boolean }) {
  const shown = Math.round(useCountUp(Math.max(0, r.xpGained), { delay: COUNT_DELAY_MS, duration: COUNT_MS, enabled: !instant }))
  const ring = ringAt(r, shown)
  const up = r.levelUp && ring.reached
  const nt = r.nextTitle
  return (
    <div className="flex items-center gap-5 rounded-sm bg-bg-1 p-4" style={{ boxShadow: 'inset 0 0 0 1px var(--color-line-2)' }} data-testid="gameover-xp">
      <XpRing size={96} level={ring.level} progress={ring.progress} levelUp={up} transition={false} />
      <div className="flex min-w-0 flex-1 flex-col gap-[9px]">
        <div className="flex items-baseline justify-between gap-3">
          <span className="label">Erfahrung</span>
          <span className="num text-target" style={{ fontSize: 26, lineHeight: 1 }}>
            {r.xpGained === 0 ? '–' : r.xpGained < 0 ? `−${-r.xpGained} XP` : `+${shown} XP`}
          </span>
        </div>
        <ProgressBar value={ring.progress * 1000} max={1000} height={4} tone="target" />
        {r.breakdown.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {r.breakdown.map((b) => (
              <Chip key={b.source} tone="neutral" size="sm" style={{ letterSpacing: '.04em' }}>
                {b.label} {signed(b.amount)}
              </Chip>
            ))}
          </div>
        )}
        {up && (
          <motion.div {...enter} className="flex flex-wrap items-center gap-2.5">
            <Chip tone="target" fill icon="levelUp" style={{ fontSize: 14, letterSpacing: '.08em' }}>
              Level up
            </Chip>
            <span className="text-[13px] text-fg-2">
              Level {r.level} erreicht · {nt ? `nächster Titel ${nt.title} ab Level ${nt.level}` : r.title}
            </span>
          </motion.div>
        )}
      </div>
    </div>
  )
}

function MasteryBox({ r, art }: { r: Reward; art: string | null }) {
  const after = r.masteryLevel ?? 0
  const before = r.masteryLevelBefore ?? after
  const up = after > before
  return (
    <motion.div
      {...enter}
      className="flex items-center gap-3 rounded-sm"
      style={{ padding: '12px 16px', boxShadow: 'inset 0 0 0 1px color-mix(in oklab, var(--color-target) 40%, transparent)' }}
      data-testid="gameover-mastery"
    >
      {art && <img src={art} alt="" draggable={false} className="h-[34px] w-[52px] flex-none rounded-xs bg-bg-4 object-cover" />}
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="label">Deck-Meisterschaft</span>
        <span className="truncate text-[14px] font-semibold text-fg-1">{r.deckName}</span>
      </div>
      {up ? (
        <>
          <span className="num flex items-center gap-1.5 text-fg-4" style={{ fontSize: 22, lineHeight: 1 }}>
            {before}
            <Icon name="chevronRight" size={14} />
          </span>
          <Chip tone="target" fill icon="mastery" style={{ fontSize: 18 }}>
            Stufe {after}
          </Chip>
        </>
      ) : (
        <>
          {!!r.masteryGained && <span className="text-body-s text-fg-3">+{r.masteryGained} XP</span>}
          <Chip tone="target" icon="mastery" style={{ fontSize: 18 }}>
            Stufe {after}
          </Chip>
        </>
      )}
    </motion.div>
  )
}

export function GameOverOverlay() {
  const over = useGame((s) => s.gameOver)!
  const state = useGame((s) => s.state)
  const hello = useGame((s) => s.hello)
  const spectator = useGame((s) => s.spectator)
  const connect = useGame((s) => s.connect)
  const reset = useGame((s) => s.reset)
  const stopSpectating = useGame((s) => s.stopSpectating)
  const go = useNav((s) => s.go)
  const lastSetup = useNav((s) => s.lastSetup)
  const tableId = useTable((s) => s.tableId)
  const layout = useBoardLayout()
  const [hidden, setHidden] = useState(false)
  const [busy, setBusy] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)

  const myId = spectator ? undefined : hello?.myPlayerId
  const human = spectator ? undefined : (over.placements.find((p) => p.playerId === myId) ?? over.placements.find((p) => p.human))
  const won = !!human && human.place === 1 && over.winnerId === human.playerId
  const winner = over.placements.find((p) => p.playerId === over.winnerId)
  const r = spectator ? undefined : over.reward
  const xpTarget = r ? Math.max(0, r.xpGained) : 0
  // Meisterschaft erst nach dem Hochzaehlen (gleiche Zeitachse wie XpBox)
  const [countDone, setCountDone] = useState(() => reducedMotion())
  useEffect(() => {
    if (!r || reducedMotion()) return
    const t = window.setTimeout(() => setCountDone(true), xpTarget > 0 ? COUNT_DELAY_MS + COUNT_MS : 0)
    return () => window.clearTimeout(t)
  }, [r, xpTarget])

  const players = state?.players ?? []
  const playerOf = (id: string): PlayerState | undefined => players.find((p) => p.id === id)
  const meState = human ? playerOf(human.playerId) : undefined
  const myArt = meState ? commanderArt(meState) : null
  const deckName = r?.deckName ?? hello?.seats.find((s) => s.playerId === human?.playerId)?.deckName

  const again = async () => {
    if (!lastSetup || busy) return
    setBusy(true)
    setStartError(null)
    try {
      const res = await api.post<{ gameId: string }>('/api/games', lastSetup)
      connect(res.gameId)
    } catch (e) {
      // z. B. 409 "Gerade spielt ..." (online nur ein Spiel gleichzeitig)
      setStartError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }
  const toTable = () => {
    reset()
    go('table')
  }
  const toHome = () => {
    reset()
    go('home')
  }

  let primary: { label: string; run: () => void; disabled?: boolean }
  if (spectator) primary = { label: 'Zur Lobby', run: stopSpectating }
  else if (tableId) primary = { label: 'Zurück zum Tisch', run: toTable }
  else primary = { label: busy ? 'Starte …' : 'Nochmal', run: again, disabled: !lastSetup || busy }

  const display = human ? `Platz ${human.place}` : winner ? `${shortName(winner.name)} gewinnt` : 'Spielende'
  const meta = [`${over.turns} Züge`, `${clock(over.durationMs)} min`, deckName].filter(Boolean).join(' · ')

  return (
    <BoardModal
      label="Spielende"
      title={won ? 'Sieg' : over.error ? 'Spiel abgebrochen' : 'Partie beendet'}
      width={layout.modal.over}
      closable={false}
      minimized={hidden}
      onMinimizedChange={setHidden}
      minimizedLabel="Ergebnis anzeigen"
      onSpace={primary.disabled ? undefined : primary.run}
      footer={
        <>
          <Button variant="secondary" testId="gameover-view-table" onClick={() => setHidden(true)}>
            Tisch ansehen
          </Button>
          {!spectator && (
            <Button variant="secondary" onClick={toHome}>
              Hauptmenü
            </Button>
          )}
          <Button variant="primary" kbd="Space" testId="gameover-primary" disabled={primary.disabled} onClick={primary.run}>
            {primary.label}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3.5" style={{ padding: '18px 20px 6px' }}>
        <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
          <span className="num uppercase text-ember" style={{ fontSize: 64, lineHeight: 0.85, letterSpacing: '.02em' }}>
            {display}
          </span>
          <span className="text-[14px] text-fg-3">
            {meta}
            {over.error && <span style={{ color: 'var(--color-attack)' }}> · Fehler: {over.error}</span>}
          </span>
        </div>

        <div className="flex flex-col" data-testid="gameover-placements">
          {over.placements.map((p) => {
            const isMe = p.playerId === human?.playerId
            const ps = playerOf(p.playerId)
            return (
              <div
                key={p.playerId}
                className="grid items-center gap-3 border-b border-line-1 py-2"
                style={{ gridTemplateColumns: '34px 28px minmax(0,1fr) auto' }}
                data-player-place={p.place}
              >
                <span className="num" style={{ fontSize: 22, lineHeight: 1, color: placeNumColor(p.place, isMe) }}>
                  {p.place}.
                </span>
                <Avatar src={ps ? commanderArt(ps) : null} name={p.name} size={28} />
                <span className="truncate text-[14px] font-semibold text-fg-1">{isMe ? 'Du' : p.name}</span>
                <span className="text-body-s text-fg-3">{placeMeta(p, over.winnerId)}</span>
              </div>
            )
          })}
        </div>

        {r && <XpBox r={r} instant={countDone} />}
        {r && countDone && r.deckName && (r.masteryLevel ?? 0) > 0 && <MasteryBox r={r} art={myArt} />}

        {startError && (
          <div
            className="flex items-center gap-2.5 rounded-sm bg-danger-bg text-[13.5px] text-fg-1"
            style={{ padding: '10px 14px', boxShadow: 'inset 0 0 0 1px color-mix(in oklab, var(--color-attack) 40%, transparent)' }}
            role="alert"
          >
            <Icon name="error" size={16} style={{ color: 'var(--color-attack)' }} />
            {startError}
          </div>
        )}
      </div>
    </BoardModal>
  )
}
