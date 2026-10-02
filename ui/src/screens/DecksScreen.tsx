import { useEffect, useState } from 'react'
import { api, ApiError, cardImageUrl } from '../api/client'
import type { StoredDeck } from '../api/types'
import { Modal } from '../components/Modal'
import { ColorPips } from '../lib/mana'
import { useNav } from '../store/nav'

interface Preview {
  name: string
  commanders: { name: string; set: string; number: string; count: number }[]
  cardCount: number
  unknown: string[]
  unfinished: string[]
  needsCommander: boolean
  candidates: string[]
  cards: { name: string; set: string; num: string; count: number }[]
  valid: boolean
  validation?: string
  colors?: string
  commanderSet?: string
  commanderNum?: string
  text?: string
  source?: string
  sourceUrl?: string
}

export function masteryLevel(xp: number): { level: number; into: number; next: number } {
  const T = [0, 200, 500, 900, 1500, 2300, 3300, 4600, 6200, 8200]
  let level = 1
  for (let i = 0; i < T.length; i++) if (xp >= T[i]) level = i + 1
  const cur = T[level - 1]
  const nxt = T[level] ?? T[T.length - 1]
  return { level, into: xp - cur, next: Math.max(1, nxt - cur) }
}

export function DecksScreen() {
  const [decks, setDecks] = useState<StoredDeck[]>([])
  const [editor, setEditor] = useState<null | { id?: number; text: string; name: string; sourceUrl?: string }>(null)
  const [confirmDel, setConfirmDel] = useState<StoredDeck | null>(null)
  const go = useNav((s) => s.go)
  const setLastSetup = useNav((s) => s.setLastSetup)
  const lastSetup = useNav((s) => s.lastSetup)

  const load = () => api.get<StoredDeck[]>('/api/decks').then(setDecks).catch(() => setDecks([]))
  useEffect(() => {
    load()
  }, [])

  const play = (d: StoredDeck) => {
    setLastSetup({ deck: { type: 'user', id: d.id }, bots: lastSetup?.bots ?? [{ type: 'random' }, { type: 'random' }, { type: 'random' }], tempo: lastSetup?.tempo ?? 'NORMAL' })
    go('play')
  }

  const edit = async (d: StoredDeck) => {
    const r = await api.get<{ text: string }>(`/api/decks/${d.id}/text`)
    setEditor({ id: d.id, text: r.text, name: d.name, sourceUrl: d.sourceUrl })
  }

  return (
    <div className="h-full overflow-y-auto p-8 scrollbar-thin">
      <div className="flex items-end justify-between">
        <div>
          <h1 className="font-display text-3xl font-bold tracking-wide text-gold-300">Deine Decks</h1>
          <p className="mt-1 text-ink-300">Liste einfügen oder Archidekt-/Moxfield-Link importieren.</p>
        </div>
        <button className="btn-primary !px-6" onClick={() => setEditor({ text: '', name: '' })}>
          + Deck importieren
        </button>
      </div>

      <div className="mt-8 grid grid-cols-[repeat(auto-fill,minmax(340px,1fr))] gap-4">
        {decks.map((d) => {
          const art = d.commanderSet && d.commanderNum ? cardImageUrl({ set: d.commanderSet, num: d.commanderNum }, { size: 'art_crop' }) : null
          const m = masteryLevel(d.masteryXp)
          return (
            <div key={d.id} className="group glass relative overflow-hidden rounded-2xl">
              <div className="relative h-36 overflow-hidden">
                {art && <img src={art} alt="" className="h-full w-full object-cover opacity-70 transition duration-500 group-hover:scale-105 group-hover:opacity-90" />}
                <div className="absolute inset-0 bg-linear-to-t from-ink-900 via-ink-900/40 to-transparent" />
                <div className="absolute bottom-2 left-4 right-4">
                  <div className="flex items-center gap-2">
                    <div className="truncate font-display text-lg font-bold text-ink-100">{d.name}</div>
                    {!d.valid && <span className="rounded bg-blood-500/30 px-1.5 text-[10px] font-semibold text-blood-400" title={d.validation}>nicht legal</span>}
                  </div>
                  <div className="flex items-center gap-2 text-xs text-ink-300">
                    <ColorPips colors={d.colors} size="sm" />
                    <span className="truncate">{d.commanders.join(' & ')}</span>
                  </div>
                </div>
                <div className="absolute right-3 top-3 rounded-full bg-ink-950/80 px-2 py-0.5 text-[11px] font-semibold text-arcane-400 ring-1 ring-arcane-400/40" title={`Deck-Meisterschaft: ${d.masteryXp} XP`}>
                  ★ Stufe {m.level}
                </div>
              </div>
              <div className="px-4 pb-4 pt-2">
                <div className="h-1.5 overflow-hidden rounded-full bg-ink-800">
                  <div className="h-full bg-linear-to-r from-arcane-500 to-arcane-400" style={{ width: `${m.level >= 10 ? 100 : (m.into / m.next) * 100}%` }} />
                </div>
                <div className="mt-1 flex justify-between text-[11px] text-ink-400">
                  <span>{d.cardCount} Karten</span>
                  <span>{d.source === 'text' ? 'Textliste' : d.source}</span>
                </div>
                <div className="mt-3 flex gap-2">
                  <button className="btn-primary flex-1 !py-1.5" onClick={() => play(d)}>
                    Spielen
                  </button>
                  <button className="btn-ghost !py-1.5" onClick={() => edit(d)}>
                    Bearbeiten
                  </button>
                  <button className="btn-ghost !px-2.5 !py-1.5" title="Löschen" onClick={() => setConfirmDel(d)}>
                    🗑
                  </button>
                </div>
              </div>
            </div>
          )
        })}
        {decks.length === 0 && (
          <button className="glass flex h-[230px] flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-ink-500/40 text-ink-300 hover:border-gold-400/50 hover:text-gold-300" onClick={() => setEditor({ text: '', name: '' })}>
            <span className="text-4xl">＋</span>
            Erstes Deck importieren
          </button>
        )}
      </div>

      {editor && (
        <ImportDialog
          initial={editor}
          onClose={() => setEditor(null)}
          onSaved={() => {
            setEditor(null)
            load()
          }}
        />
      )}
      {confirmDel && (
        <Modal
          title="Deck löschen?"
          onClose={() => setConfirmDel(null)}
          footer={
            <>
              <button className="btn-ghost" onClick={() => setConfirmDel(null)}>
                Abbrechen
              </button>
              <button
                className="btn-danger"
                onClick={async () => {
                  await api.del(`/api/decks/${confirmDel.id}`)
                  setConfirmDel(null)
                  load()
                }}
              >
                Löschen
              </button>
            </>
          }
        >
          <p className="text-ink-200">
            „{confirmDel.name}“ wird entfernt. Deine Statistiken zu diesem Deck bleiben erhalten.
          </p>
        </Modal>
      )}
    </div>
  )
}

