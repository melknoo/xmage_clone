import { useState } from 'react'
import { BoardModal } from '../components/BoardModal'
import { Button, Segmented, Toggle } from '../components/ui'
import { Icon } from '../lib/icons'
import { TEMPOS } from '../lib/tempo'
import { me as meOf, useGame, type LogFilter } from '../store/game'
import { useNav } from '../store/nav'
import { useTable } from '../store/table'
import { useUi, type CardSize } from '../store/ui'
import { useBoardLayout } from './layout'

const CARD_SIZE_ITEMS: { id: CardSize; label: string; title: string }[] = [
  { id: 'small', label: 'Klein', title: 'Kleine Hand- und Mulligan-Karten' },
  { id: 'medium', label: 'Mittel', title: 'Mittlere Hand- und Mulligan-Karten' },
  { id: 'large', label: 'Groß', title: 'Große Hand- und Mulligan-Karten' },
]

const LOG_ITEMS: { id: LogFilter; label: string; title: string }[] = [
  { id: 'important', label: 'Wichtiges', title: 'Nur Wichtiges im Spielverlauf' },
  { id: 'all', label: 'Alles', title: 'Alle Einträge im Spielverlauf' },
]

/**
 * Pausemenue (Esc / Knopf „Menü“): Optionen, Bot-Tempo, Verlauf, Aufgeben mit Inline-Bestaetigung, Spiel verlassen.
 * Nach dem Aufgeben (oder Ausscheiden) laeuft das Spiel fuer die anderen weiter; von hier geht es zur Startseite
 * oder zurueck zum Tisch. Solo (keine anderen Menschen) beendet Aufgeben das Spiel, das Ergebnis zeigt GameOverOverlay.
 * Zuschauer: nur lokale Optionen und „Zuschauen beenden“.
 */
