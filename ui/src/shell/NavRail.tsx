import { Badge, Wordmark } from '../components/ui'
import { Icon, type IconName } from '../lib/icons'
import { useAuth } from '../store/auth'
import { useGame } from '../store/game'
import { useNav, type Screen } from '../store/nav'
import { useSocial } from '../store/social'
import { useTable } from '../store/table'

interface NavItem {
  key: Screen
  label: string
  icon: IconName
}

const NAV: NavItem[] = [
  { key: 'home', label: 'Held', icon: 'held' },
  { key: 'play', label: 'Spielen', icon: 'play' },
  { key: 'decks', label: 'Decks', icon: 'decks' },
  { key: 'stats', label: 'Statistik', icon: 'stats' },
]
const ADMIN: NavItem = { key: 'admin', label: 'Einladungen', icon: 'invites' }

/** "Spielen" ist auch beim Solo-Setup (Server) und am Tisch aktiv */
function isActive(key: Screen, screen: Screen): boolean {
  return screen === key || (key === 'play' && (screen === 'solo' || screen === 'table'))
}

/**
 * Navigationsleiste links (<nav>, 84 px): ML, Held, Spielen, Decks, Statistik, (Admin) Einladungen, "Zum Tisch", Konto.
 * Aktiver Punkt: fg-1 + 2-px-Ember-Leiste am rechten Rand. Badge am Held: nur ungelesene Lobby-Chat-Nachrichten,
 * solange man im Chat und nicht auf dem Held ist (Server-Modus). Liest alle Stores selbst.
 */
export function NavRail() {
  const screen = useNav((s) => s.screen)
  const go = useNav((s) => s.go)
  const gameId = useGame((s) => s.gameId)
  const tableId = useTable((s) => s.tableId)
  const mode = useAuth((s) => s.mode)
  const me = useAuth((s) => s.me)
  const server = mode === 'server'
  const unread = useSocial((s) => s.unreadCount)
  const badge = server && screen !== 'home' ? unread : 0

  const items = server && me?.admin ? [...NAV, ADMIN] : NAV
  // Zum Tisch: laufendes Spiel -> Brett; sonst (Server) eigener Tisch
  const toTable: Screen | null = gameId ? 'game' : server && tableId ? 'table' : null
  const accountActive = screen === 'account'

  return (
    <nav className="flex w-[84px] flex-none flex-col items-center gap-1 border-r border-line-2 pt-[22px]" style={{ paddingBottom: server ? 14 : 16 }}>
      <Wordmark size={20} short className="mb-[22px]" />
      {items.map((n) => {
        const on = isActive(n.key, screen)
        return (
          <button
            key={n.key}
            type="button"
            aria-current={on ? 'page' : undefined}
            className={`relative flex w-full cursor-pointer flex-col items-center gap-1.5 py-3 transition-colors duration-1 ${on ? 'text-fg-1' : 'text-fg-tab hover:text-fg-2'}`}
            onClick={() => go(n.key)}
          >
            {on && <span className="absolute -right-px top-2 bottom-2 w-0.5 bg-ember" />}
            <span className="relative flex">
              <Icon name={n.icon} size={21} />
              {n.key === 'home' && (
                <Badge count={badge} max={9} className="absolute -right-3 -top-[7px]" title="Ungelesene Nachrichten im Lobby-Chat" />
              )}
            </span>
            <span className="font-display text-[12px] font-semibold uppercase leading-none tracking-[.1em]">{n.label}</span>
          </button>
        )
      })}
      <span className="flex-1" />
      {toTable && (
        <button
          type="button"
          className="flex w-[68px] cursor-pointer flex-col items-center gap-1.5 rounded-sm py-2.5 text-ember shadow-[inset_0_0_0_1px_var(--color-ember)] transition-colors duration-1 hover:bg-bg-4"
          style={{ marginBottom: server ? 8 : 0 }}
          onClick={() => go(toTable)}
          title={toTable === 'game' ? 'Zur laufenden Partie' : 'Zu deinem Tisch'}
        >
          <Icon name="toTable" size={19} />
          <span className="font-display text-[11px] font-semibold uppercase leading-none tracking-[.1em]">Zum Tisch</span>
        </button>
      )}
      {server && (
        <button
          type="button"
          aria-current={accountActive ? 'page' : undefined}
          className={`flex cursor-pointer flex-col items-center gap-1.5 transition-colors duration-1 ${accountActive ? 'text-fg-1' : 'text-fg-tab hover:text-fg-2'}`}
          onClick={() => go('account')}
          title={me ? `Angemeldet als ${me.name}${me.hasPassword ? '' : ' (Gast – Konto sichern)'}` : undefined}
        >
          <span
            className="flex h-[34px] w-[34px] items-center justify-center rounded-sm bg-bg-4 font-display text-[16px] font-semibold uppercase leading-none"
            style={{ boxShadow: accountActive ? '0 0 0 1px var(--color-ember)' : 'none' }}
          >
            {(me?.name ?? '?').trim().charAt(0) || '?'}
          </span>
          <span className="font-display text-[11px] font-semibold uppercase leading-none tracking-[.1em]">Konto</span>
        </button>
      )}
    </nav>
  )
}
