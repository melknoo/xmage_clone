import { useState } from 'react'
import { Badge } from '../components/ui'
import { useSocial } from '../store/social'
import { FriendsPanel } from './FriendsPanel'
import { LobbyChat } from './LobbyChat'

type SideTab = 'chat' | 'friends'

const KEY = 'magelite.sideTab'

function loadTab(): SideTab {
  try {
    return localStorage.getItem(KEY) === 'friends' ? 'friends' : 'chat'
  } catch {
    return 'chat'
  }
}

/**
 * Rechte Leiste des Server-Helds (400, unter 1440 px Breite 320): Tabs "Lobby-Chat" | "Freunde"
 * (Badge = eingehende Anfragen). Liest die Stores selbst.
 */
export function SocialSidebar() {
  const incomingCount = useSocial((s) => s.incomingCount)
  const [tab, setTabState] = useState<SideTab>(loadTab)
  const setTab = (t: SideTab) => {
    setTabState(t)
    try {
      localStorage.setItem(KEY, t)
    } catch {
      /* egal */
    }
  }

  const items: { id: SideTab; label: string; testId: string; badge: number }[] = [
    { id: 'chat', label: 'Lobby-Chat', testId: 'side-tab-chat', badge: 0 },
    { id: 'friends', label: 'Freunde', testId: 'friends-tab', badge: incomingCount },
  ]

  return (
    <aside className="flex h-full min-h-0 w-[320px] flex-none flex-col border-l border-line-2 bg-bg-2 board:w-[400px]" data-testid="social-sidebar">
      <div role="tablist" className="tab-list flex-none px-[18px] pt-4" style={{ gap: 22 }}>
        {items.map((it) => (
          <button
            key={it.id}
            type="button"
            role="tab"
            aria-selected={tab === it.id}
            className="tab"
            style={{ paddingBottom: 11 }}
            data-testid={it.testId}
            title={it.id === 'friends' && it.badge > 0 ? `${it.badge} offene Anfrage${it.badge === 1 ? '' : 'n'}` : undefined}
            onClick={() => setTab(it.id)}
          >
            {it.label}
            <Badge count={it.badge} variant="tab" />
          </button>
        ))}
      </div>
      {tab === 'chat' ? <LobbyChat /> : <FriendsPanel />}
    </aside>
  )
}
