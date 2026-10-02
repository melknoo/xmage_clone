import { useEffect, useMemo, useState } from 'react'
import { api, cardImageUrl } from '../api/client'
import type { DeckSpec, SampleDeck, StoredDeck, Tempo } from '../api/types'
import { ColorPips } from '../lib/mana'
import { useGame } from '../store/game'
import { useNav } from '../store/nav'

const TEMPOS: { key: Tempo; label: string; desc: string }[] = [
  { key: 'BLITZ', label: 'Blitz', desc: 'Bots entscheiden in ~2 s, keine Pausen' },
  { key: 'NORMAL', label: 'Normal', desc: 'Schnell, mit kurzen Pausen zum Mitlesen' },
  { key: 'BEDACHT', label: 'Bedacht', desc: 'Bots denken länger und reagieren in deinem Zug' },
  { key: 'MAX', label: 'Max', desc: 'Stärkste Bots – deutlich langsamer' },
]

export function PlaySetupScreen() {
  const [decks, setDecks] = useState<StoredDeck[]>([])
  const [samples, setSamples] = useState<SampleDeck[]>([])
  const lastSetup = useNav((s) => s.lastSetup)
  const setLastSetup = useNav((s) => s.setLastSetup)
  const go = useNav((s) => s.go)
  const connect = useGame((s) => s.connect)
  const [myDeck, setMyDeck] = useState<DeckSpec | null>(lastSetup?.deck ?? null)
  const [bots, setBots] = useState<DeckSpec[]>(lastSetup?.bots ?? [{ type: 'random' }, { type: 'random' }, { type: 'random' }])
  const [tempo, setTempo] = useState<Tempo>(lastSetup?.tempo ?? 'NORMAL')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [picker, setPicker] = useState<null | { target: 'me' | number }>(null)

  useEffect(() => {
    api.get<StoredDeck[]>('/api/decks').then(setDecks).catch(() => setDecks([]))
    api.get<SampleDeck[]>('/api/samples').then(setSamples).catch(() => setSamples([]))
  }, [])

  const describe = (spec: DeckSpec | null) => {
    if (!spec) return null
    if (spec.type === 'random') return { name: 'Zufälliges Deck', sub: 'aus den mitgelieferten Commander-Decks', colors: '', art: null as string | null }
    if (spec.type === 'user') {
      const d = decks.find((x) => x.id === spec.id)
      return d ? { name: d.name, sub: d.commanders.join(' & '), colors: d.colors, art: d.commanderSet && d.commanderNum ? cardImageUrl({ set: d.commanderSet, num: d.commanderNum }, { size: 'art_crop' }) : null } : null
    }
    const s = samples.find((x) => x.id === spec.id)
    return s ? { name: s.name, sub: s.commanders.join(' & '), colors: s.colors, art: s.commanderSet && s.commanderNum ? cardImageUrl({ set: s.commanderSet, num: s.commanderNum }, { size: 'art_crop' }) : null } : null
  }

  const start = async () => {
    if (!myDeck) return
    setBusy(true)
    setError(null)
    const setup = { deck: myDeck, bots, tempo }
    try {
      const res = await api.post<{ gameId: string }>('/api/games', setup)
      setLastSetup(setup)
      connect(res.gameId)
      go('game')
    } catch (e) {
      setError(String((e as Error).message))
    } finally {
      setBusy(false)
    }
  }

  const me = describe(myDeck)
  return (
    <div className="h-full overflow-y-auto p-8 scrollbar-thin">
      <h1 className="font-display text-3xl font-bold tracking-wide text-gold-300">Neues Spiel</h1>
      <p className="mt-1 text-ink-300">Dein Commander gegen drei Bots – Free-for-All, 40 Leben.</p>

      <div className="mt-8 grid max-w-5xl gap-6 lg:grid-cols-[1.1fr_1fr]">
        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-ink-300">Dein Deck</h2>
          <SeatCard info={me} highlight onClick={() => setPicker({ target: 'me' })} empty="Deck wählen" />
          <h2 className="mb-3 mt-8 text-sm font-semibold uppercase tracking-wider text-ink-300">Bot-Tempo</h2>
          <div className="grid grid-cols-2 gap-2">
            {TEMPOS.map((t) => (
              <button key={t.key} className={`rounded-xl p-3 text-left ring-1 transition ${tempo === t.key ? 'bg-arcane-500/15 ring-arcane-400/60' : 'bg-ink-900/60 ring-white/10 hover:ring-white/25'}`} onClick={() => setTempo(t.key)}>
                <div className={`font-semibold ${tempo === t.key ? 'text-arcane-400' : 'text-ink-100'}`}>{t.label}</div>
                <div className="text-xs text-ink-400">{t.desc}</div>
              </button>
            ))}
          </div>
        </section>
        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-ink-300">Gegner</h2>
          <div className="flex flex-col gap-2">
            {bots.map((b, i) => (
              <SeatCard key={i} info={describe(b)} small onClick={() => setPicker({ target: i })} empty="Zufällig" />
            ))}
          </div>
          <button className="btn-ghost mt-3 !text-xs" onClick={() => setBots([{ type: 'random' }, { type: 'random' }, { type: 'random' }])}>
            Alle zufällig
          </button>
        </section>
      </div>

      <div className="mt-10 flex items-center gap-4">
        <button className="btn-primary !px-10 !py-3 !text-base" disabled={!myDeck || busy} onClick={start}>
          {busy ? 'Mische Decks …' : 'Spiel starten'}
        </button>
        {!myDeck && <span className="text-sm text-ink-400">Wähle zuerst dein Deck.</span>}
        {error && <span className="text-sm text-blood-400">{error}</span>}
      </div>

      {picker && (
        <DeckPicker
          decks={decks}
          samples={samples}
          allowRandom={picker.target !== 'me'}
          onClose={() => setPicker(null)}
          onPick={(spec) => {
            if (picker.target === 'me') setMyDeck(spec)
            else setBots(bots.map((b, j) => (j === picker.target ? spec : b)))
            setPicker(null)
          }}
        />
      )}
    </div>
  )
}

