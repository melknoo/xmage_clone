// End-to-End-Test ueber REST + WebSocket gegen eine laufende Dev-Engine (gradlew run, Port 7317, ohne Token).
// Ablauf: Deck importieren (Archidekt-Link oder Sample) -> Spiel starten -> Test-Spieler spielt Laender und passt
// -> Spielende abwarten (max. 7 min, dann Aufgeben) -> Belohnung, Profil und Statistik ausgeben.
//
// Aufruf:  node scripts/e2e-flow.mjs [archidekt-url]
const base = process.env.MAGELITE_URL ?? 'http://127.0.0.1:7317'
const deckUrl = process.argv[2]

const j = async (method, path, body) => {
  const r = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
  const t = await r.text()
  if (!r.ok) throw new Error(`${method} ${path}: ${r.status} ${t}`)
  return t ? JSON.parse(t) : null
}

let deck = { type: 'random' }
if (deckUrl) {
  const prev = await j('POST', '/api/decks/url', { url: deckUrl })
  const saved = await j('POST', '/api/decks', { name: prev.name, text: prev.text, source: prev.source, sourceUrl: prev.sourceUrl })
  console.log('Deck gespeichert:', saved.id, saved.name, saved.cardCount, 'Karten, legal:', saved.valid)
  deck = { type: 'user', id: saved.id }
}

const { gameId } = await j('POST', '/api/games', { deck, bots: [{ type: 'random' }, { type: 'random' }, { type: 'random' }], tempo: 'BLITZ' })
console.log('Spiel', gameId)
const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/ws/game/${gameId}`)
let over = null
let last = null
const counts = {}
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data)
  counts[m.t] = (counts[m.t] ?? 0) + 1
  if (m.t === 'state') last = m
  if (m.t === 'gameOver') over = m
  if (m.t !== 'prompt') return
  let ans
  switch (m.kind) {
    case 'ASK':
      ans = { bool: !m.mulligan }
      break
    case 'SELECT': {
      if (m.mode !== 'priority') {
        ans = { bool: true }
        break
      }
      const land = last?.hand.find((c) => last.actions?.includes(c.id) && c.types?.includes('LAND'))
      ans = land ? { uuid: land.id } : { bool: false }
      break
    }
    case 'PICK_TARGET':
      ans = m.targets?.length && !m.chosen?.length ? { uuid: m.targets[0] } : { bool: false }
      break
    case 'CHOOSE_CHOICE':
      ans = { str: m.choice.keyed ? m.choice.items[0].key : m.choice.items[0].value }
      break
    case 'AMOUNT':
      ans = { int: m.min ?? 0 }
      break
    case 'MULTI_AMOUNT':
      ans = { str: m.items.map((i) => i.value).join(' ') }
      break
    default:
      ans = m.choices?.length ? { uuid: m.choices[0].id } : { bool: false }
  }
  ws.send(JSON.stringify({ t: 'respond', id: m.id, ...ans }))
}

const t0 = Date.now()
while (!over && Date.now() - t0 < 420000) await new Promise((r) => setTimeout(r, 1000))
if (!over) {
  console.log('Zeitlimit in Zug', last?.turn, '-> Aufgeben')
  ws.send(JSON.stringify({ t: 'leave' }))
  while (!over) await new Promise((r) => setTimeout(r, 500))
}
console.log('Ende:', over.result, '| Zuege', over.turns, '|', over.placements.map((p) => `${p.place}. ${p.name}`).join(', '))
console.log('Nachrichten:', JSON.stringify(counts))
console.log('Belohnung:', JSON.stringify(over.reward))
console.log('Profil:', JSON.stringify(await j('GET', '/api/profile')))
process.exit(0)
