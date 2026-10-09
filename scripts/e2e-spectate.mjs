// End-to-End-Test fuer das Zuschauen (/ws/game/{id}?spectate=1) gegen eine laufende Dev-Engine im Server-Modus:
//   isolierte Engine im Server-Modus starten (nie Port 7317), dann
//   MAGELITE_URL=http://127.0.0.1:7401 MAGELITE_OWNER_CODE=DEV-OWNER-CODE node scripts/e2e-spectate.mjs
// Ablauf: Owner + Bob + Bot an einem Tisch, Start -> Carla schaut zu: hello-Flags, State-Invarianten, Close-Codes
// (4403 ohne ?spectate, 4409 sitzt selbst, 4404 kein Tisch-Spiel, 4429 9. Zuschauer, 4000 zweites Fenster), Eingaben
// des Zuschauers wirken nicht, Chat kommt an, Spielende ohne Belohnung, Lobby zeigt turn/canSpectate/spectators.
// Env: MAGELITE_URL (Pflicht), MAGELITE_OWNER_CODE (Pflicht)
const base = process.env.MAGELITE_URL
const ownerCode = process.env.MAGELITE_OWNER_CODE
if (!base || !ownerCode) {
  console.error('MAGELITE_URL und MAGELITE_OWNER_CODE noetig (z. B. http://127.0.0.1:7401) - kein Default, nie gegen 7317 testen')
  process.exit(2)
}

