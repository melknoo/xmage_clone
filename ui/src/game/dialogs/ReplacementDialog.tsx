// Ersatzeffekt waehlen: Gruppen (gleicher Regeltext) mit Quellen-Chips, Optionszeilen je Gruppe + "Keinen anwenden",
// erst auswaehlen, dann "Bestaetigen" (Space). Ein Klick auf einen Quellen-Chip wendet den Effekt dieser Karte sofort an.
import { useState } from 'react'
import type { ReplGroup } from '../../api/types'
import { BoardModal } from '../../components/BoardModal'
import { CardView } from '../../components/CardView'
import { Button, Checkbox, OptionRow } from '../../components/ui'
import { withSymbols } from '../../lib/mana'
import { useGame } from '../../store/game'
import { EngineTitle, useDigitKeys, type DialogProps } from './shared'

/** Gruppe per Optionszeile annehmbar: eine Quelle, oder alle Quellen gleichnamig (Engine 'acceptGroup') */
const groupAcceptable = (g: ReplGroup) => g.sources.length === 1 || (!!g.uniform && g.sources.length > 0)

function groupLabel(g: ReplGroup) {
  const n = g.sources.length
  return [g.label, `${n} ${n === 1 ? 'Karte' : 'Karten'}`, g.cause].filter(Boolean).join(' · ')
}

export function ReplacementDialog({ p, onHover, layout }: DialogProps) {
  const answer = useGame((s) => s.answer)
  const replacement = useGame((s) => s.replacement)
  const objects = useGame((s) => s.objects)
  const groups = p.choice?.groups ?? []
  const anyOptional = groups.some((g) => g.optional)
  const rowCount = groups.length + (anyOptional ? 1 : 0)
  const declineIdx = anyOptional ? groups.length : -1
  const [sel, setSel] = useState(() => {
    const first = groups.findIndex(groupAcceptable)
    return first >= 0 ? first : Math.max(0, declineIdx)
  })
  const [remember, setRemember] = useState(false)
  useDigitKeys(rowCount, setSel)

  /** einzelne Quelle anwenden (wie bisher der Chip-Klick) */
  const pickSource = (g: ReplGroup, key: string) => (g.optional ? replacement('accept', key) : answer({ str: key }))
  const canConfirm = sel === declineIdx || (!!groups[sel] && groupAcceptable(groups[sel]))
  const confirm = (i: number = sel) => {
    if (i === declineIdx) return replacement('decline', undefined, remember)
    const g = groups[i]
    if (!g || !groupAcceptable(g)) return
    if (g.sources.length === 1) pickSource(g, g.sources[0].key)
    else replacement('acceptGroup', g.rule)
  }

  return (
    <BoardModal
      label="Ersatzeffekt wählen"
      title={p.choice?.message ?? <EngineTitle p={p} />}
      width={layout.modal.replace}
      minimizable
      closable={false}
      onSpace={() => canConfirm && confirm()}
      footer={
        <Button variant="primary" kbd="Space" testId="repl-confirm" disabled={!canConfirm} onClick={() => confirm()}>
          Bestätigen
        </Button>
      }
    >
      <div className="flex flex-col gap-3" style={{ padding: '14px 20px 4px' }}>
        {groups.map((g) => (
          <div key={g.rule} className="outline-panel flex flex-col gap-2.5 p-3" style={{ borderRadius: 3 }}>
            <span className="label">{groupLabel(g)}</span>
            <div className="flex flex-wrap gap-2.5">
              {g.sources.map((src) => {
                const card = src.objectId ? objects.get(src.objectId) : undefined
                return (
                  <button
                    key={src.key}
                    type="button"
                    className="flex items-center gap-2 rounded-sm bg-bg-4 text-[13px] font-semibold text-fg-1 transition-colors duration-1 hover:bg-line-3"
                    style={{ padding: card ? '4px 10px 4px 4px' : '6px 10px' }}
                    title={`Effekt von ${src.name} sofort anwenden`}
                    onMouseEnter={() => card && onHover(card)}
                    onMouseLeave={() => onHover(null)}
                    onClick={() => pickSource(g, src.key)}
                  >
                    {card && <CardView card={card} width={30} anchor={false} />}
                    {src.name}
                  </button>
                )
              })}
            </div>
          </div>
        ))}
        <div role="listbox" className="contents">
          {groups.map((g, i) => {
            const ok = groupAcceptable(g)
            return (
              <OptionRow
                key={g.rule}
                index={i < 9 ? i + 1 : undefined}
                selected={sel === i}
                disabled={!ok}
                title={ok ? undefined : 'Verschiedene Karten – oben die Karte anklicken, deren Effekt gelten soll'}
                onClick={() => setSel(i)}
                onDoubleClick={() => confirm(i)}
              >
                {withSymbols(g.rule)}
              </OptionRow>
            )
          })}
          {anyOptional && (
            <OptionRow
              index={declineIdx < 9 ? declineIdx + 1 : undefined}
              selected={sel === declineIdx}
              testId="repl-decline-all"
              title="Alle optionalen Ersatzeffekte ablehnen"
              onClick={() => setSel(declineIdx)}
              onDoubleClick={() => confirm(declineIdx)}
            >
              Keinen anwenden
            </OptionRow>
          )}
        </div>
        {anyOptional && (
          <Checkbox
            checked={remember}
            onChange={setRemember}
            label="Für dieses Spiel merken"
            testId="repl-remember"
            title="Bei „Keinen anwenden“: diese Effekte ab jetzt ohne Nachfrage ablehnen (Knopf oben in der Leiste setzt zurück)"
            className="py-0.5"
          />
        )}
      </div>
    </BoardModal>
  )
}
