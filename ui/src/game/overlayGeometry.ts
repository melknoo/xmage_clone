export interface Point {
  x: number
  y: number
}

/** Mittelpunkt des ersten Elements zum Selektor (Bildschirmkoordinaten) oder null. */
export function center(sel: string): Point | null {
  const el = document.querySelector(sel)
  if (!el) return null
  const r = el.getBoundingClientRect()
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
}

/** Gebogener Pfad von a nach b (leicht nach oben gewoelbt). */
export function curve(a: Point, b: Point): string {
  const mx = (a.x + b.x) / 2
  const my = (a.y + b.y) / 2 - Math.abs(b.x - a.x) * 0.15
  return `M ${a.x} ${a.y} Q ${mx} ${my} ${b.x} ${b.y}`
}

/** Mittelpunkt eines Objekts auf dem Feld; zusammengefasste Karten (×N) ueber den Stapel, der sie enthaelt. */
export function objCenter(id: string): Point | null {
  return center(`[data-obj="${id}"]`) ?? center(`[data-objs~="${id}"]`)
}

export type ZoneKey = 'library' | 'hand' | 'graveyard' | 'exile' | 'command'

/** XMage-Zonenname -> Ankerzone in der UI */
export function zoneKey(zone?: string): ZoneKey | null {
  switch (zone) {
    case 'LIBRARY':
      return 'library'
    case 'HAND':
      return 'hand'
    case 'GRAVEYARD':
      return 'graveyard'
    case 'EXILED':
      return 'exile'
    case 'COMMAND':
      return 'command'
    default:
      return null
  }
}

/** Anker einer Zone eines Spielers (Zonen-Knopf im Pod bzw. meine Hand), sonst der Spieler selbst. */
export function zoneAnchor(playerId: string | undefined, zone: ZoneKey | null): Point | null {
  if (!playerId) return null
  return (
    (zone ? center(`[data-player="${playerId}"] [data-zone="${zone}"]`) : null) ??
    (zone ? center(`[data-zone="${zone}"][data-owner="${playerId}"]`) : null) ??
    anchorOf(playerId)
  )
}

/** Mittelpunkt eines Stapelobjekts (sonst der Stapel insgesamt). */
export function stackAnchor(id?: string): Point | null {
  return (id ? center(`[data-stack="${id}"]`) : null) ?? center('[data-stack]')
}

/** Mittelpunkt eines Spielers (Lebensanzeige bevorzugt) oder eines Objekts auf Feld/Stapel. */
export function anchorOf(id: string): Point | null {
  return (
    center(`[data-player="${id}"] [data-life]`) ??
    center(`[data-player="${id}"]`) ??
    objCenter(id) ??
    center(`[data-stack="${id}"]`)
  )
}
