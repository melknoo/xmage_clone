import { Button, ProgressBar } from '../../components/ui'
import type { DeckInfo } from '../../decks/catalog'
import { Icon } from '../../lib/icons'
import { ColorPips } from '../../lib/mana'
import { DeckArt } from '../decks/DeckArt'
import type { DeckMasteryView } from '../decks/deckMastery'

/** "Dein Deck" im Spiel-Setup: Art 300x170 (200x112 unter 1440 px), Name + Farben, Commander · Herkunft, Meisterschaft. */
export function MyDeckCard({ info, mastery, onChange, onRandom }: { info: DeckInfo | null; mastery: DeckMasteryView | null; onChange: () => void; onRandom: () => void }) {
  const meta = info ? [info.kind === 'random' ? info.setLine : info.commander, info.src].filter(Boolean).join(' · ') : 'Lade Decks …'
  return (
    <div className="surface flex gap-6 p-[14px]">
      <DeckArt src={info?.art} className="h-[112px] w-[200px] flex-none rounded-sm board:h-[170px] board:w-[300px]">
        {info?.kind === 'random' && (
          <span className="absolute inset-0 flex items-center justify-center text-fg-4">
            <Icon name="random" size={28} />
          </span>
        )}
      </DeckArt>
      <div className="flex min-w-0 flex-1 flex-col gap-2.5 py-1.5">
        <div className="flex min-w-0 items-center gap-3">
          <span className="truncate font-display text-[30px] font-semibold leading-none text-fg-1">{info?.name ?? '–'}</span>
          {info && info.kind !== 'random' && (
            <span className="flex flex-none text-[15px] leading-none">
              <ColorPips colors={info.colors} flat />
            </span>
          )}
        </div>
        <span className="truncate text-[14px] text-fg-2">{meta}</span>
        <div className="flex max-w-[360px] items-center gap-3" title={mastery ? `Deck-Meisterschaft: ${mastery.text}` : 'Meisterschaft gibt es nur für eigene Decks'}>
          <span className="flex flex-none items-center gap-1 font-display text-[16px] font-semibold uppercase leading-none text-target">
            <Icon name="mastery" size={13} />
            {mastery ? `Stufe ${mastery.level}` : '–'}
          </span>
          <ProgressBar value={mastery ? mastery.pct : 0} max={1} height={3} tone="target" className="flex-1" />
        </div>
        <span className="flex-1" />
        <div className="flex gap-2">
          <Button icon="decks" testId="my-deck-change" onClick={onChange}>
            Deck wechseln
          </Button>
          <Button variant="ghost" icon="random" onClick={onRandom} title="Zufälliges eigenes Deck (ohne eigene Decks: ein vorgefertigtes)">
            Zufällig
          </Button>
        </div>
      </div>
    </div>
  )
}
