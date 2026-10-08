// Testdaten fuer die Relay-Screenshots (steps-relay.json) gegen zwei laufende Engines
// (RELAY_ONLY_START=1 node scripts/e2e-relay.mjs): Bob bindet die Host-Engine Y an X, eroeffnet einen privaten Tisch
// auf seinem Rechner; auf X liegt ein Dummy-Setup fuer die Download-Karte der Startseite.
//   node desktop/tools/relay-seed.mjs            (RELAY_X / RELAY_Y ueberschreiben die Ports 7411/7412)
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const X = process.env.RELAY_X ?? 'http://127.0.0.1:7411'
const Y = process.env.RELAY_Y ?? 'http://127.0.0.1:7412'
const ownerCode = process.env.MAGELITE_OWNER_CODE ?? 'DEV-OWNER-CODE'

async function call(base, method, p, { body, cookie } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (cookie) headers.Cookie = cookie
  const r = await fetch(base + p, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined })
  const t = await r.text()
  let json = null
  try {
    json = t ? JSON.parse(t) : null
  } catch {
    json = t
  }
  if (!r.ok) throw new Error(`${method} ${p} -> ${r.status} ${JSON.stringify(json)}`)
  return { json, setCookie: r.headers.get('set-cookie') }
}
const cookieOf = (sc) => (sc ? sc.split(';')[0] : null)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// Dummy-Setup fuer die Download-Karte (die Engine liest den Ordner <data>/downloads)
const dl = path.join(root, 'engine', 'run', 'relay-fly', 'downloads')
fs.mkdirSync(dl, { recursive: true })
const dummy = path.join(dl, 'MageLite-Setup-0.1.6.exe')
if (!fs.existsSync(dummy)) fs.writeFileSync(dummy, Buffer.alloc(3 * 1024 * 1024))

let r = await call(X, 'POST', '/api/auth/login', { body: { code: ownerCode } })
const owner = cookieOf(r.setCookie)
const existing = (await call(X, 'GET', '/api/admin/invites', { cookie: owner })).json
let bob = existing.find?.((u) => u.name === 'Bob')
if (bob) {
  r = await call(X, 'POST', `/api/admin/invites/${bob.id}/rotate`, { cookie: owner })
} else {
  r = await call(X, 'POST', '/api/admin/invites', { cookie: owner, body: { name: 'Bob' } })
}
const bobCode = r.json.code
r = await call(X, 'POST', '/api/auth/login', { body: { code: bobCode } })
const bobCookie = cookieOf(r.setCookie)
const bobToken = bobCookie.split('=')[1]

await call(Y, 'POST', '/api/host/link', { body: { server: X, session: bobToken } })
let linked = false
for (let i = 0; i < 40 && !linked; i++) {
  await sleep(250)
  linked = (await call(Y, 'GET', '/api/host/link')).json.connected === true
}
if (!linked) throw new Error('Y haengt nicht an X')

// Bob: alten Tisch schliessen, neuen privaten REMOTE-Tisch mit Bot
try {
  const mine = (await call(X, 'GET', '/api/tables/mine', { cookie: bobCookie })).json
  await call(X, 'POST', `/api/tables/${mine.id}/leave`, { cookie: bobCookie })
} catch {
  /* kein Tisch */
}
const samples = (await call(X, 'GET', '/api/samples', { cookie: bobCookie })).json
r = await call(X, 'POST', '/api/tables', { cookie: bobCookie, body: { name: 'Bobs Keller', hosting: 'REMOTE', password: 'geheim', tempo: 'NORMAL' } })
const tid = r.json.id
await call(X, 'PUT', `/api/tables/${tid}/seat`, { cookie: bobCookie, body: { deck: { type: 'sample', id: samples[0].id } } })
await call(X, 'PUT', `/api/tables/${tid}/seats/3`, { cookie: bobCookie, body: { kind: 'BOT', deck: { type: 'sample', id: samples[1].id } } })
console.log(`Bob (${bobCookie ? 'angemeldet' : '?'}) hostet Tisch ${tid} auf Y; Owner-Code ${ownerCode}; Setup-Dummy ${dummy}`)
