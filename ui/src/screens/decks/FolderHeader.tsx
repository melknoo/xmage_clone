import { useState } from 'react'
import { decksApi } from '../../api/decks'
import { TextField } from '../../components/ui'
import { invalidateDeckCatalog } from '../../decks/catalog'
import { Icon } from '../../lib/icons'
import { pushToast } from '../../store/ui'

const COLLAPSED_KEY = 'magelite.deckFoldersCollapsed'

/** eingeklappte Ordner ('' = ohne Ordner), pro Browser */
export function loadCollapsed(): Set<string> {
  try {
    const raw = localStorage.getItem(COLLAPSED_KEY)
    return new Set(raw ? (JSON.parse(raw) as string[]) : [])
  } catch {
    return new Set()
  }
}

export function saveCollapsed(s: Set<string>) {
  try {
    localStorage.setItem(COLLAPSED_KEY, JSON.stringify([...s]))
  } catch {
    // egal (z. B. privates Fenster)
  }
}

/** Abschnittskopf eines Deck-Ordners: Ein-/Ausklappen, Name, Anzahl, Umbenennen (leer = Ordner aufloesen). */
export function FolderHeader({ folder, count, collapsed, onToggle }: { folder: string; count: number; collapsed: boolean; onToggle: () => void }) {
  const [renaming, setRenaming] = useState(false)
  const [value, setValue] = useState(folder)

  const rename = async () => {
    const to = value.trim()
    setRenaming(false)
    if (to === folder) return
    try {
      await decksApi.renameFolder(folder, to)
      pushToast({ kind: 'success', text: to ? `Ordner umbenannt in ${to}` : `Ordner ${folder} aufgelöst` })
      invalidateDeckCatalog()
    } catch (e) {
      pushToast({ kind: 'error', text: e instanceof Error ? e.message : String(e) })
    }
  }

  return (
    <div className="flex min-h-[34px] items-center gap-2.5 border-b border-line-2 pb-2">
      <button
        type="button"
        className="flex min-w-0 items-center gap-2 text-fg-1 hover:text-fg-1"
        aria-expanded={!collapsed}
        data-testid="deck-folder-toggle"
        onClick={onToggle}
      >
        <Icon name={collapsed ? 'chevronRight' : 'chevronDown'} size={16} className="flex-none text-fg-3" />
        {!renaming && (
          <span className={`truncate font-display text-[20px] font-semibold uppercase leading-none tracking-[.06em] ${folder ? 'text-fg-1' : 'text-fg-3'}`}>{folder || 'Ohne Ordner'}</span>
        )}
      </button>
      {renaming ? (
        <form
          className="w-[260px]"
          onSubmit={(e) => {
            e.preventDefault()
            void rename()
          }}
        >
          <TextField
            autoFocus
            maxLength={40}
            value={value}
            placeholder="Leer = Ordner auflösen"
            onChange={(e) => setValue(e.target.value)}
            onBlur={() => void rename()}
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                e.preventDefault()
                e.stopPropagation()
                setValue(folder)
                setRenaming(false)
              }
            }}
            data-testid="deck-folder-name"
          />
        </form>
      ) : (
        <span className="text-[13px] text-fg-3">{count === 1 ? '1 Deck' : `${count} Decks`}</span>
      )}
      {folder && !renaming && (
        <button
          type="button"
          className="btn-tile"
          style={{ width: 28, height: 28 }}
          title="Ordner umbenennen (leer = auflösen)"
          aria-label="Ordner umbenennen"
          data-testid="deck-folder-rename"
          onClick={() => {
            setValue(folder)
            setRenaming(true)
          }}
        >
          <Icon name="edit" size={14} />
        </button>
      )}
    </div>
  )
}
