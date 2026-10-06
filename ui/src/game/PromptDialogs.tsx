import { useDeferredValue, useEffect, useMemo, useRef, useState } from 'react'
import type { Card, Prompt, ReplGroup } from '../api/types'
import { CardView } from '../components/CardView'
import { Modal } from '../components/Modal'
import { Rich, withSymbols } from '../lib/mana'
import { useGame } from '../store/game'
import type { Interaction } from './interaction'
import { isOpeningHandAsk } from './promptActions'

export function PromptDialogs({ inter, onHover }: { inter: Interaction; onHover: (c: Card | null) => void }) {
  const p = inter.prompt
  if (!p) return null
  // key: jeder neue Prompt startet wieder aufgeklappt
  return <PromptDialog key={p.id} p={p} inter={inter} onHover={onHover} />
}

function PromptDialog({ p, inter, onHover }: { p: Prompt; inter: Interaction; onHover: (c: Card | null) => void }) {
  const step = useGame((s) => s.state?.step)
  switch (p.kind) {
    case 'CHOOSE_ABILITY':
    case 'CHOOSE_MODE':
    case 'PICK_ABILITY':
      return <ChoiceListDialog p={p} />
    case 'CHOOSE_CHOICE':
      return p.choice?.groups ? <ReplacementDialog p={p} onHover={onHover} /> : <ChoiceDialog p={p} onHover={onHover} />
    case 'AMOUNT':
      return <AmountDialog p={p} />
    case 'MULTI_AMOUNT':
      return <MultiAmountDialog p={p} />
    case 'CHOOSE_PILE':
      return <PileDialog p={p} onHover={onHover} />
    case 'ASK':
      if (p.mulligan) return <MulliganDialog p={p} onHover={onHover} />
      if (isOpeningHandAsk(p, step)) return <OpeningHandDialog p={p} onHover={onHover} />
      return null
    case 'PICK_TARGET':
      return inter.needsCardModal ? <CardPickDialog p={p} inter={inter} onHover={onHover} /> : null
    default:
      return null
  }
}

function Title({ p }: { p: Prompt }) {
  return <Rich segs={p.message} />
}

const REPEAT_PRESETS = [1, 3, 5, 10]

function ChoiceListDialog({ p }: { p: Prompt }) {
  const answer = useGame((s) => s.answer)
  const repeat = useGame((s) => s.repeat)
  const [times, setTimes] = useState(1)
  // "N-mal aktivieren" nur fuer aktivierte Faehigkeiten eines Objekts (z.B. Necropotence "Pay 1 life")
  const repeatable = p.kind === 'CHOOSE_ABILITY' && !!p.sourceId
  const pick = (id: string) => (repeatable && times > 1 ? repeat(id, times) : answer({ uuid: id }))
  return (
    <Modal
      minimizable
      title={<Title p={p} />}
      onClose={() => answer({ bool: false })}
      closable={!p.required}
      footer={
        repeatable ? (
          <div className="flex w-full flex-wrap items-center justify-between gap-2 text-xs text-ink-300">
            <span title="Die Engine hält zwischen den Aktivierungen die Priorität; Ziele oder Fragen stoppen die Wiederholung">
              Anzahl: <b className="text-ink-100">×{times}</b>
            </span>
            <div className="flex items-center gap-1">
              {REPEAT_PRESETS.map((n) => (
                <button key={n} className={`rounded px-2 py-1 ring-1 ring-white/10 ${times === n ? 'bg-arcane-500/30 text-ink-100' : 'bg-ink-900/60 hover:bg-ink-700'}`} onClick={() => setTimes(n)}>
                  ×{n}
                </button>
              ))}
              <button className="rounded px-2 py-1 ring-1 ring-white/10 bg-ink-900/60 hover:bg-ink-700" onClick={() => setTimes((t) => Math.max(1, t - 1))} title="weniger">
                −
              </button>
              <button className="rounded px-2 py-1 ring-1 ring-white/10 bg-ink-900/60 hover:bg-ink-700" onClick={() => setTimes((t) => Math.min(20, t + 1))} title="mehr">
                +
              </button>
            </div>
          </div>
        ) : undefined
      }
    >
      <div className="flex flex-col gap-2">
        {(p.choices ?? []).map((c) => (
          <button key={c.id} className="rounded-xl bg-ink-800/80 px-4 py-3 text-left text-sm ring-1 ring-white/10 transition hover:bg-ink-700 hover:ring-gold-400/50" onClick={() => pick(c.id)}>
            {withSymbols(c.text)}
            {repeatable && times > 1 && <span className="ml-2 rounded bg-arcane-500/30 px-1.5 py-0.5 text-xs text-arcane-300">×{times}</span>}
          </button>
        ))}
      </div>
    </Modal>
  )
}

