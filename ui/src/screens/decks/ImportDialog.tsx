import { useEffect, useRef, useState, type ReactNode } from 'react'
import { ApiError, cardImageUrl } from '../../api/client'
import { decksApi, type DeckCardType, type DeckIssue, type DeckPreview, type StoredDeck } from '../../api/decks'
import { Button, Chip, Overlay, Segmented, TextField } from '../../components/ui'
import { useCatalogStore } from '../../decks/catalog'
import { Icon } from '../../lib/icons'
import { pushToast } from '../../store/ui'
import { BRACKET_NAME, reasonLines } from './bracket'
import { DeckArt } from './DeckArt'

type ImportTab = 'link' | 'text'
type BracketPick = '0' | '1' | '2' | '3' | '4' | '5'

/** Gruppen der Vorschau in Anzeige-Reihenfolge (Commander kommt separat davor) */
const GROUPS: { type: DeckCardType; label: string }[] = [
  { type: 'creature', label: 'Kreaturen' },
  { type: 'planeswalker', label: 'Planeswalker' },
  { type: 'instant', label: 'Spontanzauber' },
  { type: 'sorcery', label: 'Hexereien' },
  { type: 'artifact', label: 'Artefakte' },
  { type: 'enchantment', label: 'Verzauberungen' },
  { type: 'battle', label: 'Schlachten' },
  { type: 'land', label: 'Länder' },
  { type: 'other', label: 'Sonstige' },
]
const MAX_ISSUES = 3
const PREVIEW_DELAY_MS = 600

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e))
const cardList = (cards: { name: string; count: number }[]) => cards.map((c) => (c.count > 1 ? `${c.count} ${c.name}` : c.name)).join(', ')
const sum = (cards: { count: number }[]) => cards.reduce((n, c) => n + c.count, 0)

/**
 * Deck importieren (Link oder Textliste) bzw. bearbeiten (edit gesetzt). Die Vorschau laeuft automatisch
 * (600 ms nach der letzten Aenderung, im Bearbeiten-Modus sofort); veraltete Antworten werden verworfen.
 * Fuer die Exit-Animation in <AnimatePresence> rendern.
 */