export function PauseMenu() {
  const layout = useBoardLayout()
  const close = () => useGame.getState().setMenuOpen(false)
  const state = useGame((s) => s.state)
  const conceded = useGame((s) => s.conceded)
  const gameOver = useGame((s) => s.gameOver)
  const hello = useGame((s) => s.hello)
  const spectator = useGame((s) => s.spectator)
  const tempo = useGame((s) => s.tempo)
  const setTempo = useGame((s) => s.setTempo)
  const autoMana = useGame((s) => s.autoMana)
  const setAutoMana = useGame((s) => s.setAutoMana)
  const autoPass = useGame((s) => s.autoPass)
  const setAutoPass = useGame((s) => s.setAutoPass)
  const fxEnabled = useGame((s) => s.fxEnabled)
  const setFxEnabled = useGame((s) => s.setFxEnabled)
  const muted = useGame((s) => s.muted)
  const setMuted = useGame((s) => s.setMuted)
  const cardSize = useUi((s) => s.cardSize)
  const setCardSize = useUi((s) => s.setCardSize)
  const logFilter = useGame((s) => s.logFilter)
  const setLogFilter = useGame((s) => s.setLogFilter)
  const leave = useGame((s) => s.leave)
  const reset = useGame((s) => s.reset)
  const stopSpectating = useGame((s) => s.stopSpectating)
  const go = useNav((s) => s.go)
  const tableId = useTable((s) => s.tableId)
  const [confirm, setConfirm] = useState(false)

  const isHost = !spectator && hello?.host !== false
  const otherHumans = (hello?.seats.filter((x) => x.human && x.playerId !== hello?.myPlayerId).length ?? 0) > 0
  const out = !spectator && (conceded || !!meOf(state)?.lost || !!gameOver)
  // Aufgeben jetzt = letzter Platz unter den noch Lebenden
  const place = Math.max(1, state?.players.filter((p) => !p.lost).length ?? 1)
  const turn = state?.turn ?? 0
  const round = Math.ceil(turn / Math.max(1, state?.players.length ?? 1))

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

  let footer
  if (spectator) {
    footer = (
      <Button variant="primary" icon="spectate" onClick={stopSpectating}>
        Zuschauen beenden
      </Button>
    )
  } else if (out) {
    footer = (
      <>
        {!gameOver && (
          <Button variant="ghost" icon="spectate" kbd="Esc" onClick={close}>
            Zuschauen
          </Button>
        )}
        {tableId && (
          <Button variant="primary" onClick={() => exit('table')}>
            Zurück zum Tisch
          </Button>
        )}
        <Button variant={tableId ? 'secondary' : 'primary'} onClick={() => exit('home')}>
          Zur Startseite
        </Button>
      </>
    )
  } else if (confirm) {
    footer = (
      <>
        <Button variant="ghost" onClick={() => setConfirm(false)}>
          Nein
        </Button>
        <Button variant="dangerConfirm" testId="pause-resign-confirm" onClick={concede}>
          Ja, aufgeben
        </Button>
      </>
    )
  } else {
    footer = (
      <>
        <Button variant="danger" testId="pause-resign" onClick={() => setConfirm(true)}>
          Aufgeben
        </Button>
        <Button variant="primary" kbd="Esc" onClick={close}>
          Weiterspielen
        </Button>
      </>
    )
  }

  return (
    <BoardModal
      label="Pause"
      title={state ? `Runde ${round} · Zug ${turn}` : 'Spiel pausiert'}
      width={layout.modal.pause}
      viewer
      onClose={close}
      footer={footer}
    >
      <div className="grid grid-cols-2 gap-7" style={{ padding: '16px 20px' }}>
        <div className="flex flex-col gap-3">
          <span className="label">Optionen</span>
          {!spectator && (
            <>
              <Toggle checked={autoMana} onChange={setAutoMana} label="Auto-Mana" title="Mana automatisch bezahlen" />
              <Toggle checked={autoPass} onChange={setAutoPass} label="Auto-Passen" title="Automatisch passen, wenn nichts spielbar ist" />
            </>
          )}
          <Toggle
            checked={fxEnabled}
            onChange={setFxEnabled}
            label="Effekte"
            title="Zonenwechsel, Schaden und Lebensänderungen einblenden (die Ereignisleiste bleibt)"
          />
          <Toggle checked={!muted} onChange={(on) => setMuted(!on)} label="Ton" />
          {!spectator && (
            <>
              <span className="label" style={{ marginTop: 8 }}>
                Kartengröße (Hand)
              </span>
              <Segmented variant="boxed" ariaLabel="Kartengröße" items={CARD_SIZE_ITEMS} value={cardSize} onChange={setCardSize} className="self-start" itemStyle={{ padding: '8px 12px' }} />
            </>
          )}
        </div>
        <div className="flex flex-col gap-3">
          {!spectator && (
            <>
              <span className="label">Bot-Tempo</span>
              <div className="flex flex-wrap items-center gap-2.5">
                <Segmented
                  variant="boxed"
                  ariaLabel="Bot-Tempo"
                  items={TEMPOS.map((t) => ({ id: t.key, label: t.label, title: `${t.desc} – ${t.title}` }))}
                  value={tempo}
                  onChange={setTempo}
                  disabled={!isHost}
                  itemStyle={{ padding: '8px 12px' }}
                />
                {!isHost && <span className="text-body-s text-fg-3">stellt der Gastgeber</span>}
              </div>
            </>
          )}
          <span className="label" style={spectator ? undefined : { marginTop: 8 }}>
            Verlauf
          </span>
          <Segmented variant="inline" ariaLabel="Verlauf" items={LOG_ITEMS} value={logFilter} onChange={setLogFilter} itemStyle={{ fontSize: 14 }} className="self-start" />
        </div>
      </div>
      {!spectator && !out && confirm && (
        <div
          className="flex items-center gap-2.5 rounded-sm bg-danger-bg text-[13.5px] text-fg-1"
          style={{ margin: '0 20px', padding: '12px 14px', boxShadow: 'inset 0 0 0 1px color-mix(in oklab, var(--color-attack) 40%, transparent)' }}
          role="alert"
        >
          <Icon name="warning" size={16} style={{ color: 'var(--color-attack)' }} />
          <span>
            Wirklich aufgeben? Die Partie zählt als Platz {place}.{otherHumans ? ' Die anderen spielen ohne dich weiter.' : ''}
          </span>
        </div>
      )}
      {out && (
        <div className="outline-panel text-[13.5px] text-fg-2" style={{ margin: '0 20px', padding: '12px 14px', borderRadius: 3 }}>
          {gameOver
            ? 'Das Spiel ist vorbei.'
            : conceded
              ? 'Du hast aufgegeben – die anderen spielen weiter. Dein Ergebnis landet in der Statistik.'
              : 'Du bist ausgeschieden – die anderen spielen weiter.'}
        </div>
      )}
    </BoardModal>
  )
}