function ChoiceDialog({ p, onHover }: { p: Prompt; onHover: (c: Card | null) => void }) {
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
  useEffect(() => () => {
    if (hoverTimer.current) window.clearTimeout(hoverTimer.current)
    if (cardHint) onHover(null)
  }, [cardHint, onHover])
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
          <button
            key={i.key}
            className="rounded-lg bg-ink-800/80 px-3 py-2 text-left text-sm ring-1 ring-white/10 hover:bg-ink-700 hover:ring-gold-400/50"
            onClick={() => answer({ str: ch?.keyed ? i.key : i.value })}
            onMouseEnter={() => hoverName(i.value)}
            onMouseLeave={() => hoverName(null)}
          >
            {ch?.manaColor ? <ManaWord v={i.value} /> : withSymbols(i.value)}
          </button>
        ))}
        {items.length > 300 && <div className="col-span-2 p-2 text-xs text-ink-400">… {items.length - 300} weitere – Suche verfeinern</div>}
      </div>
    </Modal>
  )
}

/**
 * Ersatzeffekt-Wahl: gleiche Effekte (Regeltext) als ein Kasten mit Karten-Chips. Bei optionalen Effekten
 * ("you may", z.B. Dredge) wendet ein Chip-Klick den Effekt direkt an; "Keinen anwenden" lehnt alle ab.
 */
function ReplacementDialog({ p, onHover }: { p: Prompt; onHover: (c: Card | null) => void }) {
  const answer = useGame((s) => s.answer)
  const replacement = useGame((s) => s.replacement)
  const objects = useGame((s) => s.objects)
  const [remember, setRemember] = useState(false)
  const groups = p.choice?.groups ?? []
  const anyOptional = groups.some((g) => g.optional)
  const pick = (g: ReplGroup, key: string) => (g.optional ? replacement('accept', key) : answer({ str: key }))
  return (
    <Modal
      minimizable
      title="Ersatzeffekt wählen"
      closable={false}
      footer={
        anyOptional ? (
          <div className="flex w-full flex-wrap items-center justify-between gap-3">
            <label className="flex cursor-pointer items-center gap-2 text-xs text-ink-300" title="Diese Effekte ab jetzt ohne Nachfrage ablehnen (Knopf oben in der Leiste setzt zurück)">
              <input type="checkbox" className="accent-amber-400" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
              Für dieses Spiel merken
            </label>
            <button className="btn-primary" onClick={() => replacement('decline', undefined, remember)}>
              Keinen anwenden
            </button>
          </div>
        ) : undefined
      }
    >
      <div className="mb-3 text-xs text-ink-400">{anyOptional ? 'Karte anklicken = diesen Effekt anwenden.' : 'Welcher Effekt wirkt zuerst?'}</div>
      <div className="flex flex-col gap-2">
        {groups.map((g) => {
          const rest = g.rule.startsWith(g.label) ? g.rule.slice(g.label.length) : null
          return (
            <div key={g.rule} className="rounded-xl bg-ink-800/60 p-3 ring-1 ring-white/10">
              <div className="mb-2 text-sm">
                {rest === null ? (
                  withSymbols(g.rule)
                ) : (
                  <>
                    <span className="font-semibold text-gold-300">{g.label}</span>
                    {g.sources.length > 1 && <span className="ml-1.5 text-xs text-ink-400">×{g.sources.length}</span>}
                    <span className="text-ink-400">{withSymbols(rest)}</span>
                  </>
                )}
              </div>
              <div className="flex flex-wrap gap-1.5">
                {g.sources.map((src) => {
                  const card = src.objectId ? objects.get(src.objectId) : undefined
                  return (
                    <button
                      key={src.key}
                      className="rounded-lg bg-ink-950/60 px-2.5 py-1 text-xs font-semibold text-ink-100 ring-1 ring-white/15 transition hover:bg-ink-700 hover:ring-gold-400/60"
                      onMouseEnter={() => card && onHover(card)}
                      onMouseLeave={() => onHover(null)}
                      onClick={() => pick(g, src.key)}
                    >
                      {src.name}
                    </button>
                  )
                })}
              </div>
            </div>
          )
        })}
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

/**
 * Starthand-Aktion vor dem ersten Zug (Gemstone Caverns, Leylines, Chancellors): XMage fragt nur per Ja/Nein -
 * in der Prompt-Leiste leicht zu uebersehen oder mit Esc wegzudruecken. Darum ein eigener Dialog mit der Hand.
 */
function OpeningHandDialog({ p, onHover }: { p: Prompt; onHover: (c: Card | null) => void }) {
  const answer = useGame((s) => s.answer)
  const hand = useGame((s) => s.state?.hand ?? [])
  const name = /^Put (.+?) (?:onto|on) the battlefield\?$/i.exec(p.messageText ?? '')?.[1]
  return (
    <Modal title="Starthand-Aktion" closable={false} wide>
      <div className="mb-4 text-sm text-ink-300">
        <Rich segs={p.message} />
        <div className="mt-1 text-xs text-ink-400">Diese Karte darf schon vor dem ersten Zug ins Spiel kommen.</div>
      </div>
      <div className="flex flex-wrap justify-center gap-3">
        {hand.map((c) => (
          <CardView key={c.id} card={c} size="xl" onHover={onHover} highlight={name && c.name === name ? 'chosen' : 'none'} dim={!!name && c.name !== name} />
        ))}
      </div>
      <div className="mt-6 flex justify-center gap-3">
        <button className="btn-ghost min-w-[160px]" onClick={() => answer({ bool: false })}>
          In der Hand behalten
        </button>
        <button className="btn-primary min-w-[160px]" onClick={() => answer({ bool: true })}>
          Auf das Spielfeld legen
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
