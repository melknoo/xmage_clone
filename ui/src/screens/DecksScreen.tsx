import { AnimatePresence } from 'motion/react'
import { useEffect, useState, type DragEvent } from 'react'
import { decksApi, type StoredDeck } from '../api/decks'
import { Button, EmptyState, Segmented } from '../components/ui'
import { invalidateDeckCatalog, useCatalogStore, useDeckCatalog } from '../decks/catalog'
import { useAuth } from '../store/auth'
import { useNav } from '../store/nav'
import { pushToast } from '../store/ui'
import { deckBracket } from './decks/bracket'
import { sortDecks } from './decks/order'
import { DeckTile, ImportTile } from './decks/DeckTile'
import { FolderHeader, loadCollapsed, saveCollapsed } from './decks/FolderHeader'
import { DeleteDeckDialog } from './decks/DeleteDeckDialog'
import { ImportDialog } from './decks/ImportDialog'

/** Ablageziel: Ordner, optional neben einem Deck (after = dahinter); ohne id = ans Ende des Ordners */
type DropTarget = { folder: string; id?: number; after?: boolean }

type BracketFilter = 'all' | '1' | '2' | '3' | '4' | '5'
const BRACKET_FILTERS: BracketFilter[] = ['all', '1', '2', '3', '4', '5']

