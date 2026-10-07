import { ManaSymbol } from '../lib/mana'

/** Manapool-Schluessel -> XMage-Manatyp fuer answer({mana}) */
const TYPE: Record<string, string> = { W: 'WHITE', U: 'BLUE', B: 'BLACK', R: 'RED', G: 'GREEN', C: 'COLORLESS' }
const ORDER = ['W', 'U', 'B', 'R', 'G', 'C']

export interface ManaPoolProps {
  mana?: Record<string, number>
  /** Mana-Modus: Klick zahlt mit dieser Farbe (XMage-Typ, z. B. "RED") */
  onPick?: (type: string) => void
  /** pod: Zonenzeile (14 px) · info: Infospalte mit Label (16 px) */
  variant: 'pod' | 'info'
}

/** Manapool (im Design nicht gezeichnet): flache Manasymbole + Anzahl; im Mana-Modus Ember-Kontur und anklickbar. */
export function ManaPool({ mana, onPick, variant }: ManaPoolProps) {
  const list = Object.entries(mana ?? {})
    .filter(([, v]) => v > 0)
    .sort((a, b) => ORDER.indexOf(a[0]) - ORDER.indexOf(b[0]))
  if (list.length === 0) return null
  const pod = variant === 'pod'
  const items = list.map(([k, v]) => {
    const type = TYPE[k] ?? k
    return (
      <button
        key={k}
        type="button"
        disabled={!onPick}
        title={onPick ? `Mit {${k}} aus dem Manapool zahlen` : `{${k}} im Manapool`}
        onClick={(e) => {
          e.stopPropagation()
          onPick?.(type)
        }}
        className={`flex items-center gap-1 rounded-xs font-display font-semibold leading-none tabular-nums text-fg-1 disabled:cursor-default ${
          onPick ? 'cursor-pointer px-1 py-0.5 shadow-[inset_0_0_0_1px_var(--color-ember)] hover:bg-bg-4' : ''
        }`}
        style={{ fontSize: pod ? 14 : 16 }}
      >
        <ManaSymbol sym={k} flat size="sm" />
        {v}
      </button>
    )
  })
  if (pod) {
    return (
      <span className="flex shrink-0 items-center gap-2" title="Manapool">
        {items}
      </span>
    )
  }
  return (
    <div className="flex shrink-0 flex-col gap-1.5">
      <span className="label" style={{ fontSize: 12, letterSpacing: '.14em' }}>
        Manapool
      </span>
      <div className="flex flex-wrap items-center gap-2">{items}</div>
    </div>
  )
}
