import type { Tempo } from '../api/types'
import { Avatar, Chip, Wordmark } from '../components/ui'
import { Icon } from '../lib/icons'
import { TEMPOS, tempoLabel } from '../lib/tempo'
import { useGame } from '../store/game'
import { useNav } from '../store/nav'
import { commanderArt, shortName } from './format'
import type { BoardLayoutState } from './layout'
import { PhaseBar } from './PhaseBar'

export interface TopBarProps {
  /** hdr, compact (PhaseBar-Gruppen), showInlineTempo, manaLbl/passLbl */
  layout: BoardLayoutState
}

/** Schalter in der Kopfleiste (Barlow 600 13, an fg-1, aus fg-4) */
const HDR_TOGGLE = 'flex items-center gap-[5px] uppercase transition-colors duration-1 hover:text-fg-1'
/** Knopf "Menü" (h30, Kontur line-4) */
const HDR_MENU =
  'flex h-[30px] items-center gap-1.5 rounded-sm px-[9px] uppercase text-fg-1 shadow-[inset_0_0_0_1px_var(--color-line-4)] transition-colors duration-1 hover:bg-bg-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-fg-1'

/**
 * Kopfleiste: Wortmarke, Runde, Zug-Chip, Sitz-Avatare in Zugreihenfolge, PhaseBar (mittig), Tempo (inline ab 1600 px),
 * Auto-Mana/-Passen, abgelehnte Ersatzeffekte, Ton, Menue. Zuschauer: Chip "Zuschauer" statt der Schalter.
 */