function SeatCard({ info, onClick, empty, highlight, small }: { info: { name: string; sub: string; colors: string; art: string | null } | null; onClick: () => void; empty: string; highlight?: boolean; small?: boolean }) {
  return (
    <button className={`group relative flex w-full items-center gap-4 overflow-hidden rounded-2xl text-left ring-1 transition ${highlight ? 'ring-gold-400/40 hover:ring-gold-400/80' : 'ring-white/10 hover:ring-white/30'} ${small ? 'h-[72px]' : 'h-[120px]'} bg-ink-900/70`} onClick={onClick}>
      {info?.art && <div className="absolute inset-0 bg-cover bg-center opacity-35 transition group-hover:opacity-50" style={{ backgroundImage: `url(${info.art})` }} />}
      <div className="absolute inset-0 bg-linear-to-r from-ink-950/90 via-ink-950/60 to-transparent" />
      <div className="relative px-5">
        {info ? (
          <>
            <div className={`font-display font-semibold ${small ? 'text-base' : 'text-xl'} text-ink-100`}>{info.name}</div>
            <div className="mt-0.5 flex items-center gap-2 text-xs text-ink-300">
              {info.colors && <ColorPips colors={info.colors} size="sm" />}
              <span className="truncate">{info.sub}</span>
            </div>
          </>
        ) : (
          <div className="text-ink-300">{empty}</div>
        )}
      </div>
      <div className="relative ml-auto pr-5 text-xs text-ink-400 group-hover:text-ink-200">ändern ›</div>
    </button>
  )
}

