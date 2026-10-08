import { AnimatePresence, motion } from 'motion/react'
import { useCallback, useEffect, useState, type CSSProperties } from 'react'
import type { Card } from '../api/types'
import { BoardModalRoot, viewerOpen } from '../components/BoardModal'
import { Button, Wordmark } from '../components/ui'
import { Icon } from '../lib/icons'
import { DUR, EASE_OUT } from '../lib/motion'
import { me as meOf, opponents as oppsOf, useGame } from '../store/game'
import { useNav } from '../store/nav'
import { useTable } from '../store/table'
import { FxLayer } from './FxLayer'
import { GameOverOverlay } from './GameOverOverlay'
import { Hand } from './Hand'
import { useInteraction, type Interaction } from './interaction'
import { boardCssVars, useBoardLayout } from './layout'
import { MyArea } from './MyArea'
import { OpponentPod } from './OpponentPod'
import { HOTKEY_ACTIONS, usePromptButtons } from './promptActions'
import { PauseMenu } from './PauseMenu'
import { PromptBar } from './PromptBar'
import { PromptDialogs } from './PromptDialogs'
import { RevealPopups } from './RevealPopups'
import { SidePanel } from './SidePanel'
import { StackPanel } from './StackPanel'
import { TopBar } from './TopBar'
import { ZoneViewer } from './ZoneViewer'

/*
 * Ebenen: Brett 0-6 (Ember-Leiste 2, Stapel 6) · FX 10 · Ausgeschieden-Banner 15 · Dialoge/Pille 20 (eigene Ebene in der
 * linken Spalte) · Aufdeckungen 25. Toasts haengen global in App.tsx (ueber allem).
 */

/**
 * Spielbrett nach dem Prototyp "Spielbrett": Kopfleiste ueber die ganze Breite; darunter links die Brettspalte
 * (Gegner-Pods, eigener Bereich mit Stapel oben rechts, Hand, Aktionsleiste, Portal-Ziel der Dialoge) und rechts die
 * Seitenleiste. Masse aus game/layout.ts (Kanten zaehlen wie im Prototyp zusaetzlich zur Hoehe).
 */
export function GameScreen() {
  const state = useGame((s) => s.state)
  const conn = useGame((s) => s.conn)
  const setHover = useGame((s) => s.setHover)
  const gameOver = useGame((s) => s.gameOver)
  const menuOpen = useGame((s) => s.menuOpen)
  const spectator = useGame((s) => s.spectator)
  const inter = useInteraction()
  const layout = useBoardLayout()
  const onHover = useCallback((c: Card | null) => setHover(c), [setHover])

  useHotkeys(inter)

  if (!state) return <Loading open={conn === 'open'} />

  const me = meOf(state)
  const opps = oppsOf(state)
  // Sitzordnung: links, oben, rechts (im Uhrzeigersinn nach mir)
  const seats = [opps[0], opps[1], opps[2]]

  const rootStyle = { ...boardCssVars(layout), ['--modal-inset-right' as string]: `${layout.side}px` } as CSSProperties

  return (
    <div className="flex h-full w-full flex-col overflow-hidden bg-bg-1" style={rootStyle} data-compact={layout.compact ? 'true' : undefined}>
      <TopBar layout={layout} />
      <div className="flex min-h-0 flex-1">
        {/* linke Brettspalte */}
        <div className="relative flex min-w-0 flex-1 flex-col">
          {/* Gegner */}
          <div className="flex shrink-0 border-b border-line-2" style={{ height: layout.oppH + 1 }}>
            {seats.map((p, i) => (p ? <OpponentPod key={p.id} p={p} inter={inter} onHover={onHover} layout={layout} /> : <div key={`leer-${i}`} className="min-w-0 flex-1 border-r border-line-2" />))}
          </div>

          {/* eigener Bereich: Ember-Leiste im eigenen Zug, Stapel schwebt oben rechts (nie ueber einem Pod) */}
          <div className="relative flex min-h-0 flex-1">
            {me?.active && !gameOver && <span className="absolute inset-x-0 top-0 z-[2] h-[3px] bg-ember" aria-hidden />}
            {me && <MyArea me={me} inter={inter} onHover={onHover} layout={layout} />}
            <StackPanel inter={inter} onHover={onHover} />
          </div>

          {/* Hand */}
          <div className="relative shrink-0 border-t border-line-2 bg-bg-board" style={{ height: layout.handH + 1 }} data-zone="hand" data-owner={me?.id}>
            <Hand inter={inter} onHover={onHover} layout={layout} />
          </div>

          <PromptBar inter={inter} layout={layout} />

          {me?.lost && !gameOver && !menuOpen && !spectator && <EliminatedBanner />}
          {!gameOver && <HostLostBanner />}
          {/* Portal-Ziel der Brett-Dialoge: nur die linke Spalte, Kopf- und Seitenleiste bleiben bedienbar */}
          <div className="pointer-events-none absolute inset-0 z-20">
            <BoardModalRoot />
          </div>

          {menuOpen && !gameOver && !spectator && <PauseMenu />}
          <PromptDialogs inter={inter} onHover={onHover} layout={layout} />
          <ZoneViewer inter={inter} onHover={onHover} layout={layout} />
          <AnimatePresence>{gameOver && <GameOverOverlay />}</AnimatePresence>
        </div>

        <SidePanel layout={layout} />
      </div>

      {/* FX (z-10) und Aufdeckungen (z-25) positionieren sich selbst (fixed, CSS-Variablen von boardCssVars) */}
      <FxLayer />
      <RevealPopups />
    </div>
  )
}

