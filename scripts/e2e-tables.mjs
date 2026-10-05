// End-to-End-Test fuer Lobby und Tische (E4) gegen eine laufende Dev-Engine im Server-Modus:
//   cd engine; .\gradlew.bat runServer        (Owner-Code DEV-OWNER-CODE, Port 7317)
//   node scripts\e2e-tables.mjs
// Ablauf: Owner eroeffnet Tisch -> Bob tritt bei -> Decks waehlen -> Owner setzt Bot auf Platz 3, Platz 4 bleibt
// frei -> Start (3 Spieler) -> beide verbinden sich per WebSocket, spielen 60 s, geben auf -> Tisch zurueck in
// der Lobby -> Owner schliesst den Tisch. Dazu Negativfaelle (409).
const base = process.env.MAGELITE_URL ?? 'http://127.0.0.1:7317'
const ownerCode = process.env.MAGELITE_OWNER_CODE ?? 'DEV-OWNER-CODE'

let failed = 0
const ok = (cond, what) => {
  console.log(`${cond ? 'OK  ' : 'FAIL'} ${what}`)
  if (!cond) failed++
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
async function call(method, path, { body, cookie } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (cookie) headers.Cookie = cookie
  const r = await fetch(base + path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined })
  const t = await r.text()
  let json = null
  try {
    json = t ? JSON.parse(t) : null
  } catch {
    json = t
  }
  return { status: r.status, json, setCookie: r.headers.get('set-cookie') }
}
const cookieOf = (sc) => (sc ? sc.split(';')[0] : null)

// ---- Konten
let r = await call('POST', '/api/auth/login', { body: { code: ownerCode } })
const owner = cookieOf(r.setCookie)
r = await call('POST', '/api/admin/invites', { cookie: owner, body: { name: 'Bob' } })
const bobId = r.json?.id
r = await call('POST', '/api/auth/login', { body: { code: r.json?.code } })
const bob = cookieOf(r.setCookie)
r = await call('POST', '/api/admin/invites', { cookie: owner, body: { name: 'Carla' } })
const carlaId = r.json?.id
r = await call('POST', '/api/auth/login', { body: { code: r.json?.code } })
const carla = cookieOf(r.setCookie)
ok(owner && bob && carla, 'Owner, Bob, Carla angemeldet')
const samples = (await call('GET', '/api/samples', { cookie: owner })).json
const sample = (i) => ({ type: 'sample', id: samples[i].id })

// ---- Lobby leer, Tisch eroeffnen
r = await call('GET', '/api/tables', { cookie: owner })
ok(r.status === 200 && Array.isArray(r.json), `Lobby-Liste -> ${r.status} (${r.json?.length} Tische)`)
r = await call('GET', '/api/tables/mine', { cookie: owner })
ok(r.status === 404, `kein eigener Tisch -> ${r.status}`)
r = await call('POST', '/api/tables', { cookie: owner, body: { name: 'Freitagsrunde', tempo: 'BLITZ' } })
ok(r.status === 200 && r.json?.host === true && r.json?.mySeat === 0 && r.json?.seats?.[0]?.kind === 'HUMAN', `Tisch eroeffnet -> ${r.status} id=${r.json?.id}`)
const table = r.json
const tid = table.id
r = await call('POST', '/api/tables', { cookie: owner, body: { name: 'Zweiter' } })
ok(r.status === 409, `zweiter Tisch des Gastgebers -> ${r.status}`)

// ---- Bob tritt bei, Carla auch; Negativfaelle
r = await call('POST', `/api/tables/${tid}/join`, { cookie: bob })
ok(r.status === 200 && r.json?.mySeat === 1 && r.json?.humans === 2, `Bob tritt bei -> Platz ${r.json?.mySeat}`)
r = await call('POST', `/api/tables/${tid}/start`, { cookie: bob })
ok(r.status === 409, `Bob startet (kein Gastgeber) -> ${r.status}`)
r = await call('PUT', `/api/tables/${tid}/seats/2`, { cookie: bob, body: { kind: 'BOT' } })
ok(r.status === 409, `Bob setzt Bot (kein Gastgeber) -> ${r.status}`)
r = await call('POST', `/api/tables/${tid}/start`, { cookie: owner })
ok(r.status === 409 && /Deck/.test(r.json?.error ?? ''), `Start ohne Decks -> ${r.status} (${r.json?.error})`)

