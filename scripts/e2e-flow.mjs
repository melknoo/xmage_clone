// End-to-End-Test ueber REST + WebSocket gegen eine laufende isolierte Engine im lokalen Modus (ohne Token).
// Ablauf: Import-Vorschau (Vorschlaege, Zeilen, Kartenarten) -> Deck speichern (Archidekt-Link oder Textliste) ->
// Spiel mit dem gespeicherten Deck starten -> Test-Spieler spielt Laender und passt -> Spielende abwarten
// (max. 7 min, dann Aufgeben) -> Belohnung, Profil und Deckliste pruefen.
//
// Aufruf:  MAGELITE_URL=http://127.0.0.1:7400 node scripts/e2e-flow.mjs [archidekt-url]
const base = process.env.MAGELITE_URL
if (!base) {
  console.error('MAGELITE_URL fehlt (z. B. http://127.0.0.1:7400) - kein Default, nie gegen 7317 testen')
  process.exit(2)
}
const deckUrl = process.argv[2]
let failed = 0
const ok = (cond, what) => {
  console.log(`${cond ? 'OK  ' : 'FAIL'} ${what}`)
  if (!cond) failed++
}

const j = async (method, path, body) => {
  const r = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
  const t = await r.text()
  if (!r.ok) throw new Error(`${method} ${path}: ${r.status} ${t}`)
  return t ? JSON.parse(t) : null
}

// ---- Import-Vorschau: Vorschlaege nur hier, Zeilennummern inkl. Leer-/Kopfzeilen, Kartenarten
{
  const text = 'Commander\n1 Krenko, Mob Boss\n\nDeck\n1 Sol Rnig\n1 Totally Fake Card Name\n1 Lightning Bolt\n30 Mountain\n'
  const pv = await j('POST', '/api/decks/parse', { text })
  const sol = pv.issues?.find((i) => i.name === 'Sol Rnig')
  const fake = pv.issues?.find((i) => i.name === 'Totally Fake Card Name')
  ok(sol?.suggestion === 'Sol Ring' && sol.line === 5 && sol.kind === 'unknown' && sol.count === 1, `Vorschau: ${JSON.stringify(sol)}`)
  ok(fake && fake.suggestion === undefined && fake.line === 6, `Fantasiename ohne Vorschlag: ${JSON.stringify(fake)}`)
  ok(JSON.stringify(pv.unknown) === JSON.stringify(['1 Sol Rnig', '1 Totally Fake Card Name']), `unknown unveraendert: ${JSON.stringify(pv.unknown)}`)
  const types = Object.fromEntries((pv.cards ?? []).map((c) => [c.name, c.type]))
  ok(types['Lightning Bolt'] === 'instant' && types['Mountain'] === 'land', `cards[].type: ${JSON.stringify(types)}`)
}

let deck
let savedId
if (deckUrl) {
  const prev = await j('POST', '/api/decks/url', { url: deckUrl })
  const saved = await j('POST', '/api/decks', { name: prev.name, text: prev.text, source: prev.source, sourceUrl: prev.sourceUrl })
  console.log('Deck gespeichert:', saved.id, saved.name, saved.cardCount, 'Karten, legal:', saved.valid)
  savedId = saved.id
} else {
  // gueltiges Commander-Deck aus Basislaendern: speichern (liefert get()) und damit spielen
  const saved = await j('POST', '/api/decks', { name: 'E2E Krenko', text: 'Commander\n1 Krenko, Mob Boss\nDeck\n99 Mountain\n' })
  ok(saved?.id && saved.name === 'E2E Krenko' && saved.cardCount === 100 && saved.masteryLevel >= 1, `Deck gespeichert: id=${saved?.id} ${saved?.cardCount} Karten, legal=${saved?.valid}`)
  savedId = saved.id
}
deck = { type: 'user', id: savedId }

const { gameId } = await j('POST', '/api/games', { deck, bots: [{ type: 'random' }, { type: 'random' }, { type: 'random' }], tempo: 'BLITZ' })
console.log('Spiel', gameId)
const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/ws/game/${gameId}`)
let over = null
let last = null
const counts = {}
let mulliganPrompt = null
const abilityTypes = new Set()
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data)
  counts[m.t] = (counts[m.t] ?? 0) + 1
  if (m.t === 'state') {
    last = m
    for (const so of m.stack ?? []) if (so.abilityType) abilityTypes.add(so.abilityType)
  }
  if (m.t === 'gameOver') over = m
  if (m.t !== 'prompt') return
  let ans
  if (m.kind === 'ASK' && m.mulligan && !mulliganPrompt) mulliganPrompt = m
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
console.log('Stapel-abilityType gesehen:', [...abilityTypes].join(', ') || '-')
ok(!mulliganPrompt || mulliganPrompt.mulligans === undefined, `Mulligan-Frage: mulligans=${mulliganPrompt?.mulligans} freeMulligan=${mulliganPrompt?.freeMulligan}`)
const rw = over.reward
ok(rw && typeof rw.xpIntoLevelBefore === 'number' && rw.xpForNextBefore > 0 && 'nextTitle' in rw, `Reward: xpIntoLevelBefore=${rw?.xpIntoLevelBefore} xpForNextBefore=${rw?.xpForNextBefore} nextTitle=${JSON.stringify(rw?.nextTitle)}`)
ok(rw?.deckId === savedId && rw.masteryLevel >= 1, `Reward-Meisterschaft fuer Deck ${rw?.deckId}: Stufe ${rw?.masteryLevel}`)
const decks = await j('GET', '/api/decks')
const mine = decks.find((d) => d.id === savedId)
ok(mine && mine.games >= 1 && typeof mine.wins === 'number' && mine.masteryLevel >= 1 && mine.masteryNext > 0, `Deckliste: games=${mine?.games} wins=${mine?.wins} Stufe ${mine?.masteryLevel}/${mine?.masteryNext}`)
const ov = await j('GET', '/api/stats/overview')
ok(ov.totals?.totalDurationMs > 0 && ov.totals?.firstEndedAt > 0, `Statistik: totalDurationMs=${ov.totals?.totalDurationMs} firstEndedAt=${ov.totals?.firstEndedAt}`)
ok((ov.opponents ?? []).every((o) => typeof o.aheadOfYou === 'number'), `Gegner mit aheadOfYou (${ov.opponents?.length})`)
const cs = await j('GET', `/api/stats/decks/${savedId}/cards`)
ok((cs.cards ?? []).every((c) => typeof c.gamesInHand === 'number'), `Kartenstatistik mit gamesInHand (${cs.cards?.length} Karten)`)
const sd = await j('GET', '/api/stats/decks')
ok(sd.find((d) => d.deckId === savedId)?.masteryLevel >= 1, 'Deck-Statistik mit masteryLevel')
if (!deckUrl) await j('DELETE', `/api/decks/${savedId}`)
console.log(failed === 0 ? '\n=== alles gruen ===' : `\n=== ${failed} Pruefungen fehlgeschlagen ===`)
process.exit(failed === 0 ? 0 : 1)