/** Ladezustand: Wortmarke, 2-px-Balken (Verbinden -> Vorbereiten), Text */
function Loading({ open }: { open: boolean }) {
  return (
    <div className="flex h-full w-full flex-col items-center justify-center gap-5 bg-bg-1">
      <Wordmark size={34} />
      <div className="bar-track h-[2px] w-[240px]" aria-hidden>
        <div className="bar-fill bg-ember" style={{ width: open ? '66%' : '33%' }} />
      </div>
      <div className="text-[13px] text-fg-3">{open ? 'Spiel wird vorbereitet – Decks werden gemischt …' : 'Verbinde mit Engine …'}</div>
    </div>
  )
}

/** Schwebende Karte oben mittig in der Brettspalte, wenn ich ausgeschieden bin (Spiel laeuft fuer andere weiter). */
/** Selbst gehosteter Tisch: Verbindung zum Rechner des Gastgebers weg (store.hostLost); fly wartet bis zu 60 s. */
function HostLostBanner() {
  const since = useGame((s) => s.hostLost)
  const [, tick] = useState(0)
  useEffect(() => {
    if (since === null) return
    const iv = window.setInterval(() => tick((n) => n + 1), 1000)
    return () => window.clearInterval(iv)
  }, [since])
  if (since === null) return null
  const secs = Math.max(0, Math.round((Date.now() - since) / 1000))
  return (
    <motion.div
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DUR.d2, ease: EASE_OUT }}
      className="absolute inset-x-0 top-0 z-[15] flex items-center justify-center gap-2.5 bg-bg-2 px-4 py-2.5 text-[13.5px] text-fg-1"
      style={{ boxShadow: 'inset 0 -1px 0 var(--color-line-3)' }}
      data-testid="host-lost"
    >
      <Icon name="disconnected" size={16} className="text-attack" />
      <span>Verbindung zum Gastgeber unterbrochen · seit {secs} s – das Spiel wartet bis zu einer Minute</span>
    </motion.div>
  )
}

