import { useState } from 'react'
import { cardImageUrl } from '../api/client'
import type { Card, CommandObject, PlayerState } from '../api/types'
import { CardView } from '../components/CardView'
import { Modal } from '../components/Modal'
import type { Interaction } from './interaction'

export function commanderArt(p: PlayerState): string | null {
  const c = p.command.find((o) => o.kind === 'commander' || o.kind === 'commander-away')
  if (!c?.set || !c.num) return null
  return cardImageUrl({ set: c.set, num: c.num }, { size: 'art_crop' })
}

export function LifeBadge({ life, big }: { life: number; big?: boolean }) {
  const low = life <= 10
  return (
    <div
      data-life
      className={`flex items-center justify-center rounded-xl font-display font-bold tabular-nums ${big ? 'h-14 min-w-14 px-2 text-3xl' : 'h-10 min-w-10 px-1.5 text-xl'} ${
        low ? 'bg-blood-500/20 text-blood-400 ring-1 ring-blood-400/50' : 'bg-ink-950/70 text-ink-100 ring-1 ring-white/15'
      }`}
      title="Lebenspunkte"
    >
      {life}
    </div>
  )
}

function ZoneButton({ label, icon, count, onClick }: { label: string; icon: string; count: number; onClick?: () => void }) {
  return (
    <button
      className="flex items-center gap-1 rounded-md bg-ink-950/50 px-1.5 py-0.5 text-[11px] text-ink-200 ring-1 ring-white/10 hover:bg-ink-700/70 disabled:opacity-60"
      onClick={onClick}
      disabled={!onClick}
      title={label}
    >
      <span>{icon}</span>
      <span className="tabular-nums">{count}</span>
    </button>
  )
}

export function ZoneCounters({ p, onHover, inter }: { p: PlayerState; onHover: (c: Card | null) => void; inter: Interaction }) {
  const [view, setView] = useState<null | 'gy' | 'ex'>(null)
  const cards = view === 'gy' ? p.graveyard : view === 'ex' ? p.exile : []
  return (
    <>
      <div className="flex flex-wrap items-center gap-1">
        <ZoneButton label="Bibliothek" icon="📚" count={p.library} />
        <ZoneButton label="Hand" icon="✋" count={p.handCount} />
        <ZoneButton label="Friedhof" icon="🪦" count={p.graveyard.length} onClick={p.graveyard.length ? () => setView('gy') : undefined} />
        <ZoneButton label="Exil" icon="🌀" count={p.exile.length} onClick={p.exile.length ? () => setView('ex') : undefined} />
        {p.counters?.map((c) => (
          <span key={c.name} className="rounded-md bg-purple-500/20 px-1.5 py-0.5 text-[11px] text-purple-200 ring-1 ring-purple-400/30" title={c.name}>
            {counterLabel(c.name)} {c.count}
          </span>
        ))}
        {p.monarch && <span title="Monarch" className="text-sm">👑</span>}
        {p.initiative && <span title="Initiative" className="text-sm">🗝️</span>}
      </div>
      {view && (
        <Modal title={`${view === 'gy' ? 'Friedhof' : 'Exil'} – ${p.name}`} onClose={() => setView(null)} wide>
          <div className="flex flex-wrap gap-2">
            {[...cards].reverse().map((c) => (
              <CardView key={c.id} card={c} size="lg" onHover={onHover} highlight={inter.highlight(c.id)} onClick={() => inter.click(c.id)} />
            ))}
          </div>
        </Modal>
      )}
    </>
  )
}

function counterLabel(name: string): string {
  const n = name.toLowerCase()
  if (n.includes('poison')) return '☠'
  if (n.includes('energy')) return '⚡'
  if (n.includes('experience')) return '✦'
  if (n.includes('rad')) return '☢'
  return name
}

export function CommandZone({ objects, inter, onHover, size = 'sm' }: { objects: CommandObject[]; inter: Interaction; onHover: (c: Card | null) => void; size?: 'xs' | 'sm' | 'md' }) {
  return (
    <div className="flex items-end gap-1.5">
      {objects.map((o) => {
        const card: Card = o.card ?? { id: o.id, name: o.name, set: o.set, num: o.num, rules: o.rules, image: o.image, imageNum: o.imageNum }
        const away = o.kind === 'commander-away'
        return (
          <div key={o.id} className="relative">
            <CardView card={card} size={size} dim={away} highlight={away ? 'none' : inter.highlight(o.id)} onHover={onHover} onClick={() => inter.click(o.id)} />
            {(o.kind === 'commander' || away) && (o.tax ?? 0) > 0 && (
              <span className="absolute -bottom-1 left-1/2 z-10 -translate-x-1/2 whitespace-nowrap rounded bg-ink-950/95 px-1 text-[10px] font-semibold text-gold-300 ring-1 ring-gold-400/40" title={`${o.casts}× gewirkt`}>
                +{o.tax} Steuer
              </span>
            )}
          </div>
        )
      })}
    </div>
  )
}

export function CommanderDamage({ dmg }: { dmg?: Record<string, number> }) {
  if (!dmg) return null
  return (
    <div className="flex flex-wrap gap-1">
      {Object.entries(dmg).map(([name, n]) => (
        <span key={name} className={`rounded-md px-1.5 py-0.5 text-[10px] ring-1 ${n >= 15 ? 'bg-blood-500/25 text-blood-400 ring-blood-400/40' : 'bg-ink-950/60 text-ink-200 ring-white/10'}`} title={`Commander-Schaden von ${name}`}>
          ⚔ {name.split(',')[0]}: {n}
        </span>
      ))}
    </div>
  )
}

export function ManaPool({ mana, onClick }: { mana?: Record<string, number>; onClick?: (type: string) => void }) {
  if (!mana) return null
  const NAMES: Record<string, string> = { W: 'WHITE', U: 'BLUE', B: 'BLACK', R: 'RED', G: 'GREEN', C: 'COLORLESS' }
  return (
    <div className="flex items-center gap-1 rounded-lg bg-ink-950/60 px-2 py-1 ring-1 ring-arcane-400/30" title="Manapool">
      {Object.entries(mana).map(([k, v]) => (
        <button key={k} className="flex items-center gap-0.5 rounded px-0.5 hover:bg-white/10 disabled:cursor-default" disabled={!onClick} onClick={() => onClick?.(NAMES[k])}>
          <i className={`ms ms-${k.toLowerCase()} ms-cost ms-shadow`} />
          <span className="text-sm font-bold tabular-nums">{v}</span>
        </button>
      ))}
    </div>
  )
}