export function ImportDialog({ edit, onClose, onSaved, onRequestDelete }: { edit?: StoredDeck | null; onClose: () => void; onSaved: (deck: StoredDeck | null) => void; onRequestDelete?: () => void }) {
  const [tab, setTab] = useState<ImportTab>(edit ? 'text' : 'link')
  const [url, setUrl] = useState(edit?.sourceUrl ?? '')
  const [urlBusy, setUrlBusy] = useState(false)
  const [urlError, setUrlError] = useState<string | null>(null)
  const [name, setName] = useState(edit?.name ?? '')
  const [text, setText] = useState('')
  const [textLoading, setTextLoading] = useState(!!edit)
  const [commanders, setCommanders] = useState<string[]>([])
  const [source, setSource] = useState<{ source?: string; sourceUrl?: string }>({ source: edit?.source, sourceUrl: edit?.sourceUrl })
  const [preview, setPreview] = useState<DeckPreview | null>(null)
  const [previewBusy, setPreviewBusy] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [folder, setFolder] = useState(edit?.folder ?? '')
  /** 0 = Vorschlag der Engine gilt */
  const [bracket, setBracket] = useState(edit?.bracket ?? 0)
  const decks = useCatalogStore((s) => s.decks)
  const folders = [...new Set(decks.map((d) => d.folder ?? '').filter(Boolean))].sort((a, b) => a.localeCompare(b, 'de'))

  // Vorschau: Schluessel = Liste + Commander-Wahl; seq verwirft veraltete Antworten
  const seq = useRef(0)
  const lastKey = useRef('')
  const immediate = useRef(!!edit)

  useEffect(() => {
    if (!edit) return
    let stop = false
    decksApi
      .text(edit.id)
      .then((r) => !stop && setText(r.text))
      .catch((e) => !stop && setPreviewError(errText(e)))
      .finally(() => !stop && setTextLoading(false))
    return () => {
      stop = true
    }
  }, [edit])

  useEffect(() => {
    const key = JSON.stringify([text, commanders])
    if (key === lastKey.current) return
    if (!text.trim()) {
      lastKey.current = key
      seq.current++
      setPreview(null)
      setPreviewError(null)
      setPreviewBusy(false)
      return
    }
    const delay = immediate.current ? 0 : PREVIEW_DELAY_MS
    immediate.current = false
    const t = window.setTimeout(() => {
      lastKey.current = key
      const my = ++seq.current
      setPreviewBusy(true)
      decksApi
        .parse(text, name.trim() || undefined, commanders.length ? commanders : undefined)
        .then((p) => {
          if (my !== seq.current) return
          setPreview(p)
          setPreviewError(null)
        })
        .catch((e) => {
          if (my === seq.current) setPreviewError(errText(e))
        })
        .finally(() => {
          if (my === seq.current) setPreviewBusy(false)
        })
    }, delay)
    return () => window.clearTimeout(t)
    // name bewusst nicht: er aendert den Inhalt der Vorschau nicht
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, commanders])

  const fetchUrl = async () => {
    const u = url.trim()
    if (!u || urlBusy) return
    setUrlBusy(true)
    setUrlError(null)
    try {
      let p: DeckPreview
      try {
        p = await decksApi.fromUrl(u)
      } catch (e) {
        // Moxfield/Cloudflare blockt Server-Abrufe -> ueber Electron (Chromium) laden
        const data = e instanceof ApiError ? (e.data as { blocked?: boolean; apiUrl?: string } | null) : null
        const blocked = !!data?.blocked || (e instanceof ApiError && /blockiert/.test(e.message))
        if (!blocked || !window.magelite?.fetchText) throw e
        const apiUrl = data?.apiUrl ?? (u.includes('moxfield') ? `https://api2.moxfield.com/v3/decks/all/${u.split('/decks/')[1]?.split(/[/?#]/)[0]}` : u)
        const json = await window.magelite.fetchText(apiUrl)
        p = await decksApi.fromUrl(u, json)
      }
      const t = p.text ?? ''
      // diese Vorschau gehoert schon zur neuen Liste: kein zweiter Abruf
      lastKey.current = JSON.stringify([t, []])
      seq.current++
      setText(t)
      setCommanders([])
      setName(p.name)
      setSource({ source: p.source, sourceUrl: p.sourceUrl ?? u })
      // vom Deck-Autor gesetzte Bracket (Archidekt/Moxfield) uebernehmen
      if (p.bracket) setBracket(p.bracket)
      setPreview(p)
      setPreviewError(null)
      setPreviewBusy(false)
      setTab('text')
    } catch (e) {
      const msg = errText(e)
      setUrlError(u.includes('moxfield') ? `${msg} – Moxfield blockiert evtl. den Abruf. Alternative: In Moxfield „Export → Text“ kopieren und hier einfügen.` : msg)
    } finally {
      setUrlBusy(false)
    }
  }

  const toggleCommander = (c: string) => {
    immediate.current = true
    setCommanders((cur) => (cur.includes(c) ? cur.filter((x) => x !== c) : [...cur, c].slice(-2)))
  }

  /** "Meintest du …?": ersetzt den Namen in der genannten Zeile */
  const applySuggestion = (issue: DeckIssue) => {
    if (!issue.suggestion) return
    const lines = text.split(/\r?\n/)
    const i = issue.line - 1
    if (i < 0 || i >= lines.length) return
    const line = lines[i]
    const at = line.toLowerCase().indexOf(issue.name.toLowerCase())
    lines[i] = at >= 0 ? line.slice(0, at) + issue.suggestion + line.slice(at + issue.name.length) : `${issue.count} ${issue.suggestion}`
    immediate.current = true
    setText(lines.join('\n'))
    setTab('text')
  }

  const save = async () => {
    if (!preview || saving) return
    setSaving(true)
    try {
      const deck = await decksApi.save({
        id: edit?.id,
        name: name.trim() || preview.name,
        text,
        commanders: commanders.length ? commanders : undefined,
        source: source.source ?? 'text',
        sourceUrl: source.sourceUrl,
        folder: folder.trim(),
        bracket,
      })
      pushToast({ kind: 'success', text: edit ? 'Deck gespeichert' : 'Deck importiert' })
      onSaved(deck ?? null)
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
    } finally {
      setSaving(false)
    }
  }

  const canSave = !!preview && preview.commanders.length > 0 && !!text.trim() && !saving && !textLoading

  return (
    <Overlay
      testId="import-dialog"
      label={edit ? 'Deck bearbeiten' : 'Deck importieren'}
      title={edit ? edit.name : 'Neues Deck'}
      onClose={onClose}
      footerHint={
        edit && onRequestDelete ? (
          <Button variant="danger" icon="delete" testId="import-delete" onClick={onRequestDelete}>
            Löschen
          </Button>
        ) : undefined
      }
      footer={
        <>
          <Button variant="ghost" kbd="Esc" testId="modal-cancel" onClick={onClose}>
            Abbrechen
          </Button>
          <Button variant="primary" testId="import-submit" disabled={!canSave} onClick={save}>
            {saving ? 'Speichere …' : edit ? 'Speichern' : 'Importieren'}
          </Button>
        </>
      }
    >
      <div className="grid h-full min-h-0 grid-cols-2">
        {/* links: Quelle */}
        <div className="flex min-h-0 min-w-0 flex-col gap-4 border-r border-line-2 px-5 py-[18px]">
          <Segmented<ImportTab>
            variant="boxed"
            className="self-start"
            ariaLabel="Quelle"
            value={tab}
            onChange={setTab}
            items={[
              { id: 'link', label: 'Link', testId: 'import-tab-link' },
              { id: 'text', label: 'Textliste', testId: 'import-tab-text' },
            ]}
          />
          {tab === 'link' ? (
            <div className="flex flex-col gap-[7px]">
              <label htmlFor="import-url" className="label">
                Deck-Link
              </label>
              <div className="flex gap-2">
                <TextField
                  id="import-url"
                  autoFocus
                  icon="link"
                  className="flex-1"
                  placeholder="archidekt.com/decks/… oder moxfield.com/decks/…"
                  value={url}
                  error={urlError ?? undefined}
                  onChange={(e) => {
                    setUrl(e.target.value)
                    setUrlError(null)
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault()
                      void fetchUrl()
                    }
                  }}
                />
                <Button className="flex-none self-start" disabled={!url.trim() || urlBusy} onClick={() => void fetchUrl()} testId="import-load">
                  {urlBusy ? 'Lade …' : 'Laden'}
                </Button>
              </div>
              <span className="text-[12.5px] leading-[1.45] text-fg-3">Öffentliche Decks von Archidekt und Moxfield. Der Commander wird automatisch erkannt.</span>
            </div>
          ) : (
            <>
              <TextField label="Deck-Name" placeholder={preview?.name || 'Deckname (optional)'} value={name} onChange={(e) => setName(e.target.value)} />
              <div className="flex flex-wrap items-end gap-3">
                <TextField
                  label="Ordner"
                  className="min-w-[160px] flex-1"
                  placeholder="Ohne Ordner"
                  maxLength={40}
                  list="import-folders"
                  value={folder}
                  onChange={(e) => setFolder(e.target.value)}
                  data-testid="import-folder"
                />
                <datalist id="import-folders">
                  {folders.map((f) => (
                    <option key={f} value={f} />
                  ))}
                </datalist>
                <div className="flex flex-col gap-[7px]">
                  <span className="label">Bracket</span>
                  <Segmented<BracketPick>
                    variant="boxed"
                    ariaLabel="Bracket"
                    value={String(bracket) as BracketPick}
                    onChange={(id) => setBracket(Number(id))}
                    itemStyle={{ padding: '6px 9px' }}
                    items={[
                      { id: '0', label: 'Auto', title: preview?.bracketAuto ? `Vorschlag: ${preview.bracketAuto} · ${BRACKET_NAME[preview.bracketAuto]}` : 'Vorschlag aus der Liste', testId: 'import-bracket-auto' },
                      ...[1, 2, 3, 4, 5].map((b) => ({ id: String(b) as BracketPick, label: String(b), title: BRACKET_NAME[b], testId: `import-bracket-${b}` })),
                    ]}
                  />
                </div>
              </div>
              <div className="flex min-h-0 flex-1 flex-col gap-[7px]">
                <label htmlFor="import-text" className="label">
                  Textliste · eine Karte pro Zeile
                </label>
                <textarea
                  id="import-text"
                  autoFocus={!edit}
                  spellCheck={false}
                  wrap="off"
                  disabled={textLoading}
                  className="min-h-[160px] flex-1 resize-none overflow-auto whitespace-pre rounded-sm bg-bg-0 p-3 font-mono text-[13px] font-medium leading-[1.7] text-fg-2 shadow-[inset_0_0_0_1px_var(--color-line-3)] outline-none transition-shadow duration-1 scrollbar-thin placeholder:text-fg-4 hover:shadow-[inset_0_0_0_1px_var(--color-line-4)] focus:shadow-[inset_0_0_0_1px_var(--color-ember)] disabled:opacity-50"
                  placeholder={textLoading ? 'Lade Liste …' : "Commander\n1 Atraxa, Praetors' Voice\n\nDeck\n1 Sol Ring\n1 Arcane Signet\n…\n\nFormate: Moxfield, Archidekt, MTGA, MTGO, Forge/XMage (.dck)"}
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                />
              </div>
            </>
          )}
        </div>

        {/* rechts: Vorschau */}
        <div className="flex min-h-0 min-w-0 flex-col gap-3.5 overflow-auto px-5 py-[18px] scrollbar-thin" data-testid="import-preview">
          <div className="flex items-center justify-between gap-3">
            <span className="label">Vorschau</span>
            {(previewBusy || textLoading) && (
              <span className="flex items-center gap-1.5 text-[12.5px] text-fg-4">
                <Icon name="thinking" size={14} className="animate-think" />
                Prüfe Liste …
              </span>
            )}
          </div>
          {previewError && <IssueRow>{previewError}</IssueRow>}
          {preview ? <PreviewBody preview={preview} commanders={commanders} onToggleCommander={toggleCommander} onSuggestion={applySuggestion} /> : !previewError && <PreviewEmpty tab={tab} />}
        </div>
      </div>
    </Overlay>
  )
}