function EliminatedBanner() {
  const leave = useGame((s) => s.leave)
  const reset = useGame((s) => s.reset)
  const setTempo = useGame((s) => s.setTempo)
  const isHost = useGame((s) => s.hello?.host !== false)
  const conceded = useGame((s) => s.conceded)
  const otherHumans = useGame((s) => (s.hello?.seats.filter((x) => x.human && x.playerId !== s.hello?.myPlayerId).length ?? 0) > 0)
  const tableId = useTable((s) => s.tableId)
  const go = useNav((s) => s.go)
  const [watching, setWatching] = useState(false)
  if (watching) return null
  // Mit anderen Menschen am Tisch (oder nach eigenem Aufgeben) laeuft das Spiel ohne mich weiter -> raus
  // (Ergebnis kommt in die Statistik). Solo beendet leave() das Spiel, das Ergebnis zeigt GameOverOverlay.
  const leaveTable = () => {
    leave()
    if (otherHumans || conceded) {
      reset()
      go(tableId ? 'table' : 'home')
    }
  }
  return (
    <motion.div
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DUR.d3, ease: EASE_OUT }}
      className="floating absolute left-1/2 top-[14px] z-[15] flex max-w-[calc(100%-28px)] items-center gap-4 px-4 py-[14px]"
      style={{ x: '-50%' }}
      data-testid="eliminated-banner"
    >
      <div className="flex min-w-0 flex-col gap-1.5">
        <span className="label" style={{ color: 'var(--color-attack)' }}>
          Ausgeschieden
        </span>
        <span className="text-body-l font-medium text-fg-1">Du bist ausgeschieden</span>
        <span className="text-[13.5px] leading-[1.45] text-fg-2">Die anderen spielen noch weiter. Dein Ergebnis steht fest.</span>
      </div>
      <Button
        variant="ghost"
        game
        className="shrink-0"
        onClick={() => {
          if (isHost) setTempo('BLITZ')
          setWatching(true)
        }}
      >
        {isHost ? 'Zuschauen (Blitz)' : 'Zuschauen'}
      </Button>
      <Button variant="primary" game className="shrink-0" onClick={leaveTable}>
        {tableId && (otherHumans || conceded) ? 'Zurück zum Tisch' : 'Spiel verlassen'}
      </Button>
    </motion.div>
  )
}

/**
 * Spiel-Tasten: Space/Enter/F2 = Hauptaktion, Esc = Markierung loesen -> Esc-Knopf -> Pause (nicht nach Spielende),
 * F3-F11 = Passen. Tab und die Tasten offener Dialoge behandelt BoardModal (Capture-Phase); Ansichts-Dialoge sperren alles.
 * Zuschauer: nur Esc = Zuschauen beenden.
 */
function useHotkeys(inter: Interaction) {
  const buttons = usePromptButtons(inter)
  const action = useGame((s) => s.action)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) return
      if (e.defaultPrevented || viewerOpen()) return
      const g = useGame.getState()
      if (g.spectator) {
        if (e.key === 'Escape' && !e.repeat) {
          e.preventDefault()
          g.stopSpectating()
        }
        return
      }
      if (e.key === ' ' || e.key === 'Enter' || e.key === 'F2') {
        const primary = buttons.find((b) => b.hotkey === 'Space') ?? buttons[0]
        if (primary) {
          e.preventDefault()
          if (!primary.disabled && !primary.confirm) primary.run()
        }
        return
      }
      if (e.key === 'Escape') {
        if (g.marked.size > 0) {
          // erst die Mehrfach-Markierung aufheben
          e.preventDefault()
          g.clearMarks()
          return
        }
        const esc = buttons.find((b) => b.hotkey === 'Esc')
        if (esc) {
          e.preventDefault()
          esc.run()
          return
        }
        // sonst: Pausemenue
        if (!g.gameOver) {
          e.preventDefault()
          g.setMenuOpen(true)
        }
        return
      }
      const a = HOTKEY_ACTIONS[e.key]
      if (a) {
        e.preventDefault()
        // F10 bei leerem Stapel: XMage ignoriert es (Engine lehnt ab) - gar nicht erst senden
        if (a === 'PASS_PRIORITY_UNTIL_STACK_RESOLVED' && (g.state?.stack.length ?? 0) === 0) return
        action(a)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [buttons, action])
}
