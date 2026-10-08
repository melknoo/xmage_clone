import { useEffect, useState } from 'react'
import { Button } from '../../components/ui'
import { useDeckCatalog } from '../../decks/catalog'
import { Icon, type IconName } from '../../lib/icons'
import { openLocalApp } from '../../lib/localApp'
import { SeatedStrip } from '../../social/SeatedStrip'
import { SocialSidebar } from '../../social/SocialSidebar'
import { useMarkLobbyRead } from '../../social/useMarkLobbyRead'
import { useAuth } from '../../store/auth'
import { useNav } from '../../store/nav'
import { useSocial } from '../../store/social'
import { HeroHeader } from './HeroHeader'
import { RecentGames } from './RecentGames'

const WIDE_QUERY = '(min-width: 1440px)'

/** Breite >= 1440 (Meta/Online-Breakpoint) */
function useWide(): boolean {
  const [wide, setWide] = useState(() => typeof window === 'undefined' || window.matchMedia(WIDE_QUERY).matches)
  useEffect(() => {
    const mq = window.matchMedia(WIDE_QUERY)
    const on = () => setWide(mq.matches)
    on()
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])
  return wide
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/**
 * Startseite im Server-Modus: links Gast-Hinweis, Held-Kopf (kompakt), Tisch-Leiste, Lobby / Allein ueben,
 * Letzte Partien; rechts Seitenleiste mit Lobby-Chat und Freunden. Solange der Screen offen ist, gilt der Chat als gelesen.
 */
export function HomeServer() {
  const go = useNav((s) => s.go)
  const lastSetup = useNav((s) => s.lastSetup)
  const me = useAuth((s) => s.me)
  const tables = useSocial((s) => s.tables)
  const online = useSocial((s) => s.online)
  const { describe } = useDeckCatalog()
  const wide = useWide()

  useMarkLobbyRead()

  const guest = !!me && !me.hasPassword
  const lobbySub =
    tables === null ? 'Tische eröffnen und beitreten' : tables === 0 ? 'Kein offener Tisch' : [plural(tables, 'Tisch', 'Tische'), online !== null ? `${online} Spieler online` : null].filter(Boolean).join(' · ')
  const soloDeck = lastSetup ? describe(lastSetup.deck)?.name : undefined
  const publicTier = me?.tier === 'public'
  const soloSub = publicTier
    ? 'Läuft in der MageLite-App auf deinem PC'
    : lastSetup && soloDeck
      ? `${soloDeck} · gegen ${plural(lastSetup.bots.length, 'Bot', 'Bots')}`
      : 'Deck und Gegner wählen'

  return (
    <div className="flex h-full min-h-0" data-testid="home-server">
      <div className="h-full min-w-0 flex-1 overflow-y-auto scrollbar-thin">
        <div className="flex min-h-full flex-col gap-4 px-8 py-[26px] board:gap-[26px] board:px-14 board:py-11">
          {guest && (
            <div className="flex items-center gap-3 rounded-sm px-3.5 py-2.5 text-[13.5px] leading-[1.4] text-fg-1" style={{ boxShadow: 'inset 0 0 0 1px var(--color-line-3)' }} data-testid="guest-row">
              <Icon name="human" size={16} className="flex-none text-fg-3" />
              <span className="min-w-0">Du spielst als Gast. Ohne E-Mail und Passwort kommst du nur mit deinem Einladungscode wieder rein.</span>
              <span className="flex-1" />
              <Button variant="secondary" size="sm" className="flex-none" testId="guest-secure" onClick={() => go('account')}>
                Konto sichern
              </Button>
            </div>
          )}
          <HeroHeader variant="server" />
          <SeatedStrip />
          <div className="grid grid-cols-2 gap-4 board:gap-[26px]">
            <CtaCard icon="lobby" title="Lobby" sub={lobbySub} filled testId="home-lobby" onClick={() => go('play')} />
            <CtaCard icon={publicTier ? 'desktop' : 'autoMana'} title="Allein üben" sub={soloSub} testId="home-solo" onClick={() => (publicTier ? void openLocalApp() : go('solo'))} />
          </div>
          <div className="flex min-h-0 flex-1 flex-col">
            <RecentGames limit={wide ? 3 : 2} variant="server" />
          </div>
        </div>
      </div>
      <SocialSidebar />
    </div>
  )
}

function CtaCard({ icon, title, sub, filled, testId, onClick }: { icon: IconName; title: string; sub: string; filled?: boolean; testId: string; onClick: () => void }) {
  return (
    <button
      type="button"
      className={`flex h-[124px] flex-col justify-between rounded-md px-[26px] py-6 text-left text-fg-1 transition-colors duration-1 board:h-[170px] ${filled ? 'bg-bg-3 hover:bg-bg-4' : 'hover:bg-bg-3'}`}
      style={filled ? undefined : { boxShadow: 'inset 0 0 0 1px var(--color-line-3)' }}
      data-testid={testId}
      onClick={onClick}
    >
      <Icon name={icon} size={26} className={filled ? 'text-ember' : 'text-fg-1'} />
      <span className="flex w-full items-end justify-between gap-4">
        <span className="flex min-w-0 flex-col gap-2">
          <span className="font-display text-[32px] font-semibold uppercase leading-none">{title}</span>
          <span className="truncate text-[14px] leading-[1.3] text-fg-3">{sub}</span>
        </span>
        <Icon name="chevronRight" size={22} className="flex-none text-fg-3" />
      </span>
    </button>
  )
}
