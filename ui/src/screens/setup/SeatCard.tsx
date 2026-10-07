import { Button, Chip } from '../../components/ui'
import type { DeckInfo } from '../../decks/catalog'
import { Icon } from '../../lib/icons'
import { ColorPips } from '../../lib/mana'
import { DeckArt } from '../decks/DeckArt'

/** Gegner-Platz im Spiel-Setup: Art (fuellt die Hoehe), Chip "Platz n · Bot", Deck, Commander · Set, "Ändern" + Zufall. */
export function SeatCard({ seat, info, onChange, onShuffle }: { seat: number; info: DeckInfo | null; onChange: () => void; onShuffle: () => void }) {
  const random = !info || info.kind === 'random'
  const name = info?.name ?? 'Zufälliges Deck'
  const line = random ? (info?.setLine ?? 'Jede Partie neu gemischt') : [info.commander, info.setLine].filter(Boolean).join(' · ')
  return (
    <div data-testid="seat-card" data-seat={seat} className="surface flex min-w-0 flex-col overflow-hidden">
      <DeckArt src={random ? null : info.art} className="min-h-[80px] flex-1">
        {random && (
          <span className="absolute inset-0 flex items-center justify-center text-fg-4">
            <Icon name="random" size={28} />
          </span>
        )}
        <Chip tone="onArt" icon="bot" className="absolute left-2.5 top-2.5" style={{ color: 'var(--color-fg-2)', fontSize: 12, letterSpacing: '.08em', gap: 5 }}>
          {`Platz ${seat} · Bot`}
        </Chip>
      </DeckArt>
      <div className="flex flex-none flex-col gap-1.5 px-3.5 py-3">
        <div className="flex min-w-0 items-center justify-between gap-2">
          <span className="truncate font-display text-[20px] font-semibold leading-none text-fg-1" title={name}>
            {name}
          </span>
          {!random && (
            <span className="flex flex-none text-[14px] leading-none">
              <ColorPips colors={info.colors} flat />
            </span>
          )}
        </div>
        <span className="truncate text-[12.5px] text-fg-3" title={line}>
          {line}
        </span>
        <div className="mt-1 flex gap-1.5">
          <Button size="sm" className="min-w-0 flex-1" onClick={onChange}>
            Ändern
          </Button>
          <button type="button" className="btn-tile" title="Zufälliges vorgefertigtes Deck" aria-label="Zufälliges vorgefertigtes Deck" onClick={onShuffle}>
            <Icon name="random" size={16} />
          </button>
        </div>
      </div>
    </div>
  )
}
