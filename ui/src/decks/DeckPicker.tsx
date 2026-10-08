import { useEffect, useMemo, useRef, useState } from 'react'
import { cardImageUrl } from '../api/client'
import type { DeckSpec, SampleDeck, StoredDeck } from '../api/types'
import { Button, Chip, Overlay, Tabs, TextField } from '../components/ui'
import { Icon } from '../lib/icons'
import { ColorPips } from '../lib/mana'
import { DeckArt } from '../screens/decks/DeckArt'
import { deckMastery } from '../screens/decks/deckMastery'
import { deckBracket } from '../screens/decks/bracket'
import { compareDecks } from '../screens/decks/order'

export interface DeckPickerProps {
  decks: StoredDeck[]
  samples: SampleDeck[]
  /** Bots: Kachel "Zufälliges Deck" (jede Partie neu) als erste Kachel unter "Vorgefertigt" */
  allowRandom: boolean
  onPick: (s: DeckSpec) => void
  onClose: () => void
  /** Zusatz im Kopf-Label: "Dein Deck" | "Platz 3" -> "Deck wählen · Platz 3" */
  forLabel?: string
  /** aktuell gewaehltes Deck (vorausgewaehlt, bestimmt den Start-Tab) */
  current?: DeckSpec | null
}

type PickTab = 'mine' | 'pre'

interface PickItem {
  key: string
  spec: DeckSpec
  name: string
  commander: string
  colors: string
  art: string | null
  setLine: string
  random?: boolean
  invalid?: boolean
  validation?: string
  hay: string
}

const keyOf = (s: DeckSpec | null | undefined) => (!s ? '' : s.type === 'random' ? 'random' : `${s.type}:${s.id}`)
const artOf = (set?: string, num?: string) => (set && num ? cardImageUrl({ set, num }, { size: 'art_crop' }) : null)
const SOURCE: Record<string, string> = { text: 'Textliste', archidekt: 'Archidekt', moxfield: 'Moxfield' }

function initialTab(decks: StoredDeck[], allowRandom: boolean, current?: DeckSpec | null): PickTab {
  if (current?.type === 'user') return 'mine'
  if (current?.type === 'sample' || current?.type === 'random') return 'pre'
  if (allowRandom) return 'pre'
  return decks.length ? 'mine' : 'pre'
}

/**
 * Deck-Auswahl als Meta-Overlay (innerhalb von <main>): Suche, "Zufällig", Tabs eigene/vorgefertigte Decks.
 * Klick waehlt nur aus; "Übernehmen" (Enter) oder Doppelklick uebernimmt, "Abbrechen" (Esc) / Scrim schliesst.
 * Fuer die Exit-Animation in <AnimatePresence> rendern.
 */