function ImportDialog({ initial, onClose, onSaved }: { initial: { id?: number; text: string; name: string; sourceUrl?: string }; onClose: () => void; onSaved: () => void }) {
  const [tab, setTab] = useState<'text' | 'url'>(initial.id || initial.text ? 'text' : 'url')
  const [text, setText] = useState(initial.text)
  const [name, setName] = useState(initial.name)
  const [url, setUrl] = useState(initial.sourceUrl ?? '')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [commanders, setCommanders] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [source, setSource] = useState<{ source?: string; sourceUrl?: string }>({ sourceUrl: initial.sourceUrl })

  const check = async (t = text, cmds = commanders) => {
    setBusy(true)
    setError(null)
    try {
      const p = await api.post<Preview>('/api/decks/parse', { text: t, name: name || undefined, commanders: cmds.length ? cmds : undefined })
      setPreview(p)
      if (!name) setName(p.name)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const fetchUrl = async () => {
    setBusy(true)
    setError(null)
    try {
      let p: Preview
      try {
        p = await api.post<Preview>('/api/decks/url', { url })
      } catch (e) {
        // Moxfield blockt Server-Abrufe -> ueber Electron (Chromium) laden
        const blocked = e instanceof ApiError && /blockiert/.test(e.message)
        if (!blocked || !window.magelite?.fetchText) throw e
        const apiUrl = url.includes('moxfield') ? `https://api2.moxfield.com/v3/decks/all/${url.split('/decks/')[1]?.split(/[/?#]/)[0]}` : url
        const json = await window.magelite.fetchText(apiUrl)
        p = await api.post<Preview>('/api/decks/url', { url, json })
      }
      setPreview(p)
      setText(p.text ?? '')
      setName(p.name)
      setSource({ source: p.source, sourceUrl: p.sourceUrl })
      setCommanders([])
      setTab('text')
    } catch (e) {
      const msg = (e as Error).message
      setError(url.includes('moxfield') ? `${msg} – Moxfield blockiert evtl. den Abruf. Alternative: In Moxfield „Export → Text“ kopieren und hier einfügen.` : msg)
    } finally {
      setBusy(false)
    }
  }

  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/decks', { id: initial.id, name, text, commanders: commanders.length ? commanders : undefined, source: source.source ?? 'text', sourceUrl: source.sourceUrl })
      onSaved()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const art = preview?.commanderSet && preview.commanderNum ? cardImageUrl({ set: preview.commanderSet, num: preview.commanderNum }, { size: 'art_crop' }) : null
  const canSave = preview && preview.commanders.length > 0

  return (
    <Modal
      title={initial.id ? 'Deck bearbeiten' : 'Deck importieren'}
      onClose={onClose}
      wide
      footer={
        <>
          <button className="btn-ghost" onClick={onClose}>
            Abbrechen
          </button>
          <button className="btn-ghost" disabled={busy || !text.trim()} onClick={() => check()}>
            Prüfen
          </button>
          <button className="btn-primary" disabled={busy || !canSave} onClick={save}>
            {busy ? '…' : 'Speichern'}
          </button>
        </>
      }
    >
      <div className="grid grid-cols-[1.1fr_1fr] gap-6">
        <div className="flex flex-col gap-3">
          <div className="flex gap-1 self-start rounded-lg bg-ink-950/60 p-1">
            <button className={`rounded-md px-3 py-1 text-sm ${tab === 'url' ? 'bg-gold-400 text-ink-950' : 'text-ink-300'}`} onClick={() => setTab('url')}>
              Link
            </button>
            <button className={`rounded-md px-3 py-1 text-sm ${tab === 'text' ? 'bg-gold-400 text-ink-950' : 'text-ink-300'}`} onClick={() => setTab('text')}>
              Textliste
            </button>
          </div>
          {tab === 'url' ? (
            <div className="flex flex-col gap-2">
              <input
                autoFocus
                className="rounded-lg bg-ink-950/70 px-3 py-2 text-sm ring-1 ring-white/15 outline-none focus:ring-gold-400/60"
                placeholder="https://archidekt.com/decks/…  oder  https://moxfield.com/decks/…"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && url && fetchUrl()}
              />
              <button className="btn-arcane self-start" disabled={!url || busy} onClick={fetchUrl}>
                {busy ? 'Lade …' : 'Deck laden'}
              </button>
              <p className="text-xs text-ink-400">Das Deck muss öffentlich sein. Danach kannst du die Liste prüfen und anpassen.</p>
            </div>
          ) : (
            <>
              <input className="rounded-lg bg-ink-950/70 px-3 py-2 text-sm ring-1 ring-white/15 outline-none focus:ring-gold-400/60" placeholder="Deckname (optional)" value={name} onChange={(e) => setName(e.target.value)} />
              <textarea
                autoFocus
                className="h-[46vh] resize-none rounded-lg bg-ink-950/70 p-3 font-mono text-xs leading-relaxed ring-1 ring-white/15 outline-none focus:ring-gold-400/60 scrollbar-thin"
                placeholder={'Commander\n1 Atraxa, Praetors\' Voice\n\nDeck\n1 Sol Ring\n1 Arcane Signet\n…\n\nFormate: Moxfield, Archidekt, MTGA, MTGO, XMage (.dck)'}
                value={text}
                onChange={(e) => {
                  setText(e.target.value)
                  setPreview(null)
                }}
                onBlur={() => text.trim() && !preview && check()}
              />
            </>
          )}
          {error && <div className="rounded-lg bg-blood-500/15 px-3 py-2 text-sm text-blood-400 ring-1 ring-blood-400/30">{error}</div>}
        </div>

        <div className="flex min-h-0 flex-col gap-3">
          {!preview ? (
            <div className="flex h-full items-center justify-center rounded-xl bg-ink-950/40 p-6 text-center text-sm text-ink-400 ring-1 ring-white/5">Füge eine Liste ein oder lade einen Link – die Vorschau erscheint hier.</div>
          ) : (
            <>
              <div className="relative overflow-hidden rounded-xl ring-1 ring-white/10">
                {art && <img src={art} alt="" className="h-32 w-full object-cover opacity-70" />}
                {!art && <div className="h-20 bg-ink-800" />}
                <div className="absolute inset-0 bg-linear-to-t from-ink-950 to-transparent" />
                <div className="absolute bottom-2 left-3">
                  <div className="font-display text-lg font-bold">{name || preview.name}</div>
                  <div className="flex items-center gap-2 text-xs text-ink-300">
                    <ColorPips colors={preview.colors} size="sm" /> {preview.commanders.map((c) => c.name).join(' & ') || 'kein Commander'}
                  </div>
                </div>
              </div>
              <div className="flex flex-wrap gap-2 text-xs">
                <span className={`rounded-md px-2 py-1 ring-1 ${preview.cardCount === 100 ? 'bg-arcane-500/15 text-arcane-400 ring-arcane-400/30' : 'bg-gold-400/10 text-gold-300 ring-gold-400/30'}`}>{preview.cardCount} Karten</span>
                {preview.commanders.length > 0 && (preview.valid ? <span className="rounded-md bg-arcane-500/15 px-2 py-1 text-arcane-400 ring-1 ring-arcane-400/30">Commander-legal ✓</span> : <span className="rounded-md bg-blood-500/15 px-2 py-1 text-blood-400 ring-1 ring-blood-400/30">nicht legal</span>)}
                {preview.unknown.length + preview.unfinished.length > 0 && <span className="rounded-md bg-blood-500/15 px-2 py-1 text-blood-400 ring-1 ring-blood-400/30">{preview.unknown.length + preview.unfinished.length} fehlen</span>}
              </div>
              {preview.needsCommander && (
                <div className="rounded-xl bg-gold-400/10 p-3 ring-1 ring-gold-400/30">
                  <div className="mb-2 text-sm font-semibold text-gold-300">Wer ist dein Commander?</div>
                  <div className="flex max-h-40 flex-wrap gap-1.5 overflow-auto scrollbar-thin">
                    {preview.candidates.map((c) => {
                      const on = commanders.includes(c)
                      return (
                        <button
                          key={c}
                          className={`rounded-md px-2 py-1 text-xs ring-1 ${on ? 'bg-gold-400 text-ink-950 ring-gold-400' : 'bg-ink-900 text-ink-200 ring-white/15 hover:ring-gold-400/50'}`}
                          onClick={() => {
                            const next = on ? commanders.filter((x) => x !== c) : [...commanders, c].slice(-2)
                            setCommanders(next)
                            if (next.length) check(text, next)
                          }}
                        >
                          {c}
                        </button>
                      )
                    })}
                    {preview.candidates.length === 0 && <span className="text-xs text-ink-400">Keine legendären Kreaturen gefunden – füge den Commander mit „Commander“-Abschnitt hinzu.</span>}
                  </div>
                </div>
              )}
              {preview.unknown.length + preview.unfinished.length > 0 && (
                <div className="max-h-28 overflow-auto rounded-lg bg-blood-500/10 p-2 text-xs text-blood-400 ring-1 ring-blood-400/20 scrollbar-thin">
                  {preview.unfinished.length > 0 && (
                    <div className="mb-1">
                      <div className="mb-1 font-semibold">In XMage noch nicht spielbar (werden ignoriert):</div>
                      {preview.unfinished.map((u) => (
                        <div key={u}>{u}</div>
                      ))}
                    </div>
                  )}
                  {preview.unknown.length > 0 && (
                    <div>
                      <div className="mb-1 font-semibold">Unbekannt – evtl. neuer als XMage oder Tippfehler (werden ignoriert):</div>
                      {preview.unknown.map((u) => (
                        <div key={u}>{u}</div>
                      ))}
                    </div>
                  )}
                </div>
              )}
              {preview.validation && <div className="max-h-28 overflow-auto rounded-lg bg-ink-950/50 p-2 text-xs text-ink-300 ring-1 ring-white/10 scrollbar-thin">{preview.validation}</div>}
              <div className="min-h-0 flex-1 overflow-auto rounded-lg bg-ink-950/40 p-2 text-xs text-ink-300 ring-1 ring-white/5 scrollbar-thin">
                {preview.cards.map((c) => (
                  <div key={c.name} className="flex justify-between py-0.5">
                    <span>
                      {c.count}× {c.name}
                    </span>
                    <span className="text-ink-500">{c.set}</span>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>
    </Modal>
  )
}
