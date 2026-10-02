import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { api } from './api/client'
import { GameScreen } from './game/GameScreen'
import { DecksScreen } from './screens/DecksScreen'
import { HomeScreen } from './screens/HomeScreen'
import { PlaySetupScreen } from './screens/PlaySetupScreen'
import { StatsScreen } from './screens/StatsScreen'
import { useGame } from './store/game'
import { useNav, type Screen } from './store/nav'

const NAV: { key: Screen; label: string; icon: string }[] = [
  { key: 'home', label: 'Held', icon: '🛡️' },
  { key: 'play', label: 'Spielen', icon: '⚔️' },
  { key: 'decks', label: 'Decks', icon: '🃏' },
  { key: 'stats', label: 'Statistik', icon: '📊' },
]

export function App() {
  const screen = useNav((s) => s.screen)
  const go = useNav((s) => s.go)
  const gameId = useGame((s) => s.gameId)
  const connect = useGame((s) => s.connect)
  const [engine, setEngine] = useState<'wait' | 'ok' | 'down'>('wait')

  // Engine erreichbar? Laufendes Spiel wieder aufnehmen?
  useEffect(() => {
    let stop = false
    let tries = 0
    const check = async () => {
      try {
        await api.get('/api/health')
        if (stop) return
        setEngine('ok')
        try {
          const cur = await api.get<{ gameId: string }>('/api/games/current')
          if (!stop && cur?.gameId && !useGame.getState().gameId) {
            connect(cur.gameId)
            go('game')
          }
        } catch {
          /* kein laufendes Spiel */
        }
      } catch {
        tries++
        if (!stop) {
          setEngine(tries > 40 ? 'down' : 'wait')
          window.setTimeout(check, 500)
        }
      }
    }
    check()
    return () => {
      stop = true
    }
  }, [connect, go])

  if (engine !== 'ok') {
    return (
      <div className="bg-table flex h-full flex-col items-center justify-center gap-4">
        <div className="font-display text-5xl font-bold tracking-[0.2em] text-gold-300">MAGELITE</div>
        {engine === 'wait' ? (
          <div className="flex items-center gap-3 text-ink-300">
            <div className="h-5 w-5 animate-spin rounded-full border-2 border-gold-400 border-t-transparent" />
            Engine startet – Kartendatenbank wird geladen …
          </div>
        ) : (
          <div className="text-blood-400">Engine nicht erreichbar. Läuft der Java-Prozess?</div>
        )}
      </div>
    )
  }

  if (screen === 'game' && gameId) {
    return <GameScreen />
  }

  return (
    <div className="bg-table flex h-full">
      <nav className="flex w-[84px] shrink-0 flex-col items-center gap-2 border-r border-white/5 bg-ink-950/50 py-5">
        <div className="mb-4 font-display text-xs font-bold tracking-[0.25em] text-gold-300">ML</div>
        {NAV.map((n) => (
          <button
            key={n.key}
            className={`flex w-16 flex-col items-center gap-1 rounded-xl py-2.5 text-[11px] font-semibold transition ${screen === n.key ? 'bg-gold-400/15 text-gold-300 ring-1 ring-gold-400/40' : 'text-ink-300 hover:bg-white/5 hover:text-ink-100'}`}
            onClick={() => go(n.key)}
          >
            <span className="text-xl">{n.icon}</span>
            {n.label}
          </button>
        ))}
        {gameId && (
          <button className="mt-auto flex w-16 flex-col items-center gap-1 rounded-xl bg-arcane-500/15 py-2.5 text-[11px] font-semibold text-arcane-400 ring-1 ring-arcane-400/40" onClick={() => go('game')}>
            <span className="text-xl">▶</span>
            Zum Tisch
          </button>
        )}
      </nav>
      <main className="min-w-0 flex-1 overflow-hidden">
        <AnimatePresence mode="wait">
          <motion.div key={screen} className="h-full" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }}>
            {screen === 'home' && <HomeScreen />}
            {screen === 'play' && <PlaySetupScreen />}
            {screen === 'decks' && <DecksScreen />}
            {screen === 'stats' && <StatsScreen />}
          </motion.div>
        </AnimatePresence>
      </main>
    </div>
  )
}