export function DeckPicker({ decks, samples, allowRandom, onPick, onClose, forLabel, current }: DeckPickerProps) {
  const [tab, setTab] = useState<PickTab>(() => initialTab(decks, allowRandom, current))
  const [q, setQ] = useState('')
  const [sel, setSel] = useState<DeckSpec | null>(current ?? null)

  const mineItems = useMemo<PickItem[]>(
    () =>
      // nach Ordner (ohne Ordner zuerst), darin eigene Reihenfolge wie unter "Decks"
      [...decks]
        .sort((a, b) => (a.folder ?? '').localeCompare(b.folder ?? '', 'de') || compareDecks(a, b))
        .map((d) => {
          const commander = d.commanders.join(' & ')
          const bracket = deckBracket(d)
          const setLine = [d.folder || 'Eigenes Deck', `Stufe ${deckMastery(d).level}`, bracket && `Bracket ${bracket.value}${bracket.auto ? '?' : ''}`].filter(Boolean).join(' · ')
          return {
            key: `user:${d.id}`,
            spec: { type: 'user', id: d.id },
            name: d.name,
            commander,
            colors: d.colors,
            art: artOf(d.commanderSet, d.commanderNum),
            setLine,
            invalid: !d.valid,
            validation: d.validation,
            hay: [d.name, commander, d.commanderSet ?? '', SOURCE[d.source] ?? d.source, d.folder ?? '', bracket ? `bracket ${bracket.value} b${bracket.value}` : ''].join(' ').toLowerCase(),
          }
        }),
    [decks],
  )
  const preItems = useMemo<PickItem[]>(
    () =>
      samples.map((s) => {
        const commander = s.commanders.join(' & ')
        const setLine = [s.group, s.commanderSet?.toUpperCase()].filter(Boolean).join(' · ')
        return {
          key: `sample:${s.id}`,
          spec: { type: 'sample', id: s.id },
          name: s.name,
          commander,
          colors: s.colors,
          art: artOf(s.commanderSet, s.commanderNum),
          setLine,
          hay: [s.name, commander, setLine].join(' ').toLowerCase(),
        }
      }),
    [samples],
  )

  const ql = q.trim().toLowerCase()
  const randomItem: PickItem = {
    key: 'random',
    spec: { type: 'random' },
    name: 'Zufälliges Deck',
    commander: 'Jede Partie neu gemischt',
    colors: '',
    art: null,
    setLine: 'Aus den mitgelieferten Decks',
    random: true,
    hay: 'zufällig zufälliges deck jede partie neu',
  }
  const base = tab === 'mine' ? mineItems : preItems
  const shown = (allowRandom && tab === 'pre' ? [randomItem, ...base] : base).filter((it) => !ql || it.hay.includes(ql))

  const selKey = keyOf(sel)
  const commit = (spec: DeckSpec | null = sel) => {
    if (spec) onPick(spec)
  }

  // Enter uebernimmt (auch aus der Suche); der Abbrechen-Knopf behaelt sein eigenes Enter
  const commitRef = useRef(commit)
  useEffect(() => {
    commitRef.current = commit
  })
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Enter' || e.repeat || e.ctrlKey || e.altKey || e.metaKey || e.defaultPrevented) return
      if ((e.target as HTMLElement | null)?.closest?.('[data-picker-cancel]')) return
      e.preventDefault()
      commitRef.current()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const pickRandom = () => {
    const pool = base.filter((it) => it.key !== selKey)
    const list = pool.length ? pool : base
    if (list.length) setSel(list[Math.floor(Math.random() * list.length)].spec)
  }

  return (
    <Overlay
      testId="deck-picker"
      label={forLabel ? `Deck wählen · ${forLabel}` : 'Deck wählen'}
      title="Deck-Auswahl"
      onClose={onClose}
      headerRight={
        <>
          <TextField autoFocus icon="search" className="w-[280px]" placeholder="Name, Commander oder Set" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Deck suchen" />
          <Button icon="random" onClick={pickRandom} disabled={!base.length} title="Zufälliges Deck aus diesem Tab auswählen">
            Zufällig
          </Button>
        </>
      }
      headerBelow={
        <Tabs<PickTab>
          className="flex-none px-5 pt-4"
          value={tab}
          onChange={setTab}
          items={[
            { id: 'mine', label: `Meine Decks ${decks.length}`, testId: 'picker-tab-mine' },
            { id: 'pre', label: `Vorgefertigt ${samples.length}`, testId: 'picker-tab-pre' },
          ]}
        />
      }
      footerHint={tab === 'mine' ? 'Eigene Decks mit Meisterschaft' : `${samples.length} mitgelieferte Commander-Decks`}
      footer={
        <>
          <span data-picker-cancel className="contents">
            <Button variant="ghost" kbd="Esc" testId="modal-cancel" onClick={onClose}>
              Abbrechen
            </Button>
          </span>
          <Button variant="primary" kbd="Enter" testId="picker-apply" disabled={!sel} onClick={() => commit()}>
            Übernehmen
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-[repeat(auto-fill,minmax(210px,1fr))] content-start gap-3.5 px-5 py-[18px]">
        {shown.map((it) => (
          <DeckTile
            key={it.key}
            name={it.name}
            sub={it.commander}
            colors={it.colors}
            art={it.art}
            setLine={it.setLine}
            random={it.random}
            warn={it.invalid}
            validation={it.validation}
            selected={it.key === selKey}
            onClick={() => setSel(it.spec)}
            onDoubleClick={() => commit(it.spec)}
          />
        ))}
        {shown.length === 0 && (
          <div className="col-span-full py-10 text-center text-[14px] leading-[1.5] text-fg-3">
            {ql ? `Kein Deck passt zu „${q.trim()}“.` : tab === 'mine' ? 'Noch keine eigenen Decks. Importiere eins unter „Decks“.' : 'Keine vorgefertigten Decks gefunden.'}
          </div>
        )}
      </div>
    </Overlay>
  )
}

export interface DeckTileProps {
  name: string
  /** Commander-Zeile */
  sub: string
  colors: string
  /** Kunst ueber Set/Nummer (alternativ `art`) */
  set?: string
  num?: string
  /** Set-Zeile (Fallback fuer setLine) */
  group?: string
  /** eigenes Deck nicht legal */
  warn?: boolean
  onClick: () => void
  /** Zusatz: fertige Art-URL (art_crop) */
  art?: string | null
  /** Zusatz: "Eigenes Deck · Stufe 3" | "Commander 2014 · C14" */
  setLine?: string
  selected?: boolean
  onDoubleClick?: () => void
  /** Zufalls-Kachel: Shuffle statt Kunst */
  random?: boolean
  /** Tooltip des "Nicht legal"-Chips */
  validation?: string
}

/** Kachel der Deck-Auswahl: Art 112, Name + Farben, Commander, Set-Zeile auf Vollflaeche; Auswahl = Ember-Kontur + Haken. */
export function DeckTile({ name, sub, colors, set, num, group, warn, onClick, art, setLine, selected, onDoubleClick, random, validation }: DeckTileProps) {
  const src = art !== undefined ? art : artOf(set, num)
  const line = setLine ?? group
  return (
    <button
      type="button"
      aria-pressed={!!selected}
      title={name}
      className={`relative flex min-w-0 flex-col overflow-hidden rounded-sm bg-bg-1 text-left transition-shadow duration-1 ${selected ? 'shadow-[0_0_0_2px_var(--color-ember)]' : 'shadow-[0_0_0_1px_var(--color-line-2)] hover:shadow-[0_0_0_1px_var(--color-line-4)]'}`}
      onClick={onClick}
      onDoubleClick={onDoubleClick}
    >
      <DeckArt src={random ? null : src} className="h-[112px] w-full flex-none">
        {random && (
          <span className="absolute inset-0 flex items-center justify-center text-fg-4">
            <Icon name="random" size={28} />
          </span>
        )}
        {selected && (
          <span className="absolute right-2 top-2 flex h-6 w-6 items-center justify-center rounded-xs bg-ember text-ember-ink">
            <Icon name="chosen" size={15} strokeWidth={2} />
          </span>
        )}
      </DeckArt>
      <span className="flex min-w-0 flex-col gap-[5px] px-3 pb-3 pt-2.5">
        <span className="flex min-w-0 items-center justify-between gap-1.5">
          <span className="truncate font-display text-[18px] font-semibold leading-none text-fg-1">{name}</span>
          {!random && (
            <span className="flex flex-none text-[12px] leading-none">
              <ColorPips colors={colors} flat />
            </span>
          )}
        </span>
        <span className="truncate text-[12px] text-fg-2">{sub}</span>
        <span className="flex min-w-0 items-center gap-1.5 text-[12px] text-fg-3">
          <span className="truncate">{line}</span>
          {warn && (
            <Chip tone="attack" size="xs" title={validation || 'Nicht Commander-legal'} className="flex-none">
              Nicht legal
            </Chip>
          )}
        </span>
      </span>
    </button>
  )
}
