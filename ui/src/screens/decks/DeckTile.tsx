import { cardImageUrl } from '../../api/client'
import type { StoredDeck } from '../../api/decks'
import { Button, Chip, ProgressBar } from '../../components/ui'
import { Icon } from '../../lib/icons'
import { ColorPips } from '../../lib/mana'
import { DeckArt } from './DeckArt'
import { deckMastery } from './deckMastery'

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/** "{commanders} · N Karten · N Spiele · N Siege" bzw. "… · noch nicht gespielt" */
export function deckMeta(d: StoredDeck): string {
  const parts = [d.commanders.join(' & '), `${d.cardCount} Karten`]
  if (d.games !== undefined) parts.push(d.games > 0 ? `${plural(d.games, 'Spiel', 'Spiele')} · ${plural(d.wins ?? 0, 'Sieg', 'Siege')}` : 'noch nicht gespielt')
  return parts.filter(Boolean).join(' · ')
}

/** Kachel im Decks-Raster: Art 150 (110), Stufe-Chip, Name + Farben, Meta, Meisterschaft 3 px, Spielen + Bearbeiten/Löschen. */
export function DeckTile({ deck, onPlay, onEdit, onDelete }: { deck: StoredDeck; onPlay: () => void; onEdit: () => void; onDelete: () => void }) {
  const m = deckMastery(deck)
  const art = deck.commanderSet && deck.commanderNum ? cardImageUrl({ set: deck.commanderSet, num: deck.commanderNum }, { size: 'art_crop' }) : null
  const meta = deckMeta(deck)
  return (
    <div className="surface flex min-w-0 flex-col overflow-hidden" data-testid="deck-tile" data-deck-id={deck.id}>
      <DeckArt src={art} className="h-[110px] flex-none board:h-[150px]">
        <div className="absolute left-2.5 top-2.5 flex items-center gap-1.5">
          <Chip tone="onArt" icon="mastery" title={`Deck-Meisterschaft: ${m.text}`}>
            {`Stufe ${m.level}`}
          </Chip>
          {!deck.valid && (
            <Chip tone="attack" title={deck.validation || 'Nicht Commander-legal'} style={{ background: 'rgba(18,17,16,.85)' }}>
              Nicht legal
            </Chip>
          )}
        </div>
      </DeckArt>
      <div className="flex flex-col gap-2 px-4 py-3.5">
        <div className="flex min-w-0 items-center justify-between gap-2">
          <span className="truncate font-display text-[22px] font-semibold leading-none tracking-[.02em] text-fg-1" title={deck.name}>
            {deck.name}
          </span>
          <span className="flex flex-none text-[14px] leading-none">
            <ColorPips colors={deck.colors} flat />
          </span>
        </div>
        <span className="text-[12.5px] leading-[1.4] text-fg-3">{meta}</span>
        <div className="flex items-center gap-2.5">
          <ProgressBar value={m.pct} max={1} height={3} tone="target" className="flex-1" />
          <span className="whitespace-nowrap text-[12px] text-fg-3">{m.text}</span>
        </div>
        <div className="mt-1 flex gap-1.5">
          <Button variant="primary" icon="start" className="min-w-0 flex-1" style={{ height: 36, fontSize: 15, letterSpacing: '.08em' }} onClick={onPlay}>
            Spielen
          </Button>
          <button type="button" className="btn-tile" style={{ width: 36, height: 36 }} title="Bearbeiten" aria-label="Bearbeiten" data-testid="deck-edit" onClick={onEdit}>
            <Icon name="edit" size={16} />
          </button>
          <button type="button" className="btn-tile" style={{ width: 36, height: 36 }} title="Löschen" aria-label="Löschen" data-testid="deck-delete" onClick={onDelete}>
            <Icon name="delete" size={16} />
          </button>
        </div>
      </div>
    </div>
  )
}

/** Kachel "Deck importieren" am Ende des Rasters */
export function ImportTile({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      data-testid="deck-import-tile"
      className="outline-panel flex min-h-[240px] flex-col items-center justify-center gap-3 text-fg-3 transition-colors duration-1 hover:bg-bg-3 hover:text-fg-1 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-fg-1"
      onClick={onClick}
    >
      <Icon name="plus" size={30} />
      <span className="font-display text-[16px] font-semibold uppercase leading-none tracking-[.08em]">Deck importieren</span>
      <span className="text-[12.5px]">Link oder Textliste</span>
    </button>
  )
}