let failed = 0
const ok = (cond, what) => {
  console.log(`${cond ? 'OK  ' : 'FAIL'} ${what}`)
  if (!cond) failed++
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
async function call(method, path, { body, cookie, ip } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (cookie) headers.Cookie = cookie
  // Login-Rate-Limit (10/min pro IP): die Engine nimmt die Client-IP aus Fly-Client-IP
  if (ip) headers['Fly-Client-IP'] = ip
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
const wsUrl = (gameId, spectate) => `${base.replace(/^http/, 'ws')}/ws/game/${gameId}${spectate ? '?spectate=1' : ''}`
const tag = Math.random().toString(36).slice(2, 6)

// ---- Konten
let r = await call('POST', '/api/auth/login', { body: { code: ownerCode }, ip: '10.77.1.1' })
const owner = cookieOf(r.setCookie)
const ownerName = r.json?.user?.name
const accounts = {}
async function account(name) {
  const inv = await call('POST', '/api/admin/invites', { cookie: owner, body: { name: `${name}-${tag}` } })
  const n = Object.keys(accounts).length + 1
  const lg = await call('POST', '/api/auth/login', { body: { code: inv.json?.code }, ip: `10.77.0.${n}` })
  accounts[name] = { id: inv.json?.id, name: `${name}-${tag}`, cookie: cookieOf(lg.setCookie) }
  return accounts[name]
}
const bob = await account('Bob')
const carla = await account('Carla')
const dave = await account('Dave')
const extra = []
for (let i = 1; i <= 8; i++) extra.push(await account(`S${i}`))
ok(owner && bob.cookie && carla.cookie && dave.cookie && extra.every((a) => a.cookie), `Owner + ${Object.keys(accounts).length} Konten angemeldet`)
const samples = (await call('GET', '/api/samples', { cookie: owner })).json
const sample = (i) => ({ type: 'sample', id: samples[i].id })

// ---- Tisch: Owner, Bob, Bot
// NORMAL statt BLITZ: Forge-Bots beenden ein BLITZ-Spiel sonst, bevor alle Zuschauer-Faelle durch sind
r = await call('POST', '/api/tables', { cookie: owner, body: { name: `Zuschau-Test ${tag}`, tempo: 'NORMAL' } })
ok(r.status === 200, `Tisch eroeffnet -> ${r.status} ${r.json?.error ?? ''}`)
const tid = r.json?.id
const tableName = r.json?.name
r = await call('POST', `/api/tables/${tid}/join`, { cookie: bob.cookie })
ok(r.status === 200, `Bob tritt bei -> ${r.status}`)
await call('PUT', `/api/tables/${tid}/seat`, { cookie: owner, body: { deck: sample(0) } })
await call('PUT', `/api/tables/${tid}/seat`, { cookie: bob.cookie, body: { deck: sample(1) } })
await call('PUT', `/api/tables/${tid}/seats/2`, { cookie: owner, body: { kind: 'BOT', deck: sample(2) } })
r = await call('POST', `/api/tables/${tid}/start`, { cookie: owner })
ok(r.status === 200 && r.json?.state === 'RUNNING', `Start -> ${r.status} ${r.json?.state} ${r.json?.error ?? ''}`)
const gameId = r.json?.gameId

/** Sitz-Autopilot (wie e2e-tables); {@code hold}: Prompts nicht beantworten (nur merken). */
function pilot(cookie) {
  const st = { hello: null, over: null, prompts: 0, last: null, states: 0, hands: new Map(), chat: [], seats: null, closed: new Set(), hold: false, open: null, closeCode: null }
  const ws = new WebSocket(wsUrl(gameId), { headers: { Cookie: cookie, Origin: base } })
  st.ws = ws
  ws.onclose = (ev) => (st.closeCode = ev.code)
  st.answer = (m) => {
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
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data)
    if (m.t === 'hello') st.hello = m
    if (m.t === 'state') {
      st.last = m
      st.states++
      const priv = new Set(m.hand.map((c) => c.id))
      for (const la of m.lookedAt ?? []) for (const c of la.cards) priv.add(c.id)
      for (const p of m.players) if (p.topCardPrivate && p.topCard) priv.add(p.topCard.id)
      st.hands.set(m.seq, priv)
    }
    if (m.t === 'gameOver') st.over = m
    if (m.t === 'chat') st.chat.push(...m.entries)
    if (m.t === 'seats') st.seats = m
    if (m.t === 'promptClosed') st.closed.add(m.id)
    if (m.t !== 'prompt') return
    st.prompts++
    if (st.hold) {
      st.open = m
      return
    }
    st.answer(m)
  }
  return st
}

const HIDDEN_REFS = new Set(['verdeckte Karte', 'verdecktes Permanent', 'verdeckter Zauber'])
/** Zuschauer-Verbindung: sammelt alles und prueft jede Nachricht auf Lecks. */
function spectator(acc, { check = true } = {}) {
  const st = { hello: null, states: [], over: null, chat: [], chatBundles: 0, seats: null, closeCode: null, open: false, errors: [], kinds: new Map(), events: 0 }
  const ws = new WebSocket(wsUrl(gameId, true), { headers: { Cookie: acc.cookie, Origin: base } })
  st.ws = ws
  ws.onopen = () => (st.open = true)
  ws.onclose = (ev) => (st.closeCode = ev.code)
  const err = (msg) => {
    if (st.errors.length < 20) st.errors.push(msg)
  }
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data)
    st.kinds.set(m.t, (st.kinds.get(m.t) ?? 0) + 1)
    if (!check) return
    switch (m.t) {
      case 'hello':
        st.hello = m
        break
      case 'state': {
        if (!st.hello) err('state vor hello')
        st.states.push(m)
        if (m.spectator !== true) err(`state.spectator=${m.spectator}`)
        if (m.myPlayerId || m.playable || m.actions || m.lookedAt || m.replDeclines) err(`privates Feld im State ${m.seq}`)
        if (!Array.isArray(m.hand) || m.hand.length) err(`Hand nicht leer (${m.hand?.length})`)
        const me = m.players.filter((p) => p.me)
        if (me.length !== 1 || !m.players[0]?.me || m.players[0].id !== st.hello?.viewpointId) err(`me-Flag: ${me.length}x, players[0]=${m.players[0]?.name}`)
        const cards = []
        for (const p of m.players) {
          if (p.topCardPrivate) err(`topCardPrivate bei ${p.name}`)
          cards.push(...p.graveyard, ...p.exile, ...p.battlefield)
          if (p.topCard) cards.push(p.topCard)
        }
        cards.push(...m.stack)
        for (const c of cards) if (c.faceDown && (c.name || c.set || c.num || c.back)) err(`verdeckte Karte mit Infos: ${c.name} ${c.set} ${c.num}`)
        break
      }
      case 'prompt':
      case 'promptClosed':
      case 'seat':
      case 'toast':
        err(`persoenliche Nachricht: ${m.t}`)
        break
      case 'events':
        for (const e of m.items) {
          st.events++
          if (e.hidden) err(`verdecktes Ereignis ${e.kind}`)
        }
        break
      case 'activity':
        if (m.mode === 'you') err("activity 'you'")
        break
      case 'chat':
        st.chat.push(...m.entries)
        if (m.entries.length > 1) st.chatBundles++
        break
      case 'seats':
        st.seats = m
        break
      case 'gameOver':
        st.over = m
        break
    }
  }
  return st
}
/** Wartet auf das Schliessen und liefert den Code (null = offen geblieben). */
async function closeCodeOf(st, ms = 4000) {
  const t0 = Date.now()
  while (Date.now() - t0 < ms && st.closeCode === null) await sleep(100)
  return st.closeCode
}
/** Freie ids eines Zuschauer-States (ohne revealed, verdeckte Ziele, Quellen von Stapel-Faehigkeiten, aufgedeckte oberste Karten). */
function visibleIds(s) {
  const allowed = new Set()
  for (const c of s.stack) {
    for (const t of c.targetRefs ?? []) if (HIDDEN_REFS.has(t.name)) allowed.add(t.id)
    if (c.kind === 'ability' && c.sourceId) allowed.add(c.sourceId)
  }
  for (const p of s.players) if (p.topCard) allowed.add(p.topCard.id)
  const { revealed, ...rest } = s
  const ids = new Set(JSON.stringify(rest).match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g) ?? [])
  for (const a of allowed) ids.delete(a)
  return ids
}

