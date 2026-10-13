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

/** Bildschirm-Rechteck des ersten Elements zum Selektor oder null. */
export function rectOf(sel: string): Rect | null {
  const el = document.querySelector(sel)
  if (!el) return null
  const r = el.getBoundingClientRect()
  return { left: r.left, top: r.top, width: r.width, height: r.height }
}

/** Mittelpunkt eines Rechtecks */
export function rectCenter(r: Rect): Point {
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
}

/**
 * Punkt auf dem Rand von r (um pad vergroessert) auf der Strecke von der Mitte zu p - Pfeile beginnen/enden an der
 * Kartenkante statt in der Mitte und verdecken so weniger.
 */
export function edgeToward(r: Rect, p: Point, pad = 3): Point {
  const c = rectCenter(r)
  const dx = p.x - c.x
  const dy = p.y - c.y
  if (dx === 0 && dy === 0) return c
  const tx = dx === 0 ? Infinity : (r.width / 2 + pad) / Math.abs(dx)
  const ty = dy === 0 ? Infinity : (r.height / 2 + pad) / Math.abs(dy)
  const t = Math.min(tx, ty, 1)
  return { x: c.x + dx * t, y: c.y + dy * t }
}

/**
 * Quadratische Kurve a -> b, leicht nach oben gewoelbt (Pfeile). bend = Woelbung relativ zur Laenge, hoechstens 80 px.
 */
export function curve(a: Point, b: Point, bend = 0.16): string {
  const dx = b.x - a.x
  const dy = b.y - a.y
  const len = Math.hypot(dx, dy) || 1
  let nx = -dy / len
  let ny = dx / len
  if (ny > 0) {
    nx = -nx
    ny = -ny
  }
  const k = Math.min(80, len * bend)
  const cx = (a.x + b.x) / 2 + nx * k
  const cy = (a.y + b.y) / 2 + ny * k
  return `M ${a.x.toFixed(1)} ${a.y.toFixed(1)} Q ${cx.toFixed(1)} ${cy.toFixed(1)} ${b.x.toFixed(1)} ${b.y.toFixed(1)}`
}

/** Lebensanzeige eines Spielers (sonst sein Pod bzw. seine Infospalte) */
export function playerRect(id: string): Rect | null {
  return rectOf(`[data-player="${id}"] [data-life]`) ?? rectOf(`[data-player="${id}"]`)
}

/** Zeile eines Stapelobjekts (eingeklappt: Kopf des Stapels) */
export function stackRect(id: string): Rect | null {
  return rectOf(`[data-stack="${id}"]`)
}

/** Spieler, Objekt auf dem Feld/in der Hand/Kommandozone oder Stapelobjekt */
export function entityRect(id: string): Rect | null {
  return playerRect(id) ?? objRect(id) ?? stackRect(id)
}

/** Mittelpunkt eines Objekts auf dem Feld; zusammengefasste Karten (×N) ueber den Stapel, der sie enthaelt. */
export function objCenter(id: string): Point | null {
  return center(`[data-obj="${id}"]`) ?? center(`[data-objs~="${id}"]`)
}

/** Bildschirm-Rechteck (px) */
export interface Rect {
  left: number
  top: number
  width: number
  height: number
}

/**
 * Sichtbare Kartenflaeche eines Objekts auf dem Feld (getappt quer, ohne Anlagen-Versatz); zusammengefasste Karten
 * (×N) ueber die oberste Karte des Stapels. Sonst null.
 */
export function objRect(id: string): Rect | null {
  const el = document.querySelector(`[data-obj="${id}"]`) ?? document.querySelector(`[data-objs~="${id}"]`)
  if (!el) return null
  const r = (el.querySelector('[data-card-face]') ?? el).getBoundingClientRect()
  return { left: r.left, top: r.top, width: r.width, height: r.height }
}

/** Quelle eines Ereignisses (Schaden): Objekt auf dem Feld, sonst Stapelobjekt mit dieser id. */
export function sourceCenter(id: string): Point | null {
  return objCenter(id) ?? center(`[data-stack="${id}"]`)
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

/** Obere rechte Ecke der Lebensanzeige eines Spielers (Lebens-Delta der FX-Ebene), sonst null. */
export function lifeAnchor(playerId: string): Point | null {
  const el = document.querySelector(`[data-player="${playerId}"] [data-life]`)
  if (!el) return null
  const r = el.getBoundingClientRect()
  return { x: r.right, y: r.top }
}
