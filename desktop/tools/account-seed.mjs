// Vorbereitung fuer steps-account.json: gegen die Engines aus "ACCOUNT_ONLY_START=1 node scripts/e2e-account.mjs"
// (X = Server 7431, Y = Desktop mit --account 7432). Legt auf X das Konto "Dana" mit einem Deck an, auf Y (offline)
// ein lokales Deck, meldet Y ab (Gate) und schreibt die Session nach engine/run/account-host/seed.json.
//   node desktop/tools/account-seed.mjs
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const X = process.env.ACCOUNT_X ?? 'http://127.0.0.1:7431'
const Y = process.env.ACCOUNT_Y ?? 'http://127.0.0.1:7432'

async function call(base, method, p, { body, cookie } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (cookie) headers.Cookie = cookie
  const r = await fetch(base + p, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined })
  const t = await r.text()
  return { status: r.status, json: t ? JSON.parse(t) : null, cookie: (r.headers.get('set-cookie') || '').split(';')[0] }
}
const deck = (rel) => fs.readFileSync(path.join(root, 'engine', 'src', 'main', 'resources', 'sample-decks', rel), 'utf8')

const owner = (await call(X, 'POST', '/api/auth/login', { body: { code: process.env.MAGELITE_OWNER_CODE || 'DEV-OWNER-CODE' } })).cookie
const inv = (await call(X, 'POST', '/api/admin/invites', { cookie: owner, body: { name: 'Dana' } })).json
const dana = (await call(X, 'POST', '/api/auth/login', { body: { code: inv.code } })).cookie
await call(X, 'POST', '/api/decks', { cookie: dana, body: { text: deck('Commander 2014/Forged in Stone.dck'), folder: '' } })
await call(X, 'PUT', '/api/profile', { cookie: dana, body: { name: 'Dana' } })
// lokales Deck (Offline-Nutzung) fuer die Karte "ins Konto uebernehmen"
await call(Y, 'POST', '/api/account/sync', { body: { server: X, session: null, offline: true } })
await call(Y, 'POST', '/api/decks', { body: { text: deck('Commander 2014/Built from Scratch.dck') } })
await call(Y, 'POST', '/api/decks', { body: { text: deck('Commander 2014/Guided by Nature.dck') } })
await call(Y, 'POST', '/api/account/sync', { body: { server: X, session: null } })
const out = path.join(root, 'engine', 'run', 'account-host', 'seed.json')
fs.writeFileSync(out, JSON.stringify({ server: X, session: dana.split('=')[1], code: inv.code }))
console.log(`Seed ok: Dana (${inv.id}), ${out}`)
