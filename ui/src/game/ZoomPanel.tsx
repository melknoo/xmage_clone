import { useEffect, useState } from 'react'
import type { Card, Permanent } from '../api/types'
import { CardView } from '../components/CardView'
import { ManaCost, RulesText } from '../lib/mana'
import { useGame } from '../store/game'
import type { BoardLayoutState } from './layout'

export interface ZoomPanelProps {
  /** zoomW, compact (Regeltext nur im vollen Layout) */
  layout: BoardLayoutState
}

/**
 * Grosse Kartenvorschau oben in der Seitenleiste. Zeigt die Karte unter der Maus (store.hover), sonst die zuletzt
 * gezeigte: zuletzt gehoverte Karte oder ein neues oberstes Stapelobjekt. Erscheint ohne Ueberblendung.
 */
export function ZoomPanel({ layout }: ZoomPanelProps) {
  const hover = useGame((s) => s.hover)
  const stackTop = useGame((s) => s.state?.stack[0] ?? null)
  const objects = useGame((s) => s.objects)
  const [last, setLast] = useState<Card | null>(null)

  useEffect(() => {
    if (hover) setLast(hover)
  }, [hover])
  // neues oberstes Stapelobjekt (Bot wirkt etwas) wird zur gezeigten Karte
  useEffect(() => {
    if (stackTop) setLast(stackTop)
    // nur bei neuem Objekt, nicht bei jedem State
  }, [stackTop?.id])

  const base = hover ?? last
  // aktuellen Stand (getappt, Zaehler ...) aus dem Objektindex, falls die Karte noch im Spiel ist
  const card = base ? (objects.get(base.id) ?? base) : null

  return (
    <div className="flex shrink-0 flex-col gap-[9px] border-b border-line-2 px-4 py-3.5" data-testid="zoom-panel">
      <div className="self-center">
        {card ? (
          <CardView key={card.id} card={card} width={layout.zoomW} anchor={false} upright />
        ) : (
          <div
            className="flex aspect-[63/88] items-center justify-center rounded-md px-6 text-center text-[12.5px] leading-[1.4] text-fg-4 shadow-[inset_0_0_0_1px_var(--color-line-2)]"
            style={{ width: layout.zoomW }}
          >
            Fahre über eine Karte, um sie hier groß zu sehen.
          </div>
        )}
      </div>
      {/* Infobereich mit fester Hoehe: wechselnde Karten (Hover im Verlauf) verschieben den Verlauf darunter nicht */}
      <div className="flex h-[22px] items-center justify-between gap-2">
        <span className="min-w-0 truncate text-[15px] font-semibold text-fg-1">{card?.name}</span>
        <span className="flex shrink-0 gap-0.5">{card && <ManaCost cost={card.manaCost} flat />}</span>
      </div>
      <span className="-mt-1 h-[18px] truncate text-[12px] leading-[18px] text-fg-3">
        {card?.typeLine}
        {card && (card as Permanent).tapped && <span className="text-fg-4">{card.typeLine ? ' · ' : ''}getappt</span>}
      </span>
      {!layout.compact && (
        <div className="scrollbar-thin flex h-[152px] flex-col gap-1 overflow-y-auto text-[13px] leading-[1.45] text-fg-2">
          {card?.rules?.map((r, i) => (
            <div key={i}>
              <RulesText text={r} />
            </div>
          ))}
          {card?.back && <span className="text-[12px] text-fg-4">Rückseite: {card.back.name}</span>}
        </div>
      )}
      {layout.compact && <span className="h-[16px] truncate text-[12px] leading-[16px] text-fg-4">{card?.back && `Rückseite: ${card.back.name}`}</span>}
    </div>
  )
}
