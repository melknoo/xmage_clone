import { useEffect, useState } from 'react'
import { Badge } from '../components/ui'
import { useGame } from '../store/game'
import { ChatPanel } from './ChatPanel'
import type { BoardLayoutState } from './layout'
import { LogPanel } from './LogPanel'
import { ZoomPanel } from './ZoomPanel'

export interface SidePanelProps {
  /** side (Breite), zoomW, compact */
  layout: BoardLayoutState
}

type SideTab = 'important' | 'all' | 'chat'

/** Log-Tabs: Barlow 13, Unterstrich 3 px unter dem Text, Abstand 12 (kleiner als die Seiten-Tabs) */
const TAB_STYLE = { fontSize: 13, paddingBottom: 3 } as const

/**
 * Rechte Spalte: Kartenvorschau, darunter Spielverlauf mit den Tabs Wichtiges/Alles (store.logFilter) und
 * Chat (ab 2 Menschen bzw. fuer Zuschauer, mit Ungelesen-Zaehler). Ist der Chat-Tab sichtbar, gilt der Chat als gelesen.
 */
export function SidePanel({ layout }: SidePanelProps) {
  const [chatTab, setChatTab] = useState(false)
  const humans = useGame((s) => s.hello?.seats.filter((x) => x.human).length ?? 1)
  const spectator = useGame((s) => s.spectator)
  const unreadChat = useGame((s) => s.unreadChat)
  const setChatOpen = useGame((s) => s.setChatOpen)
  const logFilter = useGame((s) => s.logFilter)
  const setLogFilter = useGame((s) => s.setLogFilter)
  const chatAvailable = humans >= 2 || spectator
  const showChat = chatAvailable && chatTab
  const active: SideTab = showChat ? 'chat' : logFilter

  useEffect(() => setChatOpen(showChat), [showChat, setChatOpen])

  const tabs: { id: SideTab; label: string; testId?: string }[] = [
    { id: 'important', label: 'Wichtiges' },
    { id: 'all', label: 'Alles' },
    ...(chatAvailable ? [{ id: 'chat' as const, label: 'Chat', testId: 'side-tab-chat' }] : []),
  ]
  const pick = (t: SideTab) => {
    if (t === 'chat') {
      setChatTab(true)
      return
    }
    setChatTab(false)
    setLogFilter(t)
  }

  return (
    <aside className="flex min-h-0 shrink-0 flex-col border-l border-line-2 bg-bg-2" style={{ width: layout.side }} data-testid="game-side">
      <ZoomPanel layout={layout} />
      <div className="flex min-h-0 flex-1 flex-col px-4 py-3">
        <div className="mb-2 flex shrink-0 items-center justify-between gap-2">
          {/* kompakt mit drei Tabs ist kein Platz fuer die Ueberschrift */}
          {!(layout.compact && chatAvailable) && (
            <span className="label min-w-0 truncate" style={{ letterSpacing: '.14em' }}>
              {showChat ? 'Tisch-Chat' : 'Spielverlauf'}
            </span>
          )}
          <div role="tablist" className="flex items-end gap-3" title={showChat ? undefined : 'Wichtiges blendet Routine (Ziehen, Zugbeginn …) aus'}>
            {tabs.map((t) => (
              <button
                key={t.id}
                type="button"
                role="tab"
                aria-selected={active === t.id}
                className="tab"
                style={TAB_STYLE}
                data-testid={t.testId}
                onClick={() => pick(t.id)}
              >
                {t.label}
                {t.id === 'chat' && <Badge count={unreadChat} variant="nav" title="Ungelesene Nachrichten" />}
              </button>
            ))}
          </div>
        </div>
        <div className="min-h-0 flex-1">{showChat ? <ChatPanel /> : <LogPanel />}</div>
      </div>
    </aside>
  )
}
