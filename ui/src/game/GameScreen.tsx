import { AnimatePresence, motion } from 'motion/react'
import { useCallback, useEffect, useState } from 'react'
import type { Card, PlayerState, Tempo } from '../api/types'
import { me as meOf, opponents as oppsOf, useGame } from '../store/game'
import { useNav } from '../store/nav'
import { viewerOpen } from '../components/Modal'
import { sounds } from '../lib/sounds'
import { Battlefield } from './Battlefield'
import { CombatOverlay } from './CombatOverlay'
import { GameOverOverlay } from './GameOverOverlay'
import { Hand } from './Hand'
import { useInteraction, type Interaction } from './interaction'
import { OpponentPod } from './OpponentPod'
import { PhaseBar } from './PhaseBar'
import { CommandZone, CommanderDamage, LifeBadge, ManaPool, ZoneCounters, commanderArt } from './PlayerInfo'
import { HOTKEY_ACTIONS, usePromptButtons } from './promptActions'
import { PromptBar } from './PromptBar'
import { PromptDialogs } from './PromptDialogs'
import { LogPanel, RevealPopups, Toasts, ZoomPanel, type LogFilter } from './Side'
import { StackPanel } from './StackPanel'
import { TargetOverlay } from './TargetOverlay'

const TEMPOS: { key: Tempo; label: string }[] = [
  { key: 'BLITZ', label: 'Blitz' },
  { key: 'NORMAL', label: 'Normal' },
  { key: 'BEDACHT', label: 'Bedacht' },
  { key: 'MAX', label: 'Max' },
]

