// Faehigkeit/Modus waehlen: erst auswaehlen (Klick, Ziffer 1-9), dann "Aktivieren" (Space). Doppelklick aktiviert sofort.
import { useState } from 'react'
import { BoardModal } from '../../components/BoardModal'
import { Button, OptionRow, Segmented } from '../../components/ui'
import { withSymbols } from '../../lib/mana'
import { useGame } from '../../store/game'
import { EngineTitle, useDigitKeys, type DialogProps } from './shared'

const REPEAT_PRESETS = ['1', '3', '5', '10'] as const
type RepeatKey = (typeof REPEAT_PRESETS)[number]

export function AbilityDialog({ p, layout }: DialogProps) {
  const answer = useGame((s) => s.answer)
  const repeat = useGame((s) => s.repeat)
  const sourceName = useGame((s) => (p.sourceId ? s.objects.get(p.sourceId)?.name : undefined))
  const choices = p.choices ?? []
  const [sel, setSel] = useState(0)
  const [times, setTimes] = useState<RepeatKey>('1')
  // "N-mal aktivieren" nur fuer aktivierte Faehigkeiten eines Objekts (z. B. Necropotence "Pay 1 life")
  const repeatable = p.kind === 'CHOOSE_ABILITY' && !!p.sourceId
  const n = Number(times)
  const activate = (i: number) => {
    const c = choices[i]
    if (!c) return
    if (repeatable && n > 1) repeat(c.id, n)
    else answer({ uuid: c.id })
  }
  useDigitKeys(choices.length, setSel)
  const cancel = () => answer({ bool: false })

  return (
    <BoardModal
      label={p.kind === 'CHOOSE_MODE' ? 'Modus wählen' : 'Fähigkeit wählen'}
      title={
        <>
          <EngineTitle p={p} />
          {sourceName && <> · {sourceName}</>}
        </>
      }
      width={layout.modal.ability}
      minimizable
      closable={!p.required}
      onClose={cancel}
      onSpace={() => activate(sel)}
      footer={
        <>
          {!p.required && (
            <Button variant="ghost" kbd="Esc" onClick={cancel}>
              Abbrechen
            </Button>
          )}
          <Button variant="primary" kbd="Space" testId="ability-activate" disabled={!choices[sel]} onClick={() => activate(sel)}>
            Aktivieren
          </Button>
        </>
      }
    >
      <div role="listbox" className="flex flex-col gap-1 p-3">
        {choices.map((c, i) => (
          <OptionRow key={c.id} index={i < 9 ? i + 1 : undefined} selected={sel === i} onClick={() => setSel(i)} onDoubleClick={() => activate(i)}>
            {withSymbols(c.text)}
          </OptionRow>
        ))}
      </div>
      {repeatable && (
        <div className="flex items-center gap-2" style={{ padding: '4px 20px 0' }} title="Die Engine hält zwischen den Aktivierungen die Priorität; Ziele oder Fragen stoppen die Wiederholung">
          <span className="label">Wiederholen</span>
          <Segmented
            variant="boxed"
            ariaLabel="Wiederholen"
            items={REPEAT_PRESETS.map((k) => ({ id: k, label: `×${k}` }))}
            value={times}
            onChange={setTimes}
            itemStyle={{ padding: '6px 10px' }}
          />
        </div>
      )}
    </BoardModal>
  )
}
