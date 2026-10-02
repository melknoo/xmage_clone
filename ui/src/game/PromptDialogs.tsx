import { useMemo, useState } from 'react'
import type { Card, Prompt } from '../api/types'
import { CardView } from '../components/CardView'
import { Modal } from '../components/Modal'
import { Rich, withSymbols } from '../lib/mana'
import { useGame } from '../store/game'
import type { Interaction } from './interaction'

export function PromptDialogs({ inter, onHover }: { inter: Interaction; onHover: (c: Card | null) => void }) {
  const p = inter.prompt
  if (!p) return null
  // key: jeder neue Prompt startet wieder aufgeklappt
  return <PromptDialog key={p.id} p={p} inter={inter} onHover={onHover} />
}

function PromptDialog({ p, inter, onHover }: { p: Prompt; inter: Interaction; onHover: (c: Card | null) => void }) {
  switch (p.kind) {
    case 'CHOOSE_ABILITY':
    case 'CHOOSE_MODE':
    case 'PICK_ABILITY':
      return <ChoiceListDialog p={p} />
    case 'CHOOSE_CHOICE':
      return <ChoiceDialog p={p} />
    case 'AMOUNT':
      return <AmountDialog p={p} />
    case 'MULTI_AMOUNT':
      return <MultiAmountDialog p={p} />
    case 'CHOOSE_PILE':
      return <PileDialog p={p} onHover={onHover} />
    case 'ASK':
      return p.mulligan ? <MulliganDialog p={p} onHover={onHover} /> : null
    case 'PICK_TARGET':
      return inter.needsCardModal ? <CardPickDialog p={p} inter={inter} onHover={onHover} /> : null
    default:
      return null
  }
}

function Title({ p }: { p: Prompt }) {
  return <Rich segs={p.message} />
}

function ChoiceListDialog({ p }: { p: Prompt }) {
  const answer = useGame((s) => s.answer)
  return (
    <Modal minimizable title={<Title p={p} />} onClose={() => answer({ bool: false })} closable={!p.required}>
      <div className="flex flex-col gap-2">
        {(p.choices ?? []).map((c) => (
          <button key={c.id} className="rounded-xl bg-ink-800/80 px-4 py-3 text-left text-sm ring-1 ring-white/10 transition hover:bg-ink-700 hover:ring-gold-400/50" onClick={() => answer({ uuid: c.id })}>
            {withSymbols(c.text)}
          </button>
        ))}
      </div>
    </Modal>
  )
}

function ChoiceDialog({ p }: { p: Prompt }) {
  const answer = useGame((s) => s.answer)
  const [q, setQ] = useState('')
  const ch = p.choice
  const items = useMemo(() => {
    const all = [...(ch?.items ?? [])]
    all.sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0) || a.value.localeCompare(b.value))
    const ql = q.trim().toLowerCase()
    return ql ? all.filter((i) => i.value.toLowerCase().includes(ql)) : all
  }, [ch, q])
  const many = (ch?.items?.length ?? 0) > 12
  return (
    <Modal
      minimizable
      title={ch?.message ?? <Title p={p} />}
      onClose={() => answer({ str: '' })}
      closable={!ch?.required}
      footer={!ch?.required ? <button className="btn-ghost" onClick={() => answer({ str: '' })}>Abbrechen</button> : undefined}
    >
      {ch?.subMessage && <div className="mb-3 text-sm text-ink-300">{ch.subMessage}</div>}
      {many && (
        <input autoFocus className="mb-3 w-full rounded-lg bg-ink-950/70 px-3 py-2 text-sm ring-1 ring-white/15 outline-none focus:ring-gold-400/60" placeholder="Suchen…" value={q} onChange={(e) => setQ(e.target.value)} />
      )}
      <div className={many ? 'grid max-h-[50vh] grid-cols-2 gap-1.5 overflow-auto scrollbar-thin' : 'flex flex-col gap-2'}>
        {items.slice(0, 300).map((i) => (
          <button key={i.key} className="rounded-lg bg-ink-800/80 px-3 py-2 text-left text-sm ring-1 ring-white/10 hover:bg-ink-700 hover:ring-gold-400/50" onClick={() => answer({ str: ch?.keyed ? i.key : i.value })}>
            {ch?.manaColor ? <ManaWord v={i.value} /> : withSymbols(i.value)}
          </button>
        ))}
        {items.length > 300 && <div className="col-span-2 p-2 text-xs text-ink-400">… {items.length - 300} weitere – Suche verfeinern</div>}
      </div>
    </Modal>
  )
}

function ManaWord({ v }: { v: string }) {
  const map: Record<string, string> = { White: 'w', Blue: 'u', Black: 'b', Red: 'r', Green: 'g', Colorless: 'c' }
  const k = map[v]
  return (
    <span className="flex items-center gap-2">
      {k && <i className={`ms ms-${k} ms-cost ms-shadow`} />}
      {v}
    </span>
  )
}