export function GameScreen() {
  const state = useGame((s) => s.state)
  const conn = useGame((s) => s.conn)
  const hover = useGame((s) => s.hover)
  const setHover = useGame((s) => s.setHover)
  const thinking = useGame((s) => s.thinking)
  const gameOver = useGame((s) => s.gameOver)
  const inter = useInteraction()
  const [showLog, setShowLog] = useState(true)
  const [stackFocus, setStackFocus] = useState<string | null>(null)
  const [logFilter, setLogFilter] = useLogFilter()
  const onHover = useCallback((c: Card | null) => setHover(c), [setHover])

  useHotkeys(inter)

  if (!state) {
    return (
      <div className="bg-table flex h-full items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-ink-300">
          <div className="h-10 w-10 animate-spin rounded-full border-2 border-gold-400 border-t-transparent" />
          <div>{conn === 'open' ? 'Spiel wird vorbereitet – Decks werden gemischt …' : 'Verbinde mit Engine …'}</div>
        </div>
      </div>
    )
  }

  const me = meOf(state)
  const opps = oppsOf(state)
  // Sitzordnung: links, oben, rechts (im Uhrzeigersinn nach mir)
  const [left, top, right] = [opps[0], opps[1], opps[2]]

  return (
    <div className="bg-table relative flex h-full w-full overflow-hidden">
      <div className="flex min-w-0 flex-1 flex-col gap-2 p-2">
        <TopBar />
        {/* Gegner */}
        <div className="grid min-h-0 flex-[0.95] grid-cols-3 gap-2">
          {[left, top, right].map((p, i) =>
            p ? <OpponentPod key={p.id} p={p} inter={inter} onHover={onHover} thinking={thinking === p.id} /> : <div key={`leer-${i}`} />,
          )}
        </div>
        {/* Mitte: Stapel */}
        <div className="pointer-events-none relative flex shrink-0 justify-center">
          <div className="absolute bottom-0 left-1/2 z-30 flex max-h-[48vh] -translate-x-1/2 flex-col items-center justify-end">
            <StackPanel stack={state.stack} players={state.players} inter={inter} onHover={onHover} focusId={stackFocus} onFocus={setStackFocus} />
          </div>
        </div>
        {/* Ich */}
        {me && <MyArea me={me} inter={inter} onHover={onHover} hand={state.hand} />}
        <PromptBar inter={inter} />
      </div>

      {/* Seitenleiste */}
      <div className="flex w-[320px] shrink-0 flex-col gap-2 border-l border-white/5 bg-ink-950/40 p-2">
        <div className="shrink-0">
          <ZoomPanel card={hover} />
        </div>
        <div className={`glass flex flex-col overflow-hidden rounded-xl ${showLog ? 'min-h-[180px] flex-1' : 'h-9'}`}>
          <div className="flex shrink-0 items-center justify-between gap-2 px-3 py-1.5">
            <button className="flex flex-1 items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-ink-300 hover:text-ink-100" onClick={() => setShowLog(!showLog)}>
              <span>{showLog ? '▾' : '▸'}</span> Spielverlauf
            </button>
            {showLog && (
              <div className="flex items-center gap-0.5 rounded-md bg-ink-950/60 p-0.5 ring-1 ring-white/10" title="Routine (Ziehen, Zugbeginn …) ausblenden">
                {(['important', 'all'] as const).map((f) => (
                  <button
                    key={f}
                    className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${logFilter === f ? 'bg-ink-600 text-ink-100' : 'text-ink-400 hover:text-ink-200'}`}
                    onClick={() => setLogFilter(f)}
                  >
                    {f === 'important' ? 'Wichtiges' : 'Alles'}
                  </button>
                ))}
              </div>
            )}
          </div>
          {showLog && (
            <div className="min-h-0 flex-1">
              <LogPanel filter={logFilter} />
            </div>
          )}
        </div>
      </div>

      {me?.lost && !gameOver && <EliminatedBanner />}
      <CombatOverlay combat={state.combat} seq={state.seq} />
      <TargetOverlay stack={state.stack} focusId={stackFocus} seq={state.seq} />
      <PromptDialogs inter={inter} onHover={onHover} />
      <RevealPopups />
      <Toasts />
      <AnimatePresence>{gameOver && <GameOverOverlay />}</AnimatePresence>
    </div>
  )
}

function EliminatedBanner() {
  const leave = useGame((s) => s.leave)
  const setTempo = useGame((s) => s.setTempo)
  const [watching, setWatching] = useState(false)
  if (watching) return null
  return (
    <motion.div initial={{ opacity: 0, y: -10 }} animate={{ opacity: 1, y: 0 }} className="glass fixed left-1/2 top-16 z-40 flex -translate-x-1/2 items-center gap-4 rounded-2xl px-5 py-3 shadow-2xl ring-1 ring-blood-400/40">
      <div>
        <div className="font-display text-lg font-bold text-blood-400">Du bist ausgeschieden</div>
        <div className="text-xs text-ink-300">Die Bots spielen noch weiter. Dein Ergebnis steht fest.</div>
      </div>
      <button
        className="btn-ghost"
        onClick={() => {
          setTempo('BLITZ')
          setWatching(true)
        }}
      >
        Zuschauen (Blitz)
      </button>
      <button className="btn-primary" onClick={leave}>
        Spiel beenden
      </button>
    </motion.div>
  )
}

function TopBar() {
  const state = useGame((s) => s.state)!
  const tempo = useGame((s) => s.tempo)
  const setTempo = useGame((s) => s.setTempo)
  const leave = useGame((s) => s.leave)
  const gameOver = useGame((s) => s.gameOver)
  const reset = useGame((s) => s.reset)
  const go = useNav((s) => s.go)
  const [confirm, setConfirm] = useState(false)
  const [muted, setMuted] = useState(sounds.isMuted())
  const autoMana = useGame((s) => s.autoMana)
  const setAutoMana = useGame((s) => s.setAutoMana)
  const autoPass = useGame((s) => s.autoPass)
  const setAutoPass = useGame((s) => s.setAutoPass)

  return (
    <div className="flex shrink-0 items-center justify-between gap-3 px-1">
      <div className="flex min-w-0 items-center gap-3">
        <div className="font-display text-sm font-bold tracking-widest text-gold-300">MAGELITE</div>
        <PhaseBar state={state} />
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <div className="flex items-center gap-0.5 rounded-lg bg-ink-900/70 p-0.5 ring-1 ring-white/10" title="Bot-Tempo">
          {TEMPOS.map((t) => (
            <button key={t.key} className={`rounded-md px-2 py-1 text-[11px] font-semibold ${tempo === t.key ? 'bg-arcane-500 text-ink-950' : 'text-ink-300 hover:text-ink-100'}`} onClick={() => setTempo(t.key)}>
              {t.label}
            </button>
          ))}
        </div>
        <button
          className={`btn-ghost !px-2 !py-1 !text-xs ${autoMana ? '!border-arcane-400/60 !text-arcane-400' : ''}`}
          title="Mana automatisch bezahlen"
          onClick={() => setAutoMana(!autoMana)}
        >
          {autoMana ? '⚡ Auto-Mana' : 'Mana manuell'}
        </button>
        <button
          className={`btn-ghost !px-2 !py-1 !text-xs ${autoPass ? '!border-arcane-400/60 !text-arcane-400' : ''}`}
          title="Automatisch passen, wenn nichts spielbar ist (aus = Gegner sieht nicht, ob du Optionen hast)"
          onClick={() => setAutoPass(!autoPass)}
        >
          {autoPass ? '⏩ Auto-Passen' : 'Passen manuell'}
        </button>
        <button
          className="btn-ghost !px-2 !py-1 !text-xs"
          title="Ton an/aus"
          onClick={() => {
            sounds.setMuted(!muted)
            setMuted(!muted)
          }}
        >
          {muted ? '🔇' : '🔊'}
        </button>
        {gameOver ? (
          <button
            className="btn-ghost !px-3 !py-1 !text-xs"
            onClick={() => {
              reset()
              go('home')
            }}
          >
            Zum Menü
          </button>
        ) : confirm ? (
          <div className="flex items-center gap-1">
            <span className="text-xs text-ink-300">Wirklich aufgeben?</span>
            <button className="btn-danger !px-2 !py-1 !text-xs" onClick={() => { leave(); setConfirm(false) }}>Ja</button>
            <button className="btn-ghost !px-2 !py-1 !text-xs" onClick={() => setConfirm(false)}>Nein</button>
          </div>
        ) : (
          <button className="btn-ghost !px-3 !py-1 !text-xs" onClick={() => setConfirm(true)}>
            Aufgeben
          </button>
        )}
      </div>
    </div>
  )
}

function MyArea({ me, inter, onHover, hand }: { me: PlayerState; inter: Interaction; onHover: (c: Card | null) => void; hand: Card[] }) {
  const answer = useGame((s) => s.answer)
  const art = commanderArt(me)
  const targetable = inter.playerTargetable(me.id)
  const manaClick = inter.mode === 'mana' ? (type: string) => answer({ mana: { playerId: me.id, type } }) : undefined
  return (
    <div className="flex min-h-0 flex-[1.25] flex-col gap-1">
      <div className={`glass relative flex min-h-0 flex-1 gap-2 rounded-2xl p-2 ${me.active ? 'ring-2 ring-gold-400/60' : ''}`}>
        <div
          data-player={me.id}
          className={`flex w-[170px] shrink-0 flex-col gap-2 rounded-xl bg-ink-950/40 p-2 ${targetable ? 'glow-target cursor-pointer' : ''}`}
          onClick={() => targetable && inter.click(me.id)}
        >
          <div className="flex items-center gap-2">
            <div className="h-12 w-12 shrink-0 overflow-hidden rounded-full ring-2 ring-gold-400/50">
              {art ? <img src={art} alt="" className="h-full w-full object-cover" /> : <div className="h-full w-full bg-ink-600" />}
            </div>
            <LifeBadge life={me.life} big />
          </div>
          <div className="truncate text-xs text-ink-300">{me.deckName}</div>
          <ZoneCounters p={me} onHover={onHover} inter={inter} />
          <CommanderDamage dmg={me.commanderDamage} />
          <ManaPool mana={me.mana} onClick={manaClick} />
          {me.skips && me.skips.length > 0 && <div className="rounded bg-arcane-500/15 px-1.5 py-0.5 text-[10px] text-arcane-400">⏩ Auto-Passen aktiv (F3 stoppt)</div>}
          <div className="mt-auto">
            <CommandZone objects={me.command} inter={inter} onHover={onHover} size="md" />
          </div>
        </div>
        <div className="min-h-0 min-w-0 flex-1">
          <Battlefield perms={me.battlefield} size="md" inter={inter} onHover={onHover} />
        </div>
      </div>
      <div className="h-[150px] shrink-0">
        <Hand cards={hand} inter={inter} onHover={onHover} />
      </div>
    </div>
  )
}

function useHotkeys(inter: Interaction) {
  const buttons = usePromptButtons(inter)
  const action = useGame((s) => s.action)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) return
      if (viewerOpen()) return
      if (e.key === ' ' || e.key === 'Enter' || e.key === 'F2') {
        const primary = buttons.find((b) => b.hotkey === 'Space') ?? buttons[0]
        if (primary) {
          e.preventDefault()
          primary.run()
        }
        return
      }
      if (e.key === 'Escape') {
        const esc = buttons.find((b) => b.hotkey === 'Esc')
        if (esc) {
          e.preventDefault()
          esc.run()
        }
        return
      }
      const a = HOTKEY_ACTIONS[e.key]
      if (a) {
        e.preventDefault()
        action(a)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [buttons, action])
}

function useLogFilter(): [LogFilter, (f: LogFilter) => void] {
  const [f, setF] = useState<LogFilter>(() => {
    try {
      return localStorage.getItem('magelite.logFilter') === 'all' ? 'all' : 'important'
    } catch {
      return 'important'
    }
  })
  const set = (v: LogFilter) => {
    setF(v)
    try {
      localStorage.setItem('magelite.logFilter', v)
    } catch {
      /* egal */
    }
  }
  return [f, set]
}

export function FadeIn({ children }: { children: React.ReactNode }) {
  return (
    <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="h-full">
      {children}
    </motion.div>
  )
}