// ---- Decks, Bot, offener Platz
r = await call('PUT', `/api/tables/${tid}/seat`, { cookie: owner, body: { deck: sample(0) } })
ok(r.status === 200 && r.json?.seats?.[0]?.ready === true && r.json?.seats?.[0]?.deckName, `Owner-Deck gesetzt: ${r.json?.seats?.[0]?.deckName}`)
r = await call('PUT', `/api/tables/${tid}/seat`, { cookie: bob, body: { deck: sample(1) } })
ok(r.status === 200 && r.json?.seats?.[1]?.ready === true, `Bob-Deck gesetzt: ${r.json?.seats?.[1]?.deckName}`)
r = await call('GET', `/api/tables/${tid}`, { cookie: bob })
ok(r.json?.seats?.[0]?.deck === undefined && r.json?.seats?.[0]?.deckName, 'Bob sieht Owner-Deck nur als Name')
r = await call('PUT', `/api/tables/${tid}/seats/2`, { cookie: owner, body: { kind: 'BOT', deck: sample(2) } })
ok(r.status === 200 && r.json?.seats?.[2]?.kind === 'BOT' && r.json?.seats?.[2]?.deckName, `Bot auf Platz 3: ${r.json?.seats?.[2]?.deckName}`)
r = await call('PUT', `/api/tables/${tid}/seats/1`, { cookie: owner, body: { kind: 'OPEN' } })
ok(r.status === 409, `Gastgeber will Bobs Platz leeren -> ${r.status}`)
r = await call('POST', `/api/tables/${tid}/join`, { cookie: carla })
ok(r.status === 200 && r.json?.mySeat === 3, `Carla tritt bei -> Platz ${r.json?.mySeat}`)
r = await call('POST', `/api/tables/${tid}/leave`, { cookie: carla })
ok(r.status === 200 && r.json?.closed === false, 'Carla geht wieder (Platz frei)')
r = await call('PUT', `/api/tables/${tid}`, { cookie: owner, body: { name: 'Freitagsrunde II', tempo: 'NORMAL' } })
ok(r.status === 200 && r.json?.name === 'Freitagsrunde II' && r.json?.tempo === 'NORMAL', 'Name/Tempo geaendert')
await call('PUT', `/api/tables/${tid}`, { cookie: owner, body: { tempo: 'BLITZ' } })

// ---- Start: 3 Spieler (Owner, Bob, Bot), Platz 4 faellt weg
r = await call('POST', `/api/tables/${tid}/start`, { cookie: owner })
ok(r.status === 200 && r.json?.state === 'RUNNING' && r.json?.gameId, `Start -> ${r.status} ${r.json?.state} ${r.json?.error ?? ''}`)
const gameId = r.json?.gameId
r = await call('GET', '/api/games/current', { cookie: bob })
ok(r.json?.gameId === gameId, 'Bob: /api/games/current = Tisch-Spiel')
r = await call('POST', `/api/tables/${tid}/join`, { cookie: carla })
ok(r.status === 409, `Beitritt waehrend des Spiels -> ${r.status}`)
r = await call('GET', '/api/tables', { cookie: carla })
ok(r.json?.find((t) => t.id === tid)?.state === 'RUNNING', 'Lobby zeigt den Tisch als spielend')