function DeckPicker({ decks, samples, allowRandom, onPick, onClose }: { decks: StoredDeck[]; samples: SampleDeck[]; allowRandom: boolean; onPick: (s: DeckSpec) => void; onClose: () => void }) {
  const [tab, setTab] = useState<'mine' | 'samples'>(decks.length ? 'mine' : 'samples')
  const [q, setQ] = useState('')
  const filtered = useMemo(() => {
    const ql = q.toLowerCase()
    return samples.filter((s) => !ql || s.name.toLowerCase().includes(ql) || s.commanders.some((c) => c.toLowerCase().includes(ql)))
  }, [samples, q])
  const mine = decks.filter((d) => !q || d.name.toLowerCase().includes(q.toLowerCase()) || d.commanders.some((c) => c.toLowerCase().includes(q.toLowerCase())))
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink-950/70 p-8 backdrop-blur-sm" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="glass flex h-[80vh] w-full max-w-4xl flex-col rounded-2xl">
        <div className="flex items-center gap-3 border-b border-white/10 p-4">
          <div className="flex gap-1 rounded-lg bg-ink-950/60 p-1">
            <button className={`rounded-md px-3 py-1 text-sm ${tab === 'mine' ? 'bg-gold-400 text-ink-950' : 'text-ink-300'}`} onClick={() => setTab('mine')}>
              Meine Decks ({decks.length})
            </button>
            <button className={`rounded-md px-3 py-1 text-sm ${tab === 'samples' ? 'bg-gold-400 text-ink-950' : 'text-ink-300'}`} onClick={() => setTab('samples')}>
              Vorgefertigt ({samples.length})
            </button>
          </div>
          <input autoFocus className="flex-1 rounded-lg bg-ink-950/70 px-3 py-1.5 text-sm ring-1 ring-white/15 outline-none focus:ring-gold-400/60" placeholder="Deck oder Commander suchen…" value={q} onChange={(e) => setQ(e.target.value)} />
          {allowRandom && (
            <button className="btn-ghost !text-xs" onClick={() => onPick({ type: 'random' })}>
              🎲 Zufällig
            </button>
          )}
          <button className="rounded-md px-2 py-1 text-ink-300 hover:bg-white/10" onClick={onClose}>
            ✕
          </button>
        </div>
        <div className="grid min-h-0 flex-1 auto-rows-[84px] grid-cols-2 gap-2 overflow-y-auto p-4 scrollbar-thin">
          {tab === 'mine' &&
            mine.map((d) => (
              <DeckTile key={d.id} name={d.name} sub={d.commanders.join(' & ')} colors={d.colors} set={d.commanderSet} num={d.commanderNum} warn={!d.valid} onClick={() => onPick({ type: 'user', id: d.id })} />
            ))}
          {tab === 'mine' && mine.length === 0 && <div className="col-span-2 p-6 text-center text-ink-400">Noch keine eigenen Decks – importiere eins unter „Decks“.</div>}
          {tab === 'samples' && filtered.map((s) => <DeckTile key={s.id} name={s.name} sub={s.commanders.join(' & ')} colors={s.colors} set={s.commanderSet} num={s.commanderNum} group={s.group} onClick={() => onPick({ type: 'sample', id: s.id })} />)}
        </div>
      </div>
    </div>
  )
}

function DeckTile({ name, sub, colors, set, num, group, warn, onClick }: { name: string; sub: string; colors: string; set?: string; num?: string; group?: string; warn?: boolean; onClick: () => void }) {
  const art = set && num ? cardImageUrl({ set, num }, { size: 'art_crop' }) : null
  return (
    <button className="group relative overflow-hidden rounded-xl bg-ink-900/70 text-left ring-1 ring-white/10 transition hover:ring-gold-400/60" onClick={onClick}>
      {art && <div className="absolute inset-0 bg-cover bg-center opacity-30 transition group-hover:opacity-50" style={{ backgroundImage: `url(${art})` }} />}
      <div className="absolute inset-0 bg-linear-to-r from-ink-950/95 via-ink-950/70 to-transparent" />
      <div className="relative flex h-full flex-col justify-center px-4">
        <div className="flex items-center gap-2">
          <span className="truncate font-semibold text-ink-100">{name}</span>
          {warn && <span className="rounded bg-blood-500/30 px-1 text-[10px] text-blood-400">ungültig</span>}
        </div>
        <div className="flex items-center gap-2 text-xs text-ink-300">
          <ColorPips colors={colors} size="sm" />
          <span className="truncate">{sub}</span>
        </div>
        {group && <div className="truncate text-[10px] text-ink-500">{group}</div>}
      </div>
    </button>
  )
}