// ---- Dave: eigenes Solo-Spiel (kein Tisch) fuer den 4404-Fall
r = await call('POST', '/api/games', { cookie: dave.cookie, body: { deck: { type: 'random' }, tempo: 'BLITZ' } })
ok(r.status === 200 && r.json?.gameId, `Dave startet ein Solo-Spiel -> ${r.status} ${r.json?.error ?? ''}`)
const daveGame = r.json?.gameId

const A = pilot(owner)
const B = pilot(bob.cookie)
await sleep(2500)
ok(A.hello && B.hello, 'Sitze verbunden')
// Chat vor dem Zuschauen -> kommt beim Verbinden als Buendel
A.ws.send(JSON.stringify({ t: 'chat', text: 'vor dem Zuschauen' }))
await sleep(500)

// ---- Lobby
let t0 = Date.now()
while (Date.now() - t0 < 30000 && (A.last?.turn ?? 0) < 1) await sleep(500)
r = await call('GET', '/api/tables', { cookie: carla.cookie })
{
  const t = r.json?.find((x) => x.id === tid)
  ok(t?.state === 'RUNNING' && t?.turn > 0 && t?.canSpectate === true && t?.spectators === 0, `Lobby (Carla): ${t?.state} Zug ${t?.turn} canSpectate=${t?.canSpectate} spectators=${t?.spectators}`)
}
r = await call('GET', `/api/tables/${tid}`, { cookie: bob.cookie })
ok(r.json?.canSpectate === false, `Lobby (Bob, sitzt): canSpectate=${r.json?.canSpectate}`)

// ---- Close-Codes
{
  const plain = new WebSocket(wsUrl(gameId), { headers: { Cookie: carla.cookie, Origin: base } })
  const st = { closeCode: null }
  plain.onclose = (ev) => (st.closeCode = ev.code)
  ok((await closeCodeOf(st)) === 4403, `Carla ohne ?spectate -> ${st.closeCode}`)
}
{
  const before = B.states
  const st = spectator(bob)
  ok((await closeCodeOf(st)) === 4409, `Bob mit ?spectate (sitzt) -> ${st.closeCode}`)
  await sleep(2500)
  ok(B.closeCode === null && B.states > before, `Bobs Sitz-Verbindung laeuft weiter (${before} -> ${B.states} States)`)
}
{
  const fake = { closeCode: null }
  const ws = new WebSocket(wsUrl('00000000-0000-4000-8000-000000000000', true), { headers: { Cookie: carla.cookie, Origin: base } })
  ws.onclose = (ev) => (fake.closeCode = ev.code)
  ok((await closeCodeOf(fake)) === 4404, `unbekanntes Spiel -> ${fake.closeCode}`)
  const solo = { closeCode: null }
  const ws2 = new WebSocket(`${base.replace(/^http/, 'ws')}/ws/game/${daveGame}?spectate=1`, { headers: { Cookie: carla.cookie, Origin: base } })
  ws2.onclose = (ev) => (solo.closeCode = ev.code)
  ok((await closeCodeOf(solo)) === 4404, `Solo-Spiel ohne Tisch -> ${solo.closeCode}`)
}
// Daves Solo-Spiel beenden (gibt den Spiel-Platz frei)
{
  const D = new WebSocket(wsUrl(daveGame), { headers: { Cookie: dave.cookie, Origin: base } })
  await new Promise((res) => (D.onopen = res))
  D.send(JSON.stringify({ t: 'leave' }))
  await sleep(1000)
  D.close()
}

