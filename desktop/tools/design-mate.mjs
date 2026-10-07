// Zweiter Mensch fuer steps-design-server.json (Server-Modus, gradlew runServer): legt per Owner-Code eine
// Einladung "Bob" an, wartet auf einen offenen Tisch des Owners, tritt bei, waehlt ein Deck, schreibt in den
// Tisch-Chat und spielt nach dem Start passiv mit (Laender, sonst passen) inkl. Chatnachricht im Spiel.
// Aufruf (vor den Steps starten):  MAGELITE_URL=http://127.0.0.1:7401 node desktop/tools/design-mate.mjs
const base = process.env.MAGELITE_URL
if (!base) {
  console.error('MAGELITE_URL fehlt (z. B. http://127.0.0.1:7401) - kein Default, nie gegen 7317')
  process.exit(2)
}
const ownerCode = process.env.MAGELITE_OWNER_CODE ?? 'DEV-OWNER-CODE'
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

let r = await call('POST', '/api/auth/login', { body: { code: ownerCode } })
const owner = cookieOf(r.setCookie)
r = await call('POST', '/api/admin/invites', { cookie: owner, body: { name: 'Bob' } })
r = await call('POST', '/api/auth/login', { body: { code: r.json?.code } })
const bob = cookieOf(r.setCookie)
console.log('Bob angemeldet:', !!bob)
const samples = (await call('GET', '/api/samples', { cookie: bob })).json
const elves = samples.find((s) => /Elven Empire/.test(s.name)) ?? samples[0]

// auf einen NEUEN Tisch warten (max. 5 min); alte Tische frueherer Laeufe ignorieren
const since = Date.now()
let table = null
for (let i = 0; i < 300 && !table; i++) {
  const list = (await call('GET', '/api/tables', { cookie: bob })).json ?? []
  table = list.find((t) => t.state !== 'RUNNING' && t.humans < 4 && t.createdAt >= since) ?? null
  if (!table) await sleep(1000)
}
if (!table) {
  console.log('kein Tisch gefunden')
  process.exit(1)
}
const tid = table.id
r = await call('POST', `/api/tables/${tid}/join`, { cookie: bob })
console.log('beigetreten, Platz', r.json?.mySeat)
await call('PUT', `/api/tables/${tid}/seat`, { cookie: bob, body: { deck: { type: 'sample', id: elves.id } } })
await sleep(800)
await call('POST', `/api/tables/${tid}/chat`, { cookie: bob, body: { text: 'Moin! Bin dabei – Elfen gegen alle 🌲' } })

// auf den Start warten (max. 5 min), dann passiv mitspielen
let gameId = null
for (let i = 0; i < 300 && !gameId; i++) {
  const t = (await call('GET', `/api/tables/${tid}`, { cookie: bob })).json
  if (t?.state === 'RUNNING' && t.gameId) gameId = t.gameId
  else await sleep(1000)
}
if (!gameId) {
  console.log('Spiel nicht gestartet')
  process.exit(1)
}
console.log('Spiel', gameId)
const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/ws/game/${gameId}`, { headers: { Cookie: bob, Origin: base } })
let last = null
let chatted = false
let over = false
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data)
  if (m.t === 'state') {
    last = m
    if (!chatted) {
      chatted = true
      setTimeout(() => ws.send(JSON.stringify({ t: 'chat', text: 'gl hf! 🍀' })), 4000)
    }
  }
  if (m.t === 'gameOver') over = true
  if (m.t !== 'prompt') return
  let ans
  if (m.kind === 'ASK') ans = { bool: !m.mulligan }
  else if (m.kind === 'SELECT' && m.mode !== 'priority') ans = { bool: true }
  else if (m.kind === 'SELECT') {
    const land = last?.hand.find((c) => last.actions?.includes(c.id) && c.types?.includes('LAND'))
    ans = land ? { uuid: land.id } : { bool: false }
  } else if (m.kind === 'PICK_TARGET') ans = m.targets?.length && !m.chosen?.length ? { uuid: m.targets[0] } : { bool: false }
  else if (m.kind === 'CHOOSE_CHOICE') ans = { str: m.choice.keyed ? m.choice.items[0].key : m.choice.items[0].value }
  else if (m.kind === 'AMOUNT') ans = { int: m.min ?? 0 }
  else if (m.kind === 'MULTI_AMOUNT') ans = { str: m.items.map((i) => i.value).join(' ') }
  else ans = m.choices?.length ? { uuid: m.choices[0].id } : { bool: false }
  ws.send(JSON.stringify({ t: 'respond', id: m.id, ...ans }))
}
// spaetestens nach 4 min aufgeben
for (let i = 0; i < 240 && !over; i++) await sleep(1000)
if (!over) ws.send(JSON.stringify({ t: 'leave' }))
await sleep(1500)
ws.close()
console.log('Bob fertig')
process.exit(0)
