import type { Tempo } from '../../api/types'
import { Icon } from '../../lib/icons'
import { TEMPOS } from '../../lib/tempo'

/** Bot-Tempo als vier Optionskarten (aktiv: Ember-Kontur + Toenung + Haken); Langtext im Tooltip. */
export function TempoCards({ value, onChange }: { value: Tempo; onChange: (t: Tempo) => void }) {
  return (
    <div role="radiogroup" aria-label="Bot-Tempo" className="grid grid-cols-4 gap-2.5">
      {TEMPOS.map((t) => {
        const sel = t.key === value
        return (
          <button
            key={t.key}
            type="button"
            role="radio"
            aria-checked={sel}
            title={t.title}
            data-tempo={t.key}
            className={`flex min-w-0 flex-col gap-1.5 rounded-sm px-4 py-3.5 text-left transition-colors duration-1 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-fg-1 ${sel ? 'bg-ember/8 shadow-[inset_0_0_0_1px_var(--color-ember)]' : 'bg-bg-3 hover:bg-bg-4'}`}
            onClick={() => onChange(t.key)}
          >
            <span className={`flex items-center justify-between gap-2 font-display text-[20px] font-semibold uppercase leading-none tracking-[.06em] ${sel ? 'text-ember' : 'text-fg-1'}`}>
              {t.label}
              {sel && <Icon name="chosen" size={16} />}
            </span>
            <span className="truncate text-[12.5px] text-fg-3">{t.desc}</span>
          </button>
        )
      })}
    </div>
  )
}
