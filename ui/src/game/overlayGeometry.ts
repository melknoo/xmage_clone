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

/** Mittelpunkt eines Spielers (Lebensanzeige bevorzugt) oder eines Objekts auf Feld/Stapel. */
export function anchorOf(id: string): Point | null {
  return (
    center(`[data-player="${id}"] [data-life]`) ??
    center(`[data-player="${id}"]`) ??
    center(`[data-obj="${id}"]`) ??
    center(`[data-stack="${id}"]`)
  )
}