/** Eigene Decks: Ordner-Abschnitte (einklappbar) mit Raster mit Meisterschaft, Import/Bearbeiten (Overlay) und Loeschen-Bestaetigung. */
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
  /** Bracket-Filter: 'all' oder '1'..'5' (gueltige Bracket: manuell, sonst Vorschlag) */
  const [bracketFilter, setBracketFilter] = useState<BracketFilter>('all')
  const [collapsed, setCollapsed] = useState<Set<string>>(loadCollapsed)
  /** Drag & Drop: gezogenes Deck und Ziel unter dem Zeiger */
  const [dragId, setDragId] = useState<number | null>(null)
  const [drop, setDrop] = useState<DropTarget | null>(null)
  const applyOrder = useCatalogStore((s) => s.applyOrder)

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
  const sorted = sortDecks(decks)
  const folders = [...new Set(decks.map((d) => d.folder ?? '').filter(Boolean))].sort((a, b) => a.localeCompare(b, 'de'))
  const shown = bracketFilter === 'all' ? sorted : sorted.filter((d) => String(deckBracket(d)?.value ?? '') === bracketFilter)
  // "Ohne Ordner" zuerst, dann Ordner alphabetisch; ohne Ordner gar keine Abschnittskoepfe
  // beim Ziehen auch leere Abschnitte zeigen (z. B. "Ohne Ordner" als Ziel)
  const sections = ['', ...folders]
    .map((f) => ({ folder: f, decks: shown.filter((d) => (d.folder ?? '') === f) }))
    .filter((sec) => sec.decks.length > 0 || dragId !== null || (sec.folder === '' && folders.length === 0))
  const dragged = dragId === null ? null : decks.find((d) => d.id === dragId) ?? null
  const endDrag = () => {
    setDragId(null)
    setDrop(null)
  }
  /** Ablegen: Deck in den Ordner, vor/hinter das Ziel-Deck bzw. ans Ende; speichert die Reihenfolge des Ordners */
  const dropAt = async (target: DropTarget) => {
    const d = dragged
    endDrag()
    if (!d) return
    // Reihenfolge ueber alle Decks des Ordners (auch die vom Bracket-Filter ausgeblendeten)
    const current = sorted.filter((x) => (x.folder ?? '') === target.folder).map((x) => x.id)
    const ids = current.filter((id) => id !== d.id)
    const at = target.id === undefined ? ids.length : ids.indexOf(target.id) + (target.after ? 1 : 0)
    ids.splice(at < 0 ? ids.length : at, 0, d.id)
    const moved = (d.folder ?? '') !== target.folder
    if (!moved && ids.every((id, i) => id === current[i])) return
    applyOrder(target.folder, ids)
    try {
      await decksApi.order(target.folder, ids)
      if (moved) pushToast({ kind: 'success', text: target.folder ? `${d.name} → ${target.folder}` : `${d.name} → ohne Ordner` })
    } catch (e) {
      invalidateDeckCatalog()
      pushToast({ kind: 'error', text: e instanceof Error ? e.message : String(e) })
    }
  }
  const sameDrop = (a: DropTarget | null, b: DropTarget) => !!a && a.folder === b.folder && a.id === b.id && a.after === b.after
  const over = (e: DragEvent, target: DropTarget) => {
    e.preventDefault()
    e.stopPropagation()
    e.dataTransfer.dropEffect = 'move'
    if (!sameDrop(drop, target)) setDrop(target)
  }
  /** Drop-Ziel Ordner-Abschnitt: ans Ende (nur fuer Decks aus anderen Ordnern) */
  const sectionDrop = (folder: string) => ({
    onDragOver: (e: DragEvent) => {
      if (dragged && (dragged.folder ?? '') !== folder) over(e, { folder })
    },
    onDragLeave: (e: DragEvent) => {
      if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDrop((cur) => (cur?.folder === folder ? null : cur))
    },
    onDrop: (e: DragEvent) => {
      e.preventDefault()
      e.stopPropagation()
      // in einer Luecke losgelassen: zuletzt angezeigte Markierung in diesem Ordner gilt
      if (drop && drop.folder === folder) void dropAt(drop)
      else if (dragged && (dragged.folder ?? '') !== folder) void dropAt({ folder })
      else endDrag()
    },
  })
  /** Drop-Ziel Kachel: linke Haelfte = davor, rechte = dahinter */
  const tileDrop = (d: StoredDeck) => {
    const target = (e: DragEvent): DropTarget => {
      const r = e.currentTarget.getBoundingClientRect()
      return { folder: d.folder ?? '', id: d.id, after: e.clientX > r.left + r.width / 2 }
    }
    return {
      onDragOver: (e: DragEvent) => {
        if (dragged && dragged.id !== d.id) over(e, target(e))
      },
      onDrop: (e: DragEvent) => {
        e.preventDefault()
        e.stopPropagation()
        if (dragged && dragged.id !== d.id) void dropAt(target(e))
        else endDrag()
      },
    }
  }
  const toggleFolder = (f: string) =>
    setCollapsed((cur) => {
      const next = new Set(cur)
      if (next.has(f)) next.delete(f)
      else next.add(f)
      saveCollapsed(next)
      return next
    })
  const grid = (list: StoredDeck[], withImport: boolean) => (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(250px,1fr))] gap-[18px] board:grid-cols-[repeat(auto-fill,minmax(300px,1fr))] board:gap-7">
      {list.map((d) => (
        <div key={d.id} className="relative grid min-w-0" {...tileDrop(d)}>
          {drop?.id === d.id && (
            <span
              className="pointer-events-none absolute bottom-0 top-0 z-10 w-[3px] rounded-xs bg-ember"
              style={drop.after ? { right: -8 } : { left: -8 }}
              data-testid="deck-drop-marker"
              aria-hidden
            />
          )}
          <DeckTile
            deck={d}
            folders={folders}
            onPlay={() => play(d)}
            onEdit={() => setEditor({ deck: d })}
            onDelete={() => setDel(d)}
            onDragStart={() => setDragId(d.id)}
            onDragEnd={endDrag}
            dragging={dragId === d.id}
          />
        </div>
      ))}
      {withImport && <ImportTile onClick={openImport} />}
    </div>
  )

  return (
    <>
      <div className="flex h-full flex-col gap-[18px] overflow-y-auto px-9 py-7 scrollbar-thin board:gap-7 board:px-16 board:py-12">
        <div className="flex flex-none items-center gap-4">
          <h1 className="m-0 font-display text-[36px] font-semibold uppercase leading-none tracking-[.03em] text-fg-1">Decks</h1>
          {decks.length > 0 && <span className="text-[14px] text-fg-3">{decks.length === 1 ? '1 Deck' : `${decks.length} Decks`}</span>}
          <span className="flex-1" />
          {decks.length > 0 && (
            <Segmented<BracketFilter>
              variant="inline"
              ariaLabel="Bracket-Filter"
              value={bracketFilter}
              onChange={setBracketFilter}
              items={BRACKET_FILTERS.map((id) => ({ id, label: id === 'all' ? 'Alle' : `B${id}`, title: id === 'all' ? 'Alle Brackets' : `Bracket ${id}`, testId: `bracket-filter-${id}` }))}
            />
          )}
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
          decks.length > 0 &&
          (folders.length === 0 ? (
            grid(shown, true)
          ) : (
            <>
              {sections.map((sec) => (
                <section
                  key={sec.folder || '-'}
                  className="-m-2 flex flex-col gap-3 rounded-md p-2 transition-shadow duration-1 board:gap-4"
                  style={drop?.folder === sec.folder && drop.id === undefined ? { boxShadow: 'inset 0 0 0 2px var(--color-ember)', background: 'color-mix(in oklab, var(--color-ember) 6%, transparent)' } : undefined}
                  data-testid="deck-folder"
                  data-folder={sec.folder}
                  data-drop={(drop?.folder === sec.folder && drop.id === undefined) || undefined}
                  {...sectionDrop(sec.folder)}
                >
                  <FolderHeader folder={sec.folder} count={sec.decks.length} collapsed={collapsed.has(sec.folder)} onToggle={() => toggleFolder(sec.folder)} />
                  {!collapsed.has(sec.folder) &&
                    (sec.decks.length > 0 ? (
                      grid(sec.decks, false)
                    ) : (
                      <div className="flex h-[72px] items-center justify-center rounded-md text-[13px] text-fg-3 shadow-[inset_0_0_0_1px_var(--color-line-3)]">Hierher ziehen</div>
                    ))}
                </section>
              ))}
              {sections.length === 0 && <span className="text-[14px] text-fg-3">Keine Decks mit dieser Bracket.</span>}
              <div className="grid grid-cols-[repeat(auto-fill,minmax(250px,1fr))] gap-[18px] board:grid-cols-[repeat(auto-fill,minmax(300px,1fr))] board:gap-7">
                <ImportTile onClick={openImport} />
              </div>
            </>
          ))
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
