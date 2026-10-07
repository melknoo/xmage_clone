import { AnimatePresence } from 'motion/react'
import { useEffect, useState } from 'react'
import { decksApi, type StoredDeck } from '../api/decks'
import { Button, EmptyState } from '../components/ui'
import { invalidateDeckCatalog, useDeckCatalog } from '../decks/catalog'
import { useAuth } from '../store/auth'
import { useNav } from '../store/nav'
import { pushToast } from '../store/ui'
import { DeckTile, ImportTile } from './decks/DeckTile'
import { DeleteDeckDialog } from './decks/DeleteDeckDialog'
import { ImportDialog } from './decks/ImportDialog'

/** Eigene Decks: Raster mit Meisterschaft, Import/Bearbeiten (Overlay) und Loeschen-Bestaetigung. */
export function DecksScreen() {
  const { decks, samples, loaded } = useDeckCatalog()
  const mode = useAuth((s) => s.mode)
  const go = useNav((s) => s.go)
  const setLastSetup = useNav((s) => s.setLastSetup)
  const lastSetup = useNav((s) => s.lastSetup)
  /** null = zu; deck null = neues Deck importieren */
  const [editor, setEditor] = useState<null | { deck: StoredDeck | null }>(null)
  const [del, setDel] = useState<StoredDeck | null>(null)
  const [deleting, setDeleting] = useState(false)

  // Held -> "Deck importieren" oeffnet den Import direkt
  useEffect(() => {
    if (useNav.getState().consumeIntent()?.decksOverlay === 'import') setEditor({ deck: null })
  }, [])

  const setupScreen = mode === 'server' ? 'solo' : 'play'
  const play = (d: StoredDeck) => {
    setLastSetup({ deck: { type: 'user', id: d.id }, bots: lastSetup?.bots ?? [{ type: 'random' }, { type: 'random' }, { type: 'random' }], tempo: lastSetup?.tempo ?? 'NORMAL' })
    go(setupScreen)
  }

  const remove = async () => {
    if (!del || deleting) return
    setDeleting(true)
    try {
      await decksApi.remove(del.id)
      pushToast({ kind: 'success', text: `${del.name} gelöscht` })
      if (editor?.deck?.id === del.id) setEditor(null)
      setDel(null)
      invalidateDeckCatalog()
    } catch (e) {
      pushToast({ kind: 'error', text: e instanceof Error ? e.message : String(e) })
    } finally {
      setDeleting(false)
    }
  }

  const openImport = () => setEditor({ deck: null })
  const empty = loaded && decks.length === 0
  const sorted = [...decks].sort((a, b) => b.updatedAt - a.updatedAt)

  return (
    <>
      <div className="flex h-full flex-col gap-[18px] overflow-y-auto px-9 py-7 scrollbar-thin board:gap-7 board:px-16 board:py-12">
        <div className="flex flex-none items-center gap-4">
          <h1 className="m-0 font-display text-[36px] font-semibold uppercase leading-none tracking-[.03em] text-fg-1">Decks</h1>
          {decks.length > 0 && <span className="text-[14px] text-fg-3">{decks.length === 1 ? '1 Deck' : `${decks.length} Decks`}</span>}
          <span className="flex-1" />
          {decks.length > 0 && (
            <Button variant="primary" icon="import" testId="deck-import" onClick={openImport}>
              Deck importieren
            </Button>
          )}
        </div>

        {empty ? (
          <EmptyState
            className="flex-1"
            testId="decks-empty"
            titleSize={32}
            icon="decks"
            title="Noch keine Decks"
            text={`Importiere ein Deck per Link von Archidekt oder Moxfield oder füge eine Textliste ein. Bis dahin kannst du mit einem der ${samples.length || 70} vorgefertigten Decks spielen.`}
            primary={
              <Button variant="primary" icon="import" testId="deck-import" onClick={openImport}>
                Erstes Deck importieren
              </Button>
            }
            secondary={
              <Button size="lg" style={{ fontSize: 16, letterSpacing: '.06em' }} onClick={() => go(setupScreen)}>
                Vorgefertigt spielen
              </Button>
            }
          />
        ) : (
          decks.length > 0 && (
            <div className="grid grid-cols-[repeat(auto-fill,minmax(250px,1fr))] gap-[18px] board:grid-cols-[repeat(auto-fill,minmax(300px,1fr))] board:gap-7">
              {sorted.map((d) => (
                <DeckTile key={d.id} deck={d} onPlay={() => play(d)} onEdit={() => setEditor({ deck: d })} onDelete={() => setDel(d)} />
              ))}
              <ImportTile onClick={openImport} />
            </div>
          )
        )}
      </div>

      <AnimatePresence>
        {editor && (
          <ImportDialog
            key={editor.deck ? `edit-${editor.deck.id}` : 'import'}
            edit={editor.deck}
            // Esc landet beim Overlay-Handler: liegt die Loeschen-Bestaetigung darueber, schliesst nur sie
            onClose={() => (del ? setDel(null) : setEditor(null))}
            onSaved={() => {
              setEditor(null)
              invalidateDeckCatalog()
            }}
            onRequestDelete={editor.deck ? () => setDel(editor.deck) : undefined}
          />
        )}
      </AnimatePresence>
      <AnimatePresence>{del && <DeleteDeckDialog key="del" name={del.name} busy={deleting} scrim={!editor} onCancel={() => setDel(null)} onConfirm={() => void remove()} />}</AnimatePresence>
    </>
  )
}
