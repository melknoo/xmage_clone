import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useGame } from '../store/game'
import { useNav } from '../store/nav'
import { useTable } from '../store/table'

function fmtDuration(ms: number) {
  const m = Math.floor(ms / 60000)
  const s = Math.floor((ms % 60000) / 1000)
  return `${m}:${String(s).padStart(2, '0')} min`
}

export function GameOverOverlay() {
  const over = useGame((s) => s.gameOver)!
  const connect = useGame((s) => s.connect)
  const reset = useGame((s) => s.reset)
  const go = useNav((s) => s.go)
  const lastSetup = useNav((s) => s.lastSetup)
  const tableId = useTable((s) => s.tableId)
  const [hidden, setHidden] = useState(false)
  const [busy, setBusy] = useState(false)
  const myId = useGame((s) => s.hello?.myPlayerId)
  const human = over.placements.find((p) => p.playerId === myId) ?? over.placements.find((p) => p.human)
  const won = human?.place === 1 && over.winnerId === human.playerId
  const r = over.reward
  const [xpShown, setXpShown] = useState(0)

  useEffect(() => {
    if (!r) return
    let raf = 0
    const start = performance.now()
    const tick = (t: number) => {
      const k = Math.min(1, (t - start) / 1400)
      setXpShown(Math.round(r.xpGained * (1 - Math.pow(1 - k, 3))))
      if (k < 1) raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [r])

  if (hidden) {
    return (
      <button className="btn-primary fixed bottom-24 left-1/2 z-50 -translate-x-1/2" onClick={() => setHidden(false)}>
        Ergebnis anzeigen
      </button>
    )
  }

  const again = async () => {
    if (!lastSetup) return
    setBusy(true)
    try {
      const res = await api.post<{ gameId: string }>('/api/games', lastSetup)
      connect(res.gameId)
    } finally {
      setBusy(false)
    }
  }

  return (
    <motion.div className="fixed inset-0 z-50 flex items-center justify-center bg-ink-950/75 backdrop-blur-sm" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
      <motion.div className="glass w-[560px] rounded-3xl p-7 shadow-2xl" initial={{ scale: 0.9, y: 20 }} animate={{ scale: 1, y: 0 }} transition={{ type: 'spring', stiffness: 300, damping: 24 }}>
        <div className={`text-center font-display text-4xl font-bold tracking-wide ${won ? 'text-gold-300' : 'text-ink-100'}`}>
          {won ? 'Sieg!' : human ? `Platz ${human.place}` : 'Spielende'}
        </div>
        <div className="mt-1 text-center text-sm text-ink-300">
          {over.turns} Züge · {fmtDuration(over.durationMs)}
          {over.error && <span className="text-blood-400"> · Fehler: {over.error}</span>}
        </div>

        <div className="mt-5 flex flex-col gap-1.5">
          {over.placements.map((p) => (
            <div key={p.playerId} className={`flex items-center gap-3 rounded-xl px-3 py-2 ${p.playerId === human?.playerId ? 'bg-gold-400/10 ring-1 ring-gold-400/40' : p.human ? 'bg-ink-950/40 ring-1 ring-white/10' : 'bg-ink-950/40'}`}>
              <div className="w-6 text-center font-display text-lg font-bold text-ink-300">{p.place}</div>
              <div className="flex-1 truncate font-semibold">{p.name}</div>
              <div className="text-xs text-ink-400">{p.eliminatedTurn ? `raus in Zug ${p.eliminatedTurn}` : `${p.life} Leben`}</div>
              {p.mulligans > 0 && <div className="text-xs text-ink-400">{p.mulligans}× Mulligan</div>}
            </div>
          ))}
        </div>

        {r && (
          <div className="mt-5 rounded-2xl bg-ink-950/50 p-4 ring-1 ring-white/10">
            <div className="flex items-baseline justify-between">
              <div className="text-sm font-semibold text-ink-200">
                Erfahrung <span className="text-gold-300">+{xpShown} XP</span>
              </div>
              <div className="text-xs text-ink-400">
                Level {r.level} · {r.title}
                {r.levelUp && <span className="ml-2 rounded bg-gold-400 px-1.5 py-0.5 font-bold text-ink-950">LEVEL UP!</span>}
              </div>
            </div>
            <div className="mt-2 h-2.5 overflow-hidden rounded-full bg-ink-800">
              <motion.div
                className="h-full rounded-full bg-linear-to-r from-gold-500 to-gold-300"
                initial={{ width: `${Math.max(0, ((r.xpIntoLevel - (r.levelUp ? r.xpIntoLevel : r.xpGained)) / r.xpForNext) * 100)}%` }}
                animate={{ width: `${(r.xpIntoLevel / r.xpForNext) * 100}%` }}
                transition={{ duration: 1.4, ease: 'easeOut' }}
              />
            </div>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {r.breakdown.map((b) => (
                <span key={b.source} className="rounded-md bg-ink-800 px-2 py-0.5 text-[11px] text-ink-200">
                  {b.label} <span className="text-gold-300">+{b.amount}</span>
                </span>
              ))}
            </div>
            {r.deckName && r.masteryLevel !== undefined && (
              <div className="mt-3 text-xs text-ink-300">
                Deck-Meisterschaft <span className="font-semibold text-ink-100">{r.deckName}</span>: Stufe {r.masteryLevel}
                {r.masteryLevelBefore !== undefined && r.masteryLevel > r.masteryLevelBefore && <span className="ml-1 font-bold text-arcane-400">↑ aufgestiegen!</span>}
              </div>
            )}
          </div>
        )}

        <div className="mt-6 flex justify-center gap-2">
          <button className="btn-ghost" onClick={() => setHidden(true)}>
            Tisch ansehen
          </button>
          <button
            className="btn-ghost"
            onClick={() => {
              reset()
              go('home')
            }}
          >
            Hauptmenü
          </button>
          {tableId ? (
            <button
              className="btn-primary"
              onClick={() => {
                reset()
                go('table')
              }}
            >
              Zurück zum Tisch
            </button>
          ) : (
            <button className="btn-primary" disabled={!lastSetup || busy} onClick={again}>
              {busy ? 'Starte …' : 'Nochmal'}
            </button>
          )}
        </div>
      </motion.div>
    </motion.div>
  )
}
