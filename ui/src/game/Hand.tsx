import { memo } from 'react'
import type { Card } from '../api/types'
import { CardView } from '../components/CardView'
import type { Interaction } from './interaction'

export const Hand = memo(function Hand({ cards, inter, onHover }: { cards: Card[]; inter: Interaction; onHover: (c: Card | null) => void }) {
  const n = cards.length
  // Ueberlappung abhaengig von der Kartenanzahl
  const overlap = n <= 7 ? 8 : n <= 10 ? 34 : 54
  return (
    <div className="flex h-full items-end justify-center px-4">
      {cards.map((c, i) => {
        const h = inter.highlight(c.id)
        const mid = (n - 1) / 2
        const rot = n > 1 ? (i - mid) * Math.min(2.2, 16 / n) : 0
        const lift = Math.abs(i - mid) * Math.min(3, 20 / n)
        return (
          <div
            key={c.id}
            className="group relative transition-all duration-150 hover:z-20"
            style={{ marginLeft: i === 0 ? 0 : -overlap, transform: `translateY(${lift}px) rotate(${rot}deg)`, zIndex: i }}
          >
            <div className={`transition-transform duration-150 group-hover:-translate-y-6 group-hover:scale-110 ${h !== 'none' ? '-translate-y-2' : ''}`}>
              <CardView card={c} size="lg" highlight={h} onHover={onHover} onClick={() => inter.click(c.id)} />
            </div>
          </div>
        )
      })}
    </div>
  )
})
