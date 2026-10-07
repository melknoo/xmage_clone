// Uebrige Brett-Dialoge: Auswahl (Text/Kartennamen/Farbe), Anzahl, Anzahl verteilen, Stapel, Karte waehlen.
import { useDeferredValue, useEffect, useMemo, useRef, useState } from 'react'
import type { Card } from '../../api/types'
import { BoardModal } from '../../components/BoardModal'
import { CardView } from '../../components/CardView'
import { Button, OptionRow, TextField } from '../../components/ui'
import { Icon } from '../../lib/icons'
import { ManaSymbol, withSymbols } from '../../lib/mana'
import { useGame } from '../../store/game'
import type { Interaction } from '../interaction'
import { EngineTitle, type DialogProps } from './shared'

/* ---------------------------------------------------------------- Auswahl */

const MANA_WORD: Record<string, string> = { White: 'W', Blue: 'U', Black: 'B', Red: 'R', Green: 'G', Colorless: 'C' }

function ManaWord({ v }: { v: string }) {
  const k = MANA_WORD[v]
  return (
    <span className="flex items-center gap-2">
      {k && <ManaSymbol sym={k} flat />}
      {v}
    </span>
  )
}

const SHOW_MAX = 300

export function ChoiceDialog({ p, onHover, layout }: DialogProps) {
  const answer = useGame((s) => s.answer)
  const [q, setQ] = useState('')
  const dq = useDeferredValue(q)
  const ch = p.choice
  // einmal sortieren (Kartennamen-Wahl hat ~30k Eintraege), Filter getrennt und verzoegert
  const sorted = useMemo(() => {
    const all = (ch?.items ?? []).map((i) => ({ ...i, lower: i.value.toLowerCase() }))
    all.sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0) || a.value.localeCompare(b.value))
    return all
  }, [ch])
  const items = useMemo(() => {
    const ql = dq.trim().toLowerCase()
    return ql ? sorted.filter((i) => i.lower.includes(ql)) : sorted
  }, [sorted, dq])
  const many = (ch?.items?.length ?? 0) > 12
  // Kartennamen: Bildvorschau per Name (Engine /img/named), leicht verzoegert gegen Flackern beim Scrollen
  const cardHint = ch?.hint === 'card'
  const hoverTimer = useRef<number | null>(null)
  const hoverName = (name: string | null) => {
    if (!cardHint) return
    if (hoverTimer.current) window.clearTimeout(hoverTimer.current)
    if (!name) {
      onHover(null)
      return
    }
    hoverTimer.current = window.setTimeout(() => onHover({ id: 'name:' + name, name } as Card), 120)
  }
  useEffect(
    () => () => {
      if (hoverTimer.current) window.clearTimeout(hoverTimer.current)
      if (cardHint) onHover(null)
    },
    [cardHint, onHover],
  )
  const cancel = () => answer({ str: '' })
  return (
    <BoardModal
      label="Auswahl"
      title={ch?.message ?? <EngineTitle p={p} />}
      width={many ? layout.modal.grave : layout.modal.default}
      minimizable
      closable={!ch?.required}
      onClose={cancel}
      footer={
        !ch?.required ? (
          <Button variant="ghost" kbd="Esc" onClick={cancel}>
            Abbrechen
          </Button>
        ) : undefined
      }
    >
      <div className="flex flex-col gap-2 p-3">
        {ch?.subMessage && <div className="px-1 text-body-s text-fg-3">{ch.subMessage}</div>}
        {many && <TextField autoFocus icon="search" placeholder="Suchen …" value={q} onChange={(e) => setQ(e.target.value)} />}
        <div role="listbox" className={many ? 'grid max-h-[50vh] grid-cols-2 gap-1 overflow-auto scrollbar-thin' : 'flex flex-col gap-1'}>
          {items.slice(0, SHOW_MAX).map((i) => (
            <OptionRow
              key={i.key}
              selected={false}
              onClick={() => answer({ str: ch?.keyed ? i.key : i.value })}
              onMouseEnter={() => hoverName(i.value)}
              onMouseLeave={() => hoverName(null)}
            >
              {ch?.manaColor ? <ManaWord v={i.value} /> : withSymbols(i.value)}
            </OptionRow>
          ))}
          {items.length > SHOW_MAX && <div className="col-span-2 p-2 text-body-s text-fg-3">… {items.length - SHOW_MAX} weitere – Suche verfeinern</div>}
          {items.length === 0 && <div className="col-span-2 p-2 text-body-s text-fg-3">Kein Treffer</div>}
        </div>
      </div>
    </BoardModal>
  )
}

/* ---------------------------------------------------------------- Anzahl */

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))