// ---- Carla schaut zu
let C = spectator(carla)
await sleep(2500)
ok(C.open && C.closeCode === null, `Carla verbunden (close=${C.closeCode})`)
ok(C.hello?.spectator === true && C.hello?.host === false && !('myPlayerId' in (C.hello ?? {})), `hello: spectator=${C.hello?.spectator} host=${C.hello?.host} myPlayerId=${C.hello?.myPlayerId}`)
ok(C.hello?.viewpointId === A.hello?.myPlayerId && C.hello?.tableName === tableName && C.hello?.seats?.length === 3, `hello: Blickwinkel=Owner ${C.hello?.viewpointId === A.hello?.myPlayerId}, Tisch "${C.hello?.tableName}", ${C.hello?.seats?.length} Sitze`)
ok(C.states.length > 0, `Zuschauer bekam States (${C.states.length})`)
ok(C.chat.length === 1 && C.chat[0].text === 'vor dem Zuschauen', `Chat-Verlauf beim Verbinden (${C.chat.length} Zeilen: ${C.chat[0]?.text})`)
ok(A.seats?.spectators?.includes(carla.name), `Owner sieht Zuschauer: ${JSON.stringify(A.seats?.spectators)}`)
ok(C.seats?.spectators?.includes(carla.name), 'Zuschauer bekommt seats mit Zuschauernamen')

// ---- Eingaben des Zuschauers wirken nicht
B.hold = true
t0 = Date.now()
while (Date.now() - t0 < 30000 && !B.open) await sleep(200)
ok(!!B.open, `Bob hat einen offenen Prompt (${B.open?.kind})`)
if (B.open) {
  C.ws.send(JSON.stringify({ t: 'respond', id: B.open.id, bool: false }))
  C.ws.send(JSON.stringify({ t: 'respond', id: B.open.id, uuid: B.open.choices?.[0]?.id ?? '00000000-0000-4000-8000-000000000000' }))
  await sleep(1200)
  ok(!B.closed.has(B.open.id), `Zuschauer-Antwort schliesst Bobs Prompt nicht`)
}
const chatsA = A.chat.length
C.ws.send(JSON.stringify({ t: 'chat', text: 'Zuschauer-Spam' }))
C.ws.send(JSON.stringify({ t: 'leave' }))
C.ws.send(JSON.stringify({ t: 'action', action: 'CONCEDE' }))
C.ws.send(JSON.stringify({ t: 'kick', playerId: B.hello?.myPlayerId }))
C.ws.send(JSON.stringify({ t: 'tempo', preset: 'MAX' }))
C.ws.send(JSON.stringify({ t: 'settings', autoPay: false, autoPass: false }))
C.ws.send('kein json')
await sleep(1500)
ok(A.chat.length === chatsA && !A.chat.some((c) => c.text === 'Zuschauer-Spam'), 'Zuschauer-Chat kommt nirgends an')
r = await call('GET', '/api/health')
ok(r.json?.games >= 1 && !A.over && !B.over, `Spiel laeuft weiter nach leave/CONCEDE/kick des Zuschauers (games=${r.json?.games})`)
ok(C.closeCode === null, 'Zuschauer bleibt verbunden')
if (B.open) {
  B.hold = false
  B.answer(B.open)
  B.open = null
} else {
  B.hold = false
}
// Ping -> pong
{
  let pong = false
  const prev = C.ws.onmessage
  C.ws.onmessage = (ev) => {
    if (JSON.parse(ev.data).t === 'pong') pong = true
    prev(ev)
  }
  C.ws.send(JSON.stringify({ t: 'ping' }))
  await sleep(800)
  ok(pong, 'Zuschauer: ping -> pong')
}
// Chat eines Spielers kommt beim Zuschauer an
A.ws.send(JSON.stringify({ t: 'chat', text: 'hallo Zuschauer' }))
await sleep(800)
ok(C.chat.some((c) => c.text === 'hallo Zuschauer' && c.name === ownerName), 'Spieler-Chat kommt beim Zuschauer an')

