import type { Card, PlayerState } from '../api/types'
import { Battlefield } from './Battlefield'
import type { Interaction } from './interaction'
import type { BoardLayoutState } from './layout'
import { PlayerInfo } from './PlayerInfo'

export interface MyAreaProps {
  /** eigener Spieler (beim Zuschauen: Blickwinkel-Spieler) */
  me: PlayerState
  inter: Interaction
  onHover: (c: Card | null) => void
  /** infoW, myPad, myGap, creatureW, landW */
  layout: BoardLayoutState
}

/**
 * Eigener Bereich: Raster aus Infospalte (PlayerInfo, infoW) und Feld (Kreaturen, Laender · Artefakte).
 * Am eigenen Zug liegt eine 3-px-Ember-Leiste ueber dem ganzen Bereich. Die Hand ist eine eigene Zeile (Hand).
 */
export function MyArea({ me, inter, onHover, layout }: MyAreaProps) {
  return (
    <div className="relative grid min-h-0 min-w-0 flex-1" style={{ gridTemplateColumns: `${layout.infoW}px minmax(0, 1fr)`, gridTemplateRows: 'minmax(0, 1fr)' }}>
      {me.active && <span className="pointer-events-none absolute inset-x-0 top-0 z-[4] h-[3px] bg-ember" aria-hidden />}
      <PlayerInfo p={me} inter={inter} onHover={onHover} layout={layout} />
      <Battlefield perms={me.battlefield} variant="own" inter={inter} onHover={onHover} layout={layout} />
    </div>
  )
}
