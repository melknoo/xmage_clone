import { useState } from 'react'
import { decksApi, type StoredDeck } from '../../api/decks'
import { Popover, Segmented, TextField } from '../../components/ui'
import { useCatalogStore } from '../../decks/catalog'
import { Icon } from '../../lib/icons'
import { pushToast } from '../../store/ui'
import { BRACKET_NAME, deckBracket } from './bracket'

type BracketPick = '0' | '1' | '2' | '3' | '4' | '5'

/**
 * Menue an der Deck-Kachel: in Ordner verschieben (vorhandene, ohne Ordner, neuer Ordner) und Bracket setzen
 * (Auto = Vorschlag der Engine). Aenderungen gehen sofort an POST /api/decks/{id}/meta.
 */
export function DeckMetaMenu({ deck, folders }: { deck: StoredDeck; folders: string[] }) {
  const [open, setOpen] = useState(false)
  const [newFolder, setNewFolder] = useState('')
  const replaceDeck = useCatalogStore((s) => s.replaceDeck)
  const current = deck.folder ?? ''

  const patch = async (meta: { folder?: string; bracket?: number }) => {
    try {
      replaceDeck(await decksApi.meta(deck.id, meta))
    } catch (e) {
      pushToast({ kind: 'error', text: e instanceof Error ? e.message : String(e) })
    }
  }
  const move = (folder: string) => {
    setOpen(false)
    if (folder !== current) void patch({ folder })
  }
  const auto = deckBracket({ bracketAuto: deck.bracketAuto })

  return (
    <div className="relative flex-none">
      <button
        type="button"
        className="btn-tile"
        style={{ width: 36, height: 36 }}
        title="Ordner und Bracket"
        aria-label="Ordner und Bracket"
        aria-expanded={open}
        data-testid="deck-meta"
        onClick={() => setOpen((o) => !o)}
      >
        <Icon name="library" size={16} />
      </button>
      <Popover open={open} onClose={() => setOpen(false)} width={260} align="right" variant="menu" offset={6} testId="deck-meta-menu">
        <div className="flex flex-col gap-2 border-b border-line-3 px-2.5 pb-3 pt-2">
          <span className="label" style={{ fontSize: 12 }}>
            Bracket
          </span>
          <Segmented<BracketPick>
            variant="boxed"
            ariaLabel="Bracket"
            value={String(deck.bracket ?? 0) as BracketPick}
            onChange={(id) => void patch({ bracket: Number(id) })}
            itemStyle={{ padding: '5px 8px' }}
            items={[
              { id: '0', label: 'Auto', title: auto ? `Vorschlag: ${auto.value} · ${BRACKET_NAME[auto.value]}` : 'Vorschlag der Engine', testId: 'bracket-auto' },
              ...[1, 2, 3, 4, 5].map((b) => ({ id: String(b) as BracketPick, label: String(b), title: BRACKET_NAME[b], testId: `bracket-${b}` })),
            ]}
          />
        </div>
        <span className="label block px-2.5 pb-1 pt-2.5" style={{ fontSize: 12 }}>
          In Ordner verschieben
        </span>
        {['', ...folders].map((f) => (
          <button
            key={f || '-'}
            type="button"
            className="flex w-full items-center gap-2 rounded-xs px-2.5 py-[8px] text-left text-[13.5px] leading-[1.2] text-fg-1 transition-colors duration-1 hover:bg-line-3"
            data-testid="deck-move"
            onClick={() => move(f)}
          >
            <Icon name={f === current ? 'chosen' : 'chevronRight'} size={14} className={f === current ? 'text-chosen' : 'text-fg-4'} />
            <span className={f ? 'truncate' : 'truncate text-fg-3'}>{f || 'Ohne Ordner'}</span>
          </button>
        ))}
        <form
          className="px-1.5 pb-1 pt-1.5"
          onSubmit={(e) => {
            e.preventDefault()
            const f = newFolder.trim()
            if (!f) return
            setNewFolder('')
            move(f)
          }}
        >
          <TextField placeholder="Neuer Ordner …" icon="plus" maxLength={40} value={newFolder} onChange={(e) => setNewFolder(e.target.value)} data-testid="deck-new-folder" />
        </form>
      </Popover>
    </div>
  )
}
