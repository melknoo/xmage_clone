import { useState } from 'react'
import type { Tempo } from '../api/types'
import { Modal } from '../components/Modal'
import { sounds } from '../lib/sounds'
import { me as meOf, useGame } from '../store/game'
import { useNav } from '../store/nav'
import { useTable } from '../store/table'
import type { LogFilter } from './Side'

const TEMPOS: { key: Tempo; label: string }[] = [
  { key: 'BLITZ', label: 'Blitz' },
  { key: 'NORMAL', label: 'Normal' },
  { key: 'BEDACHT', label: 'Bedacht' },
  { key: 'MAX', label: 'Max' },
]

/**
 * Pausemenue (Esc / Knopf „Menü“): Optionen, Aufgeben mit Bestaetigung, Spiel verlassen.
 * Nach dem Aufgeben (oder Ausscheiden) laeuft das Spiel fuer die anderen weiter; von hier geht es ins Hauptmenue
 * oder zurueck zum Tisch. Solo (keine anderen Menschen) beendet Aufgeben das Spiel, das Ergebnis zeigt GameOverOverlay.
 */
export function PauseMenu({ logFilter, setLogFilter }: { logFilter: LogFilter; setLogFilter: (f: LogFilter) => void }) {
  const close = () => useGame.getState().setMenuOpen(false)
  const state = useGame((s) => s.state)
  const conceded = useGame((s) => s.conceded)
  const gameOver = useGame((s) => s.gameOver)
  const hello = useGame((s) => s.hello)
  const tempo = useGame((s) => s.tempo)
  const setTempo = useGame((s) => s.setTempo)
  const autoMana = useGame((s) => s.autoMana)
  const setAutoMana = useGame((s) => s.setAutoMana)
  const autoPass = useGame((s) => s.autoPass)
  const setAutoPass = useGame((s) => s.setAutoPass)
  const leave = useGame((s) => s.leave)
  const reset = useGame((s) => s.reset)
  const go = useNav((s) => s.go)
  const tableId = useTable((s) => s.tableId)
  const [muted, setMuted] = useState(sounds.isMuted())
  const [confirm, setConfirm] = useState(false)

  const isHost = hello?.host !== false
  const otherHumans = (hello?.seats.filter((x) => x.human && x.playerId !== hello?.myPlayerId).length ?? 0) > 0
  const out = conceded || !!meOf(state)?.lost || !!gameOver
  const exit = (where: 'home' | 'table') => {
    reset()
    go(where)
  }
  const concede = () => {
    setConfirm(false)
    leave()
    // Solo: Aufgeben beendet das Spiel, das Ergebnis kommt als gameOver -> Menue zu
    if (!otherHumans) close()
  }

  const Toggle = ({ on, label, hint, onClick }: { on: boolean; label: string; hint?: string; onClick: () => void }) => (
    <button className={`flex w-full items-center justify-between rounded-xl px-4 py-2.5 text-left ring-1 transition ${on ? 'bg-arcane-500/15 ring-arcane-400/50' : 'bg-ink-950/50 ring-white/10 hover:ring-white/25'}`} onClick={onClick} title={hint}>
      <span className="text-sm font-semibold text-ink-100">{label}</span>
      <span className={`text-xs font-semibold ${on ? 'text-arcane-400' : 'text-ink-400'}`}>{on ? 'an' : 'aus'}</span>
    </button>
  )

  return (
    <Modal title="Menü" onClose={close} viewer>
      <div className="flex flex-col gap-5">
        <section>
          <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-ink-400">Optionen</div>
          <div className="grid grid-cols-2 gap-2">
            <Toggle on={autoMana} label="Auto-Mana" hint="Mana automatisch bezahlen" onClick={() => setAutoMana(!autoMana)} />
            <Toggle on={autoPass} label="Auto-Passen" hint="Automatisch passen, wenn nichts spielbar ist" onClick={() => setAutoPass(!autoPass)} />
            <Toggle
              on={!muted}
              label="Ton"
              onClick={() => {
                sounds.setMuted(!muted)
                setMuted(!muted)
              }}
            />
            <Toggle on={logFilter === 'all'} label="Verlauf: alles" hint="Aus = nur Wichtiges im Spielverlauf" onClick={() => setLogFilter(logFilter === 'all' ? 'important' : 'all')} />
          </div>
          <div className="mt-3 flex items-center gap-2">
            <span className="text-sm text-ink-300">Bot-Tempo</span>
            <div className="flex items-center gap-0.5 rounded-lg bg-ink-950/60 p-0.5 ring-1 ring-white/10">
              {TEMPOS.map((t) => (
                <button
                  key={t.key}
                  disabled={!isHost}
                  className={`rounded-md px-2.5 py-1 text-xs font-semibold disabled:cursor-default ${tempo === t.key ? 'bg-arcane-500 text-ink-950' : isHost ? 'text-ink-300 hover:text-ink-100' : 'text-ink-500'}`}
                  onClick={() => setTempo(t.key)}
                >
                  {t.label}
                </button>
              ))}
            </div>
            {!isHost && <span className="text-xs text-ink-500">stellt der Gastgeber</span>}
          </div>
        </section>

        <section>
          <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-ink-400">Spiel</div>
          {!out ? (
            confirm ? (
              <div className="flex items-center gap-2 rounded-xl bg-blood-500/10 px-4 py-3 ring-1 ring-blood-400/40">
                <span className="flex-1 text-sm text-ink-100">Wirklich aufgeben?{otherHumans ? ' Die anderen spielen ohne dich weiter.' : ' Das Spiel endet.'}</span>
                <button className="btn-danger !px-3 !py-1 !text-xs" onClick={concede}>
                  Ja, aufgeben
                </button>
                <button className="btn-ghost !px-3 !py-1 !text-xs" onClick={() => setConfirm(false)}>
                  Nein
                </button>
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <button className="btn-primary" onClick={close}>
                  Weiterspielen
                </button>
                <button className="btn-ghost" onClick={() => setConfirm(true)}>
                  Aufgeben …
                </button>
              </div>
            )
          ) : (
            <div className="flex flex-col gap-3">
              <div className="text-sm text-ink-300">
                {gameOver ? 'Das Spiel ist vorbei.' : conceded ? 'Du hast aufgegeben – die anderen spielen weiter. Dein Ergebnis landet in der Statistik.' : 'Du bist ausgeschieden – die anderen spielen weiter.'}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {!gameOver && (
                  <button className="btn-ghost" onClick={close}>
                    Zuschauen
                  </button>
                )}
                {tableId && (
                  <button className="btn-primary" onClick={() => exit('table')}>
                    Zurück zum Tisch
                  </button>
                )}
                <button className={tableId ? 'btn-ghost' : 'btn-primary'} onClick={() => exit('home')}>
                  Zum Hauptmenü
                </button>
              </div>
            </div>
          )}
        </section>
        <div className="text-[11px] text-ink-500">Esc schließt das Menü.</div>
      </div>
    </Modal>
  )
}
