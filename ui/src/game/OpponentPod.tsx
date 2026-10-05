import { memo } from 'react'
import type { Card, PlayerState } from '../api/types'
import { useGame } from '../store/game'
import { Battlefield } from './Battlefield'
import type { Interaction } from './interaction'
import { CommandZone, CommanderDamage, LifeBadge, ManaPool, ZoneCounters, commanderArt } from './PlayerInfo'

export const OpponentPod = memo(function OpponentPod({
  p,
  inter,
  onHover,
  thinking,
}: {
  p: PlayerState
  inter: Interaction
  onHover: (c: Card | null) => void
  thinking: boolean
}) {
  const art = commanderArt(p)
  const targetable = inter.playerTargetable(p.id)
  const chosen = inter.prompt?.chosen?.includes(p.id)
  const ring = targetable ? 'glow-target cursor-pointer' : chosen ? 'glow-chosen' : p.active ? 'ring-2 ring-gold-400/70' : 'ring-1 ring-white/10'
  return (
    <div data-player={p.id} className={`glass relative flex min-h-0 flex-col overflow-hidden rounded-2xl ${ring} ${p.lost ? 'opacity-40 grayscale' : ''}`}>
      <div
        className="relative flex shrink-0 items-center gap-2 border-b border-white/10 px-2 py-1.5"
        onClick={() => targetable && inter.click(p.id)}
      >
        {art && <div className="pointer-events-none absolute inset-0 bg-cover bg-center opacity-20" style={{ backgroundImage: `url(${art})` }} />}
        <div className="relative h-10 w-10 shrink-0 overflow-hidden rounded-full ring-2 ring-white/20">
          {art ? <img src={art} alt="" className="h-full w-full object-cover" /> : <div className="h-full w-full bg-ink-600" />}
          {thinking && <div className="absolute inset-0 animate-pulse bg-arcane-400/30" />}
        </div>
        <div className="relative min-w-0 flex-1">
          <div className="flex items-center gap-1.5">
            <span className="truncate font-semibold">{p.name}</span>
            {p.active && <span className="rounded bg-gold-400/20 px-1 text-[10px] font-semibold text-gold-300">am Zug</span>}
            {thinking && <span className="animate-pulse text-[10px] font-semibold text-arcane-400">denkt…</span>}
            {p.lost && <span className="rounded bg-blood-500/30 px-1 text-[10px] text-blood-400">raus</span>}
            {p.human && !p.lost && <ConnBadge playerId={p.id} />}
          </div>
          <div className="truncate text-[11px] text-ink-300">{p.deckName}</div>
        </div>
        <div className="relative">
          <LifeBadge life={p.life} />
        </div>
      </div>
      <div className="flex shrink-0 flex-wrap items-center justify-between gap-1 px-2 py-1">
        <ZoneCounters p={p} onHover={onHover} inter={inter} />
        <CommanderDamage dmg={p.commanderDamage} />
        <ManaPool mana={p.mana} />
      </div>
      <div className="flex min-h-0 flex-1 gap-1.5 px-1.5 pb-1.5">
        <div className="shrink-0 pt-1">
          <CommandZone objects={p.command} inter={inter} onHover={onHover} size="xs" />
        </div>
        <div className="min-h-0 min-w-0 flex-1">
          <Battlefield perms={p.battlefield} size="sm" inter={inter} onHover={onHover} landsFirst />
        </div>
      </div>
    </div>
  )
})

/** "getrennt seit N s" fuer menschliche Mitspieler; nach 60 s "aufgeben lassen" (Engine wartet sonst ewig auf dessen Prompt). */
function ConnBadge({ playerId }: { playerId: string }) {
  const conn = useGame((s) => s.seatConn[playerId])
  const myConceded = useGame((s) => s.conceded)
  const kick = useGame((s) => s.kick)
  // Grenze kommt von der Engine (Produktion 60 s, Dev-Engine 5 s)
  const KICK_AFTER_MS = useGame((s) => s.kickAfterMs)
  if (!conn || conn.connected || conn.conceded) return null
  const secs = Math.round(conn.disconnectedMs / 1000)
  return (
    <span className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
      <span className="rounded bg-blood-500/20 px-1 text-[10px] font-semibold text-blood-300" title="Verbindung zum Mitspieler ist weg; das Spiel wartet auf ihn">
        getrennt {secs} s
      </span>
      {conn.disconnectedMs >= KICK_AFTER_MS && !myConceded && (
        <button className="rounded bg-white/10 px-1 text-[10px] text-ink-100 hover:bg-blood-500/30" title="Den getrennten Spieler aufgeben lassen, damit das Spiel weitergeht" onClick={() => kick(playerId)}>
          aufgeben lassen
        </button>
      )}
    </span>
  )
}