export function TopBar({ layout }: TopBarProps) {
  const state = useGame((s) => s.state)!
  const tempo = useGame((s) => s.tempo)
  const setTempo = useGame((s) => s.setTempo)
  const setMenuOpen = useGame((s) => s.setMenuOpen)
  const gameOver = useGame((s) => s.gameOver)
  const reset = useGame((s) => s.reset)
  const go = useNav((s) => s.go)
  const muted = useGame((s) => s.muted)
  const setMuted = useGame((s) => s.setMuted)
  const autoMana = useGame((s) => s.autoMana)
  const setAutoMana = useGame((s) => s.setAutoMana)
  const autoPass = useGame((s) => s.autoPass)
  const setAutoPass = useGame((s) => s.setAutoPass)
  const isHost = useGame((s) => s.hello?.host !== false)
  const spectator = useGame((s) => s.spectator)
  const spectators = useGame((s) => s.spectators)
  const resetReplDeclines = useGame((s) => s.resetReplDeclines)
  const replDeclines = state.replDeclines

  const round = Math.max(1, Math.ceil(state.turn / Math.max(1, state.players.length)))
  const active = state.players.find((p) => p.id === state.activePlayerId)
  const turnText = gameOver ? 'Ende' : active?.me && !spectator ? 'Dein Zug' : active ? `${shortName(active.name)} am Zug` : null

  return (
    <header className="flex shrink-0 items-center gap-4 border-b border-line-2 px-[14px]" style={{ height: layout.hdr + 1 }} data-testid="game-header">
      <Wordmark size={19} className="shrink-0" />
      <div className="flex shrink-0 items-center gap-2">
        <span className="whitespace-nowrap font-display text-[13px] font-semibold uppercase leading-none tracking-[.1em] text-fg-3">
          Runde <span className="text-fg-1">{round}</span>
        </span>
        {turnText && (
          <Chip tone="turn" size="hdr" title={active?.name}>
            {turnText}
          </Chip>
        )}
        <div className="flex gap-[3px]">
          {state.players.map((p) => (
            <Avatar key={p.id} src={commanderArt(p)} name={p.me && !spectator ? 'Du' : p.name} title={p.me && !spectator ? 'Du' : p.name} size={20} active={p.id === state.activePlayerId && !gameOver} dim />
          ))}
        </div>
      </div>

      <div className="flex min-w-0 flex-1 justify-center">
        <PhaseBar state={state} layout={layout} />
      </div>

      <div className="flex shrink-0 items-center gap-3 whitespace-nowrap font-display text-[13px] font-semibold leading-none tracking-[.08em]">
        {spectator ? (
          <Chip tone="outline" size="hdr" icon="spectate">
            Zuschauer
          </Chip>
        ) : (
          <>
            {layout.showInlineTempo && (
              <>
                <TempoInline tempo={tempo} isHost={isHost} onChange={setTempo} />
                <span className="h-[22px] w-px bg-line-3" aria-hidden />
              </>
            )}
            <button
              type="button"
              className={HDR_TOGGLE}
              style={{ color: autoMana ? 'var(--color-fg-1)' : 'var(--color-fg-4)' }}
              title="Mana automatisch bezahlen"
              aria-pressed={autoMana}
              onClick={() => setAutoMana(!autoMana)}
            >
              <Icon name="autoMana" size={14} style={{ color: autoMana ? 'var(--color-ember)' : 'var(--color-fg-4)' }} />
              {layout.manaLbl}
            </button>
            <button
              type="button"
              className={HDR_TOGGLE}
              style={{ color: autoPass ? 'var(--color-fg-1)' : 'var(--color-fg-4)' }}
              title="Automatisch passen, wenn nichts spielbar ist (aus = Gegner sieht nicht, ob du Optionen hast)"
              aria-pressed={autoPass}
              onClick={() => setAutoPass(!autoPass)}
            >
              <Icon name="autoPass" size={14} style={{ color: autoPass ? 'var(--color-ember)' : 'var(--color-fg-4)' }} />
              {layout.passLbl}
            </button>
            {replDeclines && replDeclines.length > 0 && (
              <Chip tone="filter" title={`Automatisch abgelehnt: ${replDeclines.join(', ')} – X setzt zurück`} onRemove={resetReplDeclines} removeLabel="Abgelehnte Ersatzeffekte zurücksetzen">
                {replDeclines[0]} · abgelehnt{replDeclines.length > 1 ? ` +${replDeclines.length - 1}` : ''}
              </Chip>
            )}
            {spectators.length > 0 && (
              <span className="flex items-center gap-1 text-fg-3" title={`Zuschauer: ${spectators.join(', ')}`}>
                <Icon name="spectate" size={14} />
                {spectators.length}
              </span>
            )}
            <button type="button" className="flex text-fg-3 transition-colors duration-1 hover:text-fg-1" title={muted ? 'Ton an' : 'Ton aus'} aria-label={muted ? 'Ton an' : 'Ton aus'} onClick={() => setMuted(!muted)}>
              <Icon name={muted ? 'muted' : 'sound'} size={16} />
            </button>
            {gameOver ? (
              <button
                type="button"
                className={HDR_MENU}
                title="Zurück zur Startseite"
                onClick={() => {
                  reset()
                  go('home')
                }}
              >
                <Icon name="home" size={14} />
                Zum Start
              </button>
            ) : (
              <button type="button" className={HDR_MENU} title="Pausemenü: Optionen, Aufgeben, Spiel verlassen (Esc)" onClick={() => setMenuOpen(true)}>
                <Icon name="menu" size={14} />
                Menü
                <kbd className="font-mono text-[10px] font-medium normal-case leading-none tracking-normal text-fg-3">Esc</kbd>
              </button>
            )}
          </>
        )}
      </div>
    </header>
  )
}

/** Bot-Tempo inline (Gastgeber); sonst nur lesend */
function TempoInline({ tempo, isHost, onChange }: { tempo: Tempo; isHost: boolean; onChange: (t: Tempo) => void }) {
  if (!isHost) {
    return (
      <span className="uppercase text-fg-tab" title="Das Tempo stellt der Gastgeber">
        Tempo <span className="text-fg-2">{tempoLabel(tempo)}</span>
      </span>
    )
  }
  return (
    <div role="radiogroup" aria-label="Bot-Tempo" className="seg-inline" style={{ gap: 11 }}>
      {TEMPOS.map((t) => (
        <button key={t.key} type="button" role="radio" aria-checked={tempo === t.key} className="seg-inline-item" title={`${t.desc} – ${t.title}`} onClick={() => tempo !== t.key && onChange(t.key)}>
          {t.label}
        </button>
      ))}
    </div>
  )
}
