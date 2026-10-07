import { useCallback } from 'react'
import type { Card } from '../api/types'
import { BoardModal } from '../components/BoardModal'
import { CardView } from '../components/CardView'
import { Button, Tabs, type TabItem } from '../components/ui'
import { useGame, type ZoneView } from '../store/game'
import type { Interaction } from './interaction'
import type { BoardLayoutState } from './layout'

export interface ZoneViewerProps {
  inter: Interaction
  onHover: (c: Card | null) => void
  /** modal.grave (Breite), graveW (Kartenbreite) */
  layout: BoardLayoutState
}

type Tab = ZoneView['tab']

const TAB_NAME: Record<Tab, string> = { gy: 'Friedhof', ex: 'Exil', lib: 'Oberste Karte' }

/**
 * Zonen-Ansicht (Friedhof / Exil / oberste Bibliothekskarte) eines Spielers als minimierbarer Ansichts-Dialog mit
 * Tabs; geoeffnet ueber store.viewer (ZoneCounter ruft setViewer). Neueste Karte zuerst. Eine waehlbare Karte
 * wird per Klick gewaehlt (inter.click), danach schliesst die Ansicht.
 */
export function ZoneViewer({ inter, onHover, layout }: ZoneViewerProps) {
  const viewer = useGame((s) => s.viewer)
  const setViewer = useGame((s) => s.setViewer)
  const spectator = useGame((s) => s.spectator)
  const p = useGame((s) => (s.viewer ? s.state?.players.find((x) => x.id === s.viewer?.playerId) : undefined))
  const close = useCallback(() => setViewer(null), [setViewer])
  if (!viewer || !p) return null

  const top = p.topCard
  const tab: Tab = viewer.tab === 'lib' && !top ? 'gy' : viewer.tab
  const who = p.me && !spectator ? 'Du' : p.name
  // Auswahl aus der Ansicht: danach schliessen, damit das Spielfeld wieder frei ist
  const pick = (id: string) => {
    if (spectator || !inter.canClick(id)) return
    inter.click(id)
    close()
  }
  const items: TabItem<Tab>[] = [
    { id: 'gy', label: `Friedhof ${p.graveyard.length}` },
    { id: 'ex', label: `Exil ${p.exile.length}` },
  ]
  if (top) items.push({ id: 'lib', label: 'Oberste Karte' })
  const cards = tab === 'gy' ? p.graveyard : p.exile

  return (
    <BoardModal
      label="Zonen-Ansicht"
      title={`${TAB_NAME[tab]} · ${who}`}
      width={layout.modal.grave}
      viewer
      minimizable
      onClose={close}
      footer={
        <Button variant="secondary" kbd="Esc" onClick={close}>
          Schließen
        </Button>
      }
    >
      <div className="sticky top-0 z-10 bg-bg-3">
        <Tabs items={items} value={tab} onChange={(t) => setViewer({ playerId: p.id, tab: t })} className="px-5 pt-3" />
      </div>
      {tab === 'lib' && top ? (
        <div className="flex items-start gap-5 px-5 py-[18px]">
          <CardView card={top} width={layout.graveW * 2} highlight={inter.highlight(top.id)} onHover={onHover} onClick={() => pick(top.id)} />
          <div className="flex min-w-0 flex-col gap-2 text-[13.5px] leading-snug text-fg-2">
            <span className="text-[14px] font-semibold text-fg-1">{top.name}</span>
            <span>
              Oberste Karte von {p.library} · {p.topCardPrivate ? 'nur für dich sichtbar' : 'für alle aufgedeckt'}
            </span>
            {!spectator && inter.canClick(top.id) && <span className="text-ember">Anklicken, um sie zu spielen</span>}
          </div>
        </div>
      ) : cards.length === 0 ? (
        <div className="px-5 py-[18px] text-[13.5px] text-fg-3">{tab === 'gy' ? 'Der Friedhof ist leer.' : 'Im Exil liegt nichts.'}</div>
      ) : (
        <div className="flex flex-wrap gap-[14px] px-5 py-[18px]">
          {[...cards].reverse().map((c) => (
            <div key={c.id} className="flex flex-col gap-[7px]" style={{ width: layout.graveW }}>
              <CardView card={c} width={layout.graveW} highlight={inter.highlight(c.id)} onHover={onHover} onClick={() => pick(c.id)} />
              <span className="truncate text-[12px] text-fg-2" title={c.name}>
                {c.name}
              </span>
            </div>
          ))}
        </div>
      )}
    </BoardModal>
  )
}
