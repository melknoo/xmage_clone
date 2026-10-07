// Etiketten und Pod-Chips statt Kampf-/Zielpfeilen (CombatOverlay/TargetOverlay entfallen).
// Reine Ableitung aus state.combat, state.stack und der Interaktion; Text normal geschrieben, Versalien per CSS.
import type { CardLabel } from '../components/CardView'
import type { GameState, UUID } from '../api/types'
import type { Interaction } from './interaction'
import { shortName } from './format'

export interface DecorCtx {
  state: GameState
  /** Modus, Markierungen (Shift), Prompt (chosen) und Spieler-Ziele */
  inter: Pick<Interaction, 'mode' | 'marked' | 'prompt' | 'playerTargetable'>
  /** Stapelobjekt unter der Maus (store.stackFocus): nur dessen Ziele; sonst die Ziele aller Stapelobjekte */
  stackFocus?: UUID | null
  /** Kompakt-Layout: keine Etiketten auf Gegnerkarten */
  compact?: boolean
}

export interface CardDecor {
  /** Etikett unten an der Karte (CardView label) */
  label?: CardLabel
}

export interface PodDecor {
  /** Angreifer auf diesen Spieler oder seine Planeswalker/Schlachten ("N Angreifer") */
  attackersN: number
  /** waehlbares Ziel (Zauberziel, Angriffsziel der Markierten) */
  targetable: boolean
  /** im laufenden Prompt schon gewaehlt */
  chosen: boolean
  /** Ziel eines Stapelobjekts (Fokus bzw. alle) */
  stackTarget: boolean
}

interface Index {
  meId: UUID | undefined
  /** Permanent -> Controller */
  controller: Map<UUID, UUID>
  /** Permanent -> Name */
  name: Map<UUID, string>
  /** Angreifer -> Kampfgruppe */
  attackerGroup: Map<UUID, GameState['combat'][number]>
  /** Blocker -> erster geblockter Angreifer */
  blockerOf: Map<UUID, UUID>
}

const cache = new WeakMap<GameState, Index>()

/** Etiketten-Objekte je Text+Ton wiederverwenden (CardView ist memo) */
const labels = new Map<string, CardLabel>()
function lbl(text: string, tone: CardLabel['tone']): CardLabel {
  const k = `${tone}|${text}`
  let l = labels.get(k)
  if (!l) {
    l = { text, tone }
    labels.set(k, l)
  }
  return l
}

function index(s: GameState): Index {
  let ix = cache.get(s)
  if (ix) return ix
  const controller = new Map<UUID, UUID>()
  const name = new Map<UUID, string>()
  for (const p of s.players) {
    for (const c of p.battlefield) {
      controller.set(c.id, c.controllerId ?? p.id)
      name.set(c.id, c.name)
    }
  }
  const attackerGroup = new Map<UUID, GameState['combat'][number]>()
  const blockerOf = new Map<UUID, UUID>()
  for (const g of s.combat ?? []) {
    for (const a of g.attackers) attackerGroup.set(a, g)
    for (const b of g.blockers) if (!blockerOf.has(b) && g.attackers[0]) blockerOf.set(b, g.attackers[0])
  }
  // Blickwinkel: ich bzw. beim Zuschauen der Spieler mit me=true
  const meId = s.myPlayerId ?? s.players.find((p) => p.me)?.id
  ix = { meId, controller, name, attackerGroup, blockerOf }
  cache.set(s, ix)
  return ix
}

/** Ziele der Stapelobjekte: nur das fokussierte, sonst alle */
function stackTargets(s: GameState, focus: UUID | null | undefined): Set<UUID> {
  const item = focus ? s.stack.find((c) => c.id === focus) : undefined
  const items = item ? [item] : s.stack
  return new Set(items.flatMap((c) => c.targets ?? []))
}

/** Verteidiger ist Spieler pid oder ein Permanent, das pid kontrolliert */
function defends(ix: Index, defenderId: UUID, pid: UUID | undefined): boolean {
  return !!pid && (defenderId === pid || ix.controller.get(defenderId) === pid)
}

/**
 * Etikett einer Karte auf dem Feld. Reihenfolge: "Markiert" (Shift-Markierung im Angriff/Block), Angreifer
 * ("→ Kotori", "→ Du", "Geblockt"), Blocker ("Blockt X"), Stapelziel ("Ziel").
 */
export function cardDecor(objId: UUID, ctx: DecorCtx): CardDecor {
  const s = ctx.state
  const ix = index(s)
  const mine = ix.controller.get(objId) === ix.meId
  if (ctx.compact && !mine) return {}
  const { mode, marked } = ctx.inter
  if ((mode === 'attack' || mode === 'block') && marked.has(objId)) {
    return { label: lbl('Markiert', mode === 'attack' ? 'attack' : 'block') }
  }
  const g = ix.attackerGroup.get(objId)
  if (g) {
    if (g.blockers.length > 0) return { label: lbl('Geblockt', 'block') }
    const toMe = !mine && defends(ix, g.defenderId, ix.meId)
    return { label: lbl(toMe ? '→ Du' : `→ ${shortName(g.defenderName)}`, 'attack') }
  }
  const blocked = ix.blockerOf.get(objId)
  if (blocked) return { label: lbl(`Blockt ${shortName(ix.name.get(blocked))}`, 'block') }
  if (s.stack.length > 0 && stackTargets(s, ctx.stackFocus).has(objId)) return { label: lbl('Ziel', 'target') }
  return {}
}

/** Chips/Rahmen eines Spieler-Pods (bzw. der eigenen Infospalte). */
export function podDecor(playerId: UUID, ctx: DecorCtx): PodDecor {
  const s = ctx.state
  const ix = index(s)
  let attackersN = 0
  for (const g of s.combat ?? []) if (defends(ix, g.defenderId, playerId)) attackersN += g.attackers.length
  return {
    attackersN,
    targetable: ctx.inter.playerTargetable(playerId),
    chosen: !!ctx.inter.prompt?.chosen?.includes(playerId),
    stackTarget: s.stack.length > 0 && stackTargets(s, ctx.stackFocus).has(playerId),
  }
}