function AmountDialog({ p }: { p: Prompt }) {
  const answer = useGame((s) => s.answer)
  const min = p.min ?? 0
  const max = p.max ?? 0
  const [v, setV] = useState(min)
  return (
    <Modal minimizable title={<Title p={p} />} closable={false} footer={<button className="btn-primary" onClick={() => answer({ int: v })}>OK ({v})</button>}>
      <div className="flex items-center gap-4">
        <button className="btn-ghost" onClick={() => setV(Math.max(min, v - 1))}>−</button>
        <input type="range" min={min} max={Math.min(max, 1000)} value={v} onChange={(e) => setV(Number(e.target.value))} className="flex-1 accent-amber-400" />
        <button className="btn-ghost" onClick={() => setV(Math.min(max, v + 1))}>+</button>
        <input type="number" className="w-20 rounded-lg bg-ink-950/70 px-2 py-1 text-center ring-1 ring-white/15" value={v} min={min} max={max} onChange={(e) => setV(Math.max(min, Math.min(max, Number(e.target.value) || 0)))} />
      </div>
      <div className="mt-2 text-xs text-ink-400">
        Bereich {min} – {max}
      </div>
    </Modal>
  )
}

function MultiAmountDialog({ p }: { p: Prompt }) {
  const answer = useGame((s) => s.answer)
  const [vals, setVals] = useState(() => (p.items ?? []).map((i) => i.value))
  const total = vals.reduce((a, b) => a + b, 0)
  const ok = total >= (p.min ?? 0) && total <= (p.max ?? 0)
  return (
    <Modal
      minimizable
      title={<Title p={p} />}
      closable={false}
      footer={
        <button className="btn-primary" disabled={!ok} onClick={() => answer({ str: vals.join(' ') })}>
          OK (Summe {total})
        </button>
      }
    >
      <div className="flex flex-col gap-2">
        {(p.items ?? []).map((it, i) => (
          <div key={i} className="flex items-center gap-3">
            <div className="flex-1 text-sm">{withSymbols(it.message)}</div>
            <input
              type="number"
              className="w-20 rounded-lg bg-ink-950/70 px-2 py-1 text-center ring-1 ring-white/15"
              value={vals[i]}
              min={it.min}
              max={it.max}
              onChange={(e) => {
                const n = Math.max(it.min, Math.min(it.max, Number(e.target.value) || 0))
                setVals(vals.map((x, j) => (j === i ? n : x)))
              }}
            />
          </div>
        ))}
        <div className="text-xs text-ink-400">
          Summe muss zwischen {p.min} und {p.max} liegen.
        </div>
      </div>
    </Modal>
  )
}

function PileDialog({ p, onHover }: { p: Prompt; onHover: (c: Card | null) => void }) {
  const answer = useGame((s) => s.answer)
  return (
    <Modal minimizable title={<Title p={p} />} closable={false} wide>
      <div className="grid grid-cols-2 gap-6">
        {[p.pile1 ?? [], p.pile2 ?? []].map((pile, i) => (
          <div key={i} className="flex flex-col gap-3 rounded-xl bg-ink-950/40 p-3 ring-1 ring-white/10">
            <div className="flex flex-wrap gap-2">
              {pile.map((c) => (
                <CardView key={c.id} card={c} size="md" onHover={onHover} />
              ))}
              {pile.length === 0 && <div className="text-sm text-ink-400">(leer)</div>}
            </div>
            <button className="btn-primary" onClick={() => answer({ bool: i === 0 })}>
              Stapel {i + 1} wählen
            </button>
          </div>
        ))}
      </div>
    </Modal>
  )
}

function MulliganDialog({ p, onHover }: { p: Prompt; onHover: (c: Card | null) => void }) {
  const answer = useGame((s) => s.answer)
  const hand = useGame((s) => s.state?.hand ?? [])
  return (
    <Modal minimizable title="Starthand" closable={false} wide>
      <div className="mb-4 text-sm text-ink-300">
        <Rich segs={p.message} />
      </div>
      <div className="flex flex-wrap justify-center gap-3">
        {hand.map((c) => (
          <CardView key={c.id} card={c} size="xl" onHover={onHover} />
        ))}
      </div>
      <div className="mt-6 flex justify-center gap-3">
        <button className="btn-ghost min-w-[160px]" onClick={() => answer({ bool: true })}>
          Mulligan
        </button>
        <button className="btn-primary min-w-[160px]" onClick={() => answer({ bool: false })}>
          Behalten
        </button>
      </div>
    </Modal>
  )
}

function CardPickDialog({ p, inter, onHover }: { p: Prompt; inter: Interaction; onHover: (c: Card | null) => void }) {
  const answer = useGame((s) => s.answer)
  const canFinish = !!p.rightBtn || !p.required
  return (
    <Modal
      minimizable
      title={<Title p={p} />}
      closable={false}
      wide
      footer={canFinish ? <button className="btn-primary" onClick={() => answer({ bool: false })}>{p.chosen?.length ? 'Fertig' : 'Keine wählen'}</button> : undefined}
    >
      <div className="flex flex-wrap gap-2">
        {inter.modalCards.map((c) => (
          <CardView key={c.id} card={c} size="lg" highlight={inter.highlight(c.id)} onHover={onHover} onClick={() => inter.click(c.id)} dim={!inter.canClick(c.id)} />
        ))}
      </div>
    </Modal>
  )
}
