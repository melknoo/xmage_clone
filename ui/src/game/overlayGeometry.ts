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