function PreviewEmpty({ tab }: { tab: ImportTab }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-3 rounded-sm p-6 text-center text-fg-3 shadow-[inset_0_0_0_1px_var(--color-line-2)]">
      <Icon name="decks" size={34} className="text-fg-4" />
      <span className="font-display text-[18px] font-semibold uppercase leading-none tracking-[.06em] text-fg-2">{tab === 'link' ? 'Noch nichts geladen' : 'Noch keine Liste'}</span>
      <span className="max-w-[300px] text-[13px] leading-[1.5]">
        {tab === 'link'
          ? 'Link einfügen und laden. Hier erscheinen dann Commander, Kartenanzahl, Legalität und fehlende Karten.'
          : 'Liste einfügen. Hier erscheinen dann Commander, Kartenanzahl, Legalität und fehlende Karten.'}
      </span>
    </div>
  )
}

function IssueRow({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-none items-start gap-2.5 rounded-sm bg-danger-bg px-3 py-2.5 text-[13px] leading-[1.45] text-fg-1 shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--color-attack)_40%,transparent)]">
      <Icon name="error" size={16} className="mt-px text-attack" />
      <span className="min-w-0">{children}</span>
    </div>
  )
}

function PreviewBody({
  preview: p,
  commanders,
  onToggleCommander,
  onSuggestion,
}: {
  preview: DeckPreview
  commanders: string[]
  onToggleCommander: (c: string) => void
  onSuggestion: (i: DeckIssue) => void
}) {
  const art = p.commanderSet && p.commanderNum ? cardImageUrl({ set: p.commanderSet, num: p.commanderNum }, { size: 'art_crop' }) : null
  const cmdNames = p.commanders.map((c) => c.name).join(' & ')
  const unknownN = p.issues ? p.issues.filter((i) => i.kind === 'unknown').length : p.unknown.length
  const issues: { key: string; node: ReactNode }[] = p.issues
    ? p.issues.map((i) => ({
        key: `${i.line}:${i.name}`,
        node: (
          <>
            Unbekannte Karte in Zeile {i.line}: <span className="font-mono text-[12.5px]">{i.name}</span>.
            {i.suggestion && (
              <>
                {' '}
                Meintest du{' '}
                <button type="button" className="font-semibold text-fg-1 underline decoration-fg-4 underline-offset-2 hover:decoration-fg-1" title="Zeile ersetzen" onClick={() => onSuggestion(i)}>
                  {i.suggestion}
                </button>
                ?
              </>
            )}
          </>
        ),
      }))
    : p.unknown.map((u) => ({ key: `u:${u}`, node: <>Unbekannte Karte: <span className="font-mono text-[12.5px]">{u}</span>.</> }))
  const typed = p.cards.some((c) => c.type)
  const groups = typed
    ? GROUPS.map((g) => ({ ...g, cards: p.cards.filter((c) => (c.type ?? 'other') === g.type) })).filter((g) => g.cards.length)
    : p.cards.length
      ? [{ type: 'other' as DeckCardType, label: 'Karten', cards: p.cards }]
      : []
  const colors = p.colors !== undefined && p.commanders.length ? (p.colors ? p.colors.split('').join(' · ') : 'Farblos') : null

  return (
    <>
      <DeckArt src={art} className="h-[120px] flex-none rounded-sm">
        <div className="absolute inset-0" style={{ background: 'linear-gradient(0deg, rgba(18,17,16,.9), rgba(18,17,16,0) 60%)' }} />
        <div className="absolute bottom-3 left-3.5 right-3.5 flex flex-col gap-1">
          <span className="label" style={{ fontSize: 12, color: 'var(--color-fg-2)' }}>
            Commander
          </span>
          <span className="truncate font-display text-[22px] font-semibold leading-none text-fg-1">{cmdNames || 'Noch kein Commander erkannt'}</span>
        </div>
      </DeckArt>

      <div className="flex flex-wrap gap-2">
        <Chip tone={p.cardCount === 100 ? 'chosen' : 'attack'}>{`${p.cardCount} Karten`}</Chip>
        {p.commanders.length > 0 &&
          (p.valid ? (
            <Chip tone="chosen">Commander-legal</Chip>
          ) : (
            <Chip tone="attack" title={p.validation || undefined}>
              Nicht legal
            </Chip>
          ))}
        {colors && <Chip tone="outline">{colors}</Chip>}
        {p.bracketAuto && (
          <Chip tone="outline" testId="import-bracket-suggestion" title={reasonLines(p.bracketInfo).join('\n') || 'Keine Game Changer, Combos, Landzerstörung oder Extra-Züge'}>
            {`Bracket-Vorschlag ${p.bracketAuto} · ${BRACKET_NAME[p.bracketAuto]}`}
          </Chip>
        )}
        {unknownN > 0 && <Chip tone="target">{`${unknownN} unbekannt`}</Chip>}
      </div>

      {p.needsCommander && (
        <div className="flex flex-none flex-col gap-2.5 rounded-sm p-3 shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--color-target)_45%,transparent)]">
          <span className="label" style={{ color: 'var(--color-target)' }}>
            Commander wählen
          </span>
          {p.candidates.length > 0 ? (
            <div className="flex max-h-40 flex-wrap gap-1.5 overflow-auto scrollbar-thin">
              {p.candidates.map((c) => {
                const on = commanders.includes(c)
                return (
                  <button key={c} type="button" aria-pressed={on} className={on ? 'chip-chosen' : 'chip-outline hover:text-fg-1'} onClick={() => onToggleCommander(c)}>
                    {on && <Icon name="chosen" size={13} />}
                    {c}
                  </button>
                )
              })}
            </div>
          ) : (
            <span className="text-[12.5px] leading-[1.45] text-fg-3">Keine legendären Kreaturen gefunden. Füge den Commander mit einem „Commander“-Abschnitt hinzu.</span>
          )}
        </div>
      )}

      {issues.slice(0, MAX_ISSUES).map((i) => (
        <IssueRow key={i.key}>{i.node}</IssueRow>
      ))}
      {issues.length > MAX_ISSUES && <span className="text-[12.5px] text-fg-3">{`+${issues.length - MAX_ISSUES} weitere`}</span>}

      {p.bracketInfo && p.bracketInfo.length > 0 && (
        <div className="flex flex-col gap-1 text-[12.5px] leading-[1.45] text-fg-3" data-testid="import-bracket-reasons">
          {reasonLines(p.bracketInfo).map((l) => (
            <span key={l}>{l}</span>
          ))}
        </div>
      )}

      {!p.valid && p.validation && p.commanders.length > 0 && <p className="m-0 whitespace-pre-line text-[12.5px] leading-[1.45] text-fg-3">{p.validation}</p>}

      <div className="flex flex-col">
        {p.commanders.length > 0 && <Group label="Commander" n={sum(p.commanders)} text={cardList(p.commanders)} />}
        {groups.map((g) => (
          <Group key={g.label} label={g.label} n={sum(g.cards)} text={cardList(g.cards)} />
        ))}
      </div>
    </>
  )
}

function Group({ label, n, text }: { label: string; n: number; text: string }) {
  return (
    <div className="flex flex-col border-b border-line-1 py-2">
      <div className="label flex justify-between pb-1.5">
        <span>{label}</span>
        <span>{n}</span>
      </div>
      <span className="text-[13px] leading-[1.55] text-fg-2">{text}</span>
    </div>
  )
}