// ---- 8 Zuschauer erlaubt, der 9. nicht; zweites Fenster ersetzt das erste
const S = extra.slice(0, 7).map((a) => spectator(a, { check: false }))
await sleep(2500)
ok(S.every((s) => s.open && s.closeCode === null), `7 weitere Zuschauer verbunden (${S.filter((s) => s.open && s.closeCode === null).length}/7)`)
r = await call('GET', '/api/tables', { cookie: extra[7].cookie })
{
  const t = r.json?.find((x) => x.id === tid)
  ok(t?.spectators === 8 && t?.canSpectate === false, `Lobby: spectators=${t?.spectators}, canSpectate=${t?.canSpectate} (voll)`)
}
const ninth = spectator(extra[7], { check: false })
ok((await closeCodeOf(ninth)) === 4429, `9. Zuschauer -> ${ninth.closeCode}`)
const C2 = spectator(carla)
ok((await closeCodeOf(C, 4000)) === 4000, `Carla im zweiten Fenster: altes -> ${C.closeCode}`)
await sleep(1500)
ok(C2.closeCode === null && C2.hello?.spectator === true && C2.states.length > 0, `zweites Fenster laeuft (${C2.states.length} States)`)
for (const s of S) s.ws.close()
await sleep(1500)
ok(A.seats?.spectators?.length === 1, `nach dem Schliessen: ${JSON.stringify(A.seats?.spectators)}`)
const again = spectator(extra[7], { check: false })
await sleep(1500)
ok(again.open && again.closeCode === null, `danach ist wieder Platz (close=${again.closeCode}, Spielende=${!!A.over})`)
again.ws.close()

// ---- spielen lassen, Lecks pruefen
t0 = Date.now()
while (Date.now() - t0 < 25000 && !A.over) await sleep(1000)
const all = [...C.states, ...C2.states]
let leaks = 0
let compared = 0
const leakInfo = []
for (const s of all) {
  const vis = visibleIds(s)
  for (const P of [A, B]) {
    const priv = P.hands.get(s.seq)
    if (!priv) continue
    compared++
    for (const id of priv) {
      if (vis.has(id)) {
        leaks++
        if (leakInfo.length < 5) leakInfo.push(`seq ${s.seq} id ${id}`)
      }
    }
  }
}
ok(compared > 0 && leaks === 0, `keine Hand-/lookedAt-ids im Zuschauer-State (${compared} Vergleiche gleicher seq, ${leaks} Lecks ${leakInfo.join(', ')})`)
const errs = [...C.errors, ...C2.errors]
ok(errs.length === 0, `Zuschauer-Invarianten (${all.length} States, ${C.events + C2.events} Ereignisse) ${errs.slice(0, 5).join(' | ')}`)

// ---- Spielende: beide geben auf -> Zuschauer bekommt gameOver ohne Belohnung
B.ws.send(JSON.stringify({ t: 'leave' }))
await sleep(1500)
A.ws.send(JSON.stringify({ t: 'leave' }))
t0 = Date.now()
while (Date.now() - t0 < 30000 && !(A.over && C2.over)) await sleep(500)
ok(!!A.over?.reward, `Owner gameOver mit Belohnung: ${!!A.over?.reward}`)
ok(!!C2.over && C2.over.reward === undefined && C2.over.placements?.length === 3, `Zuschauer gameOver ohne Belohnung (${C2.over?.result})`)
ok(C2.closeCode === null, 'Zuschauer bleibt nach dem Spielende verbunden')
let back = false
for (let i = 0; i < 10 && !back; i++) {
  await sleep(1000)
  r = await call('GET', `/api/tables/${tid}`, { cookie: carla.cookie })
  back = r.json?.state === 'LOBBY'
}
ok(back && r.json?.canSpectate === false, `Tisch wieder in der Lobby, canSpectate=${r.json?.canSpectate}`)
const late = spectator(carla)
ok((await closeCodeOf(late)) === 4404, `Zuschauen nach dem Spielende -> ${late.closeCode}`)

// Aufraeumen
C2.ws.close()
A.ws.close()
B.ws.close()
await call('POST', `/api/tables/${tid}/leave`, { cookie: owner })
for (const a of Object.values(accounts)) await call('DELETE', `/api/admin/invites/${a.id}`, { cookie: owner })
console.log(failed === 0 ? '\n=== alles gruen ===' : `\n=== ${failed} Pruefungen fehlgeschlagen ===`)
process.exit(failed === 0 ? 0 : 1)