function pilot(cookie) {
  const st = { hello: null, over: null, prompts: 0, last: null }
  // Origin wie ein Browser mitschicken: der Server prueft ihn im Server-Modus (ausser --dev)
  const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/ws/game/${gameId}`, { headers: { Cookie: cookie, Origin: base } })
  st.ws = ws
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data)
    if (m.t === 'hello') st.hello = m
    if (m.t === 'state') st.last = m
    if (m.t === 'gameOver') st.over = m
    if (m.t !== 'prompt') return
    st.prompts++
    let ans
    if (m.kind === 'ASK') ans = { bool: !m.mulligan }
    else if (m.kind === 'SELECT' && m.mode !== 'priority') ans = { bool: true }
    else if (m.kind === 'SELECT') {
      const land = st.last?.hand.find((c) => st.last.actions?.includes(c.id) && c.types?.includes('LAND'))
      ans = land ? { uuid: land.id } : { bool: false }
    } else if (m.kind === 'PICK_TARGET') ans = m.targets?.length && !m.chosen?.length ? { uuid: m.targets[0] } : { bool: false }
    else if (m.kind === 'CHOOSE_CHOICE') ans = { str: m.choice.keyed ? m.choice.items[0].key : m.choice.items[0].value }
    else if (m.kind === 'AMOUNT') ans = { int: m.min ?? 0 }
    else if (m.kind === 'MULTI_AMOUNT') ans = { str: m.items.map((i) => i.value).join(' ') }
    else ans = m.choices?.length ? { uuid: m.choices[0].id } : { bool: false }
    ws.send(JSON.stringify({ t: 'respond', id: m.id, ...ans }))
  }
  return st
}
const A = pilot(owner)
const B = pilot(bob)
await sleep(3000)
ok(A.hello?.seats?.length === 3 && A.hello.seats.filter((s) => s.human).length === 2, `Spiel hat ${A.hello?.seats?.length} Sitze, davon ${A.hello?.seats?.filter((s) => s.human).length} Menschen`)
ok(A.hello?.host === true && B.hello?.host === false, 'Gastgeber-Flag im Spiel')
const t0 = Date.now()
while (Date.now() - t0 < 45000 && !A.over) await sleep(1000)
ok(A.prompts > 0 && B.prompts > 0, `Prompts: Owner=${A.prompts} Bob=${B.prompts} (Zug ${A.last?.turn})`)
B.ws.send(JSON.stringify({ t: 'leave' }))
await sleep(2000)
A.ws.send(JSON.stringify({ t: 'leave' }))
const t1 = Date.now()
while (Date.now() - t1 < 30000 && !A.over) await sleep(500)
ok(!!A.over && !!B.over, `beide gameOver nach Aufgeben: ${!!A.over}/${!!B.over}`)

// ---- Tisch zurueck in der Lobby, Revanche moeglich
let back = null
for (let i = 0; i < 10 && !back; i++) {
  await sleep(1000)
  r = await call('GET', `/api/tables/${tid}`, { cookie: owner })
  if (r.json?.state === 'LOBBY') back = r.json
}
ok(back && back.lastGameId === gameId && back.gameId === null, `Tisch wieder in der Lobby (lastGameId gesetzt)`)
ok(back?.seats?.[0]?.ready && back?.seats?.[1]?.ready && back?.seats?.[2]?.kind === 'BOT', 'Plaetze und Decks fuer die Revanche erhalten')

// ---- Gastgeber schliesst, Bob fliegt raus
r = await call('POST', `/api/tables/${tid}/leave`, { cookie: owner })
ok(r.status === 200 && r.json?.closed === true, 'Gastgeber schliesst den Tisch')
r = await call('GET', `/api/tables/${tid}`, { cookie: bob })
ok(r.status === 404, `Bob: Tisch weg -> ${r.status}`)
r = await call('GET', '/api/tables/mine', { cookie: bob })
ok(r.status === 404, `Bob sitzt nirgends mehr -> ${r.status}`)

// Aufraeumen
A.ws.close()
B.ws.close()
await call('DELETE', `/api/admin/invites/${bobId}`, { cookie: owner })
await call('DELETE', `/api/admin/invites/${carlaId}`, { cookie: owner })
console.log(failed === 0 ? '\n=== alles gruen ===' : `\n=== ${failed} Pruefungen fehlgeschlagen ===`)
process.exit(failed === 0 ? 0 : 1)
