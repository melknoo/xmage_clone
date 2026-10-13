import { Icon, type IconName } from '../lib/icons'
import { useGame } from '../store/game'
import type { BoardLayoutState } from './layout'

/** Schalter in der Aktionsleiste (Barlow 600 12, an fg-1, aus fg-4) */
const TOGGLE = 'flex items-center gap-[5px] uppercase transition-colors duration-1 hover:text-fg-1 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-fg-1'

/**
 * Auto-Mana / Auto-Passen in der Aktionsleiste neben den Passen-Knoepfen (F5/F4/F9), untereinander (spart Breite fuer
 * den Engine-Text), mit Trennstrich danach. Gleicher Zustand wie im Pausemenue (store/game: localStorage + settings an
 * die Engine).
 */
export function PlayToggles({ layout }: { layout: BoardLayoutState }) {
  const autoMana = useGame((s) => s.autoMana)
  const setAutoMana = useGame((s) => s.setAutoMana)
  const autoPass = useGame((s) => s.autoPass)
  const setAutoPass = useGame((s) => s.setAutoPass)
  return (
    <div className="flex shrink-0 items-center gap-3">
      <div className="flex flex-col items-start gap-[7px] whitespace-nowrap font-display text-[12px] font-semibold leading-none tracking-[.08em]">
        <ToggleBtn on={autoMana} icon="autoMana" label={layout.manaLbl} title="Mana automatisch bezahlen" testId="toggle-auto-mana" onChange={setAutoMana} />
        <ToggleBtn
          on={autoPass}
          icon="autoPass"
          label={layout.passLbl}
          title="Automatisch passen, wenn nichts spielbar ist (aus = Gegner sieht nicht, ob du Optionen hast)"
          testId="toggle-auto-pass"
          onChange={setAutoPass}
        />
      </div>
      <span className="h-[22px] w-px bg-line-3" aria-hidden />
    </div>
  )
}

interface ToggleBtnProps {
  on: boolean
  icon: IconName
  label: string
  title: string
  testId: string
  onChange: (on: boolean) => void
}

function ToggleBtn({ on, icon, label, title, testId, onChange }: ToggleBtnProps) {
  return (
    <button type="button" className={TOGGLE} style={{ color: on ? 'var(--color-fg-1)' : 'var(--color-fg-4)' }} title={title} aria-pressed={on} data-testid={testId} onClick={() => onChange(!on)}>
      <Icon name={icon} size={13} style={{ color: on ? 'var(--color-ember)' : 'var(--color-fg-4)' }} />
      {label}
    </button>
  )
}