export function AmountDialog({ p, layout }: DialogProps) {
  const answer = useGame((s) => s.answer)
  const min = p.min ?? 0
  const max = p.max ?? 0
  const [v, setV] = useState(min)
  const ok = () => answer({ int: v })
  return (
    <BoardModal
      label="Anzahl wählen"
      title={<EngineTitle p={p} />}
      width={layout.modal.default}
      minimizable
      closable={false}
      onSpace={ok}
      footer={
        <Button variant="primary" kbd="Space" onClick={ok}>
          OK ({v})
        </Button>
      }
    >
      <div className="flex flex-col gap-2" style={{ padding: '18px 20px 4px' }}>
        <div className="flex items-center gap-3">
          <button type="button" className="btn-icon" title="Weniger" aria-label="Weniger" disabled={v <= min} onClick={() => setV(clamp(v - 1, min, max))}>
            <Icon name="minus" size={16} />
          </button>
          <input
            type="range"
            min={min}
            max={Math.min(max, 1000)}
            value={v}
            onChange={(e) => setV(Number(e.target.value))}
            className="min-w-0 flex-1"
            style={{ accentColor: 'var(--color-ember)' }}
          />
          <button type="button" className="btn-icon" title="Mehr" aria-label="Mehr" disabled={v >= max} onClick={() => setV(clamp(v + 1, min, max))}>
            <Icon name="plus" size={16} />
          </button>
          <input
            type="number"
            className="field num w-20 text-center"
            value={v}
            min={min}
            max={max}
            onChange={(e) => setV(clamp(Number(e.target.value) || 0, min, max))}
          />
        </div>
        <div className="text-body-s text-fg-3">
          Bereich {min} – {max}
        </div>
      </div>
    </BoardModal>
  )
}

/* ---------------------------------------------------------------- Anzahl verteilen */

export function MultiAmountDialog({ p, layout }: DialogProps) {
  const answer = useGame((s) => s.answer)
  const [vals, setVals] = useState(() => (p.items ?? []).map((i) => i.value))
  const total = vals.reduce((a, b) => a + b, 0)
  const valid = total >= (p.min ?? 0) && total <= (p.max ?? 0)
  const ok = () => valid && answer({ str: vals.join(' ') })
  return (
    <BoardModal
      label="Anzahl verteilen"
      title={<EngineTitle p={p} />}
      width={layout.modal.default}
      minimizable
      closable={false}
      onSpace={ok}
      footer={
        <Button variant="primary" kbd="Space" disabled={!valid} onClick={ok}>
          OK (Summe {total})
        </Button>
      }
    >
      <div className="flex flex-col gap-2" style={{ padding: '14px 20px 4px' }}>
        {(p.items ?? []).map((it, i) => (
          <div key={i} className="flex items-center gap-3 border-b border-line-1 pb-2">
            <div className="min-w-0 flex-1 text-body text-fg-2">{withSymbols(it.message)}</div>
            <input
              type="number"
              className="field num w-20 text-center"
              value={vals[i]}
              min={it.min}
              max={it.max}
              onChange={(e) => {
                const n = clamp(Number(e.target.value) || 0, it.min, it.max)
                setVals(vals.map((x, j) => (j === i ? n : x)))
              }}
            />
          </div>
        ))}
        <div className="text-body-s" style={{ color: valid ? 'var(--color-fg-3)' : 'var(--color-attack)' }}>
          Summe muss zwischen {p.min} und {p.max} liegen.
        </div>
      </div>
    </BoardModal>
  )
}

/* ---------------------------------------------------------------- Stapel */

export function PileDialog({ p, onHover, layout }: DialogProps) {
  const answer = useGame((s) => s.answer)
  return (
    <BoardModal label="Stapel wählen" title={<EngineTitle p={p} />} width={layout.modal.grave} minimizable closable={false}>
      <div className="grid grid-cols-2 gap-4" style={{ padding: '18px 20px 20px' }}>
        {[p.pile1 ?? [], p.pile2 ?? []].map((pile, i) => (
          <div key={i} className="outline-panel flex flex-col gap-3 p-3">
            <span className="label">
              Stapel {i + 1} · {pile.length} {pile.length === 1 ? 'Karte' : 'Karten'}
            </span>
            <div className="flex min-h-[60px] flex-wrap gap-2">
              {pile.map((c) => (
                <CardView key={c.id} card={c} width={layout.compact ? 70 : 86} onHover={onHover} />
              ))}
              {pile.length === 0 && <div className="text-body-s text-fg-3">leer</div>}
            </div>
            <Button variant="secondary" className="mt-auto w-full" onClick={() => answer({ bool: i === 0 })}>
              Stapel {i + 1} wählen
            </Button>
          </div>
        ))}
      </div>
    </BoardModal>
  )
}

/* ---------------------------------------------------------------- Karte waehlen */

export function CardPickDialog({ p, inter, onHover, layout }: DialogProps & { inter: Interaction }) {
  const answer = useGame((s) => s.answer)
  const canFinish = !!p.rightBtn || !p.required
  const finish = () => answer({ bool: false })
  return (
    <BoardModal
      label="Karte wählen"
      title={<EngineTitle p={p} />}
      width={layout.modal.grave}
      minimizable
      closable={false}
      onSpace={canFinish ? finish : undefined}
      footer={
        canFinish ? (
          <Button variant="primary" kbd="Space" onClick={finish}>
            {p.chosen?.length ? 'Fertig' : 'Keine wählen'}
          </Button>
        ) : undefined
      }
    >
      <div className="flex flex-wrap gap-3.5" style={{ padding: '18px 20px' }}>
        {inter.modalCards.map((c) => (
          <div key={c.id} className="flex flex-col gap-[7px]" style={{ width: layout.graveW }}>
            <CardView card={c} width={layout.graveW} highlight={inter.highlight(c.id)} onHover={onHover} onClick={() => inter.click(c.id)} dim={!inter.canClick(c.id)} />
            <span className="truncate text-[12px] text-fg-2">{c.name}</span>
          </div>
        ))}
      </div>
    </BoardModal>
  )
}
