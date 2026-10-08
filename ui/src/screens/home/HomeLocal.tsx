import { Icon } from '../../lib/icons'
import { useNav } from '../../store/nav'
import { HeroHeader } from './HeroHeader'
import { MasteryList } from './MasteryList'
import { QuickStartCard } from './QuickStartCard'
import { RecentGames } from './RecentGames'

/**
 * Held-Screen im lokalen Modus (Meta-Prototyp "Held"): Held-Kopf, Schnellstart + "Neues Spiel" (1.5fr/1fr),
 * darunter "Letzte Partien" und "Deck-Meisterschaft". 1680: Rand 48/64, Abstand 28; unter 1440 px: 28/36, 18.
 * Passt bei den Design-Groessen ohne Scrollen; kleinere Fenster scrollen.
 */
export function HomeLocal() {
  const go = useNav((s) => s.go)
  const online = typeof window !== 'undefined' ? window.magelite?.openOnline : undefined
  const serverHost = (typeof window !== 'undefined' && window.magelite?.serverUrl ? window.magelite.serverUrl : 'https://magelite.fly.dev').replace(/^https?:\/\//, '')

  return (
    <div className="h-full overflow-y-auto scrollbar-thin" data-testid="home-local">
      <div className="flex min-h-full flex-col gap-[18px] px-9 py-7 board:gap-7 board:px-16 board:py-12">
        <HeroHeader variant="local" />
        <div className="grid gap-[18px] board:gap-7" style={{ gridTemplateColumns: 'minmax(0,1.5fr) minmax(0,1fr)' }}>
          <QuickStartCard />
          <button
            type="button"
            className="outline-panel flex h-[170px] cursor-pointer flex-col justify-between px-6 py-5 text-left text-fg-1 transition-colors duration-1 hover:bg-bg-3 board:h-[230px] board:px-8 board:py-7"
            onClick={() => go('play')}
            data-testid="home-new-game"
          >
            <Icon name="play" size={28} />
            <span className="flex w-full items-end justify-between gap-4">
              <span className="flex min-w-0 flex-col gap-2">
                <span className="font-display text-[36px] font-semibold uppercase leading-none tracking-[.02em]">Neues Spiel</span>
                <span className="text-[14px] leading-[1.4] text-fg-3">Deck und Gegner wählen</span>
              </span>
              <Icon name="chevronRight" size={24} className="flex-none text-fg-3" />
            </span>
          </button>
        </div>
        {online && (
          <button
            type="button"
            className="outline-panel flex cursor-pointer items-center gap-4 px-6 py-4 text-left text-fg-1 transition-colors duration-1 hover:bg-bg-3 board:px-8"
            onClick={() => online()}
            data-testid="home-online"
          >
            <Icon name="lobby" size={24} className="flex-none" />
            <span className="flex min-w-0 flex-1 flex-col gap-1">
              <span className="font-display text-[22px] font-semibold uppercase leading-none tracking-[.02em]">Online spielen · {serverHost}</span>
              <span className="text-[13px] leading-[1.4] text-fg-3">Mit Freunden an einem Tisch. Einmal angemeldet, kannst du dort Tische auf diesem Rechner hosten – deine Decks hier bleiben lokal, online nutzt du deine Server-Bibliothek.</span>
            </span>
            <Icon name="chevronRight" size={22} className="flex-none text-fg-3" />
          </button>
        )}
        <div className="grid min-h-[180px] flex-1 gap-[18px] board:gap-7" style={{ gridTemplateColumns: 'minmax(0,1.5fr) minmax(0,1fr)' }}>
          <RecentGames limit={3} withArt variant="local" />
          <MasteryList limit={5} />
        </div>
      </div>
    </div>
  )
}
