// End-to-End-Test fuer den Admin-Bereich gegen eine Engine im Server-Modus:
//   isolierte Engine im Server-Modus starten (nie Port 7317), dann
//   MAGELITE_URL=http://127.0.0.1:7401 MAGELITE_OWNER_CODE=DEV-OWNER-CODE node scripts/e2e-admin.mjs
// Ablauf: Owner legt Bob und Carla an -> Nicht-Admin bekommt 403 -> Nutzerliste (Felder, Status online/am Tisch/im
// Spiel) -> Server-Uebersicht mit Tisch und laufendem Spiel -> Nutzer-Detail ohne Token -> Spiel beenden ->
// Tisch schliessen -> Nutzer abmelden -> eigenes Konto geschuetzt -> Aufraeumen.
const base = process.env.MAGELITE_URL
if (!base) {
  console.error('MAGELITE_URL fehlt (z. B. http://127.0.0.1:7401) - kein Default, nie gegen 7317 testen')
  process.exit(2)
}
const ownerCode = process.env.MAGELITE_OWNER_CODE ?? 'DEV-OWNER-CODE'

let failed = 0
const ok = (cond, what) => {
  console.log(`${cond ? 'OK  ' : 'FAIL'} ${what}`)
  if (!cond) failed++
}
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
const sleep = (ms) => new Promise((res) => setTimeout(res, ms))
const users = async (cookie) => (await call('GET', '/api/admin/users', { cookie })).json
const server = async (cookie) => (await call('GET', '/api/admin/server', { cookie })).json

// ---- Konten
const tag = String(Date.now() % 100000)
let r = await call('POST', '/api/auth/login', { body: { code: ownerCode } })
const owner = cookieOf(r.setCookie)
const me = (await call('GET', '/api/me', { cookie: owner })).json?.user
r = await call('POST', '/api/admin/invites', { cookie: owner, body: { name: `Bob${tag}` } })
const bobId = r.json?.id
r = await call('POST', '/api/auth/login', { body: { code: r.json?.code } })
const bob = cookieOf(r.setCookie)
r = await call('POST', '/api/admin/invites', { cookie: owner, body: { name: `Carla${tag}` } })
const carlaId = r.json?.id
r = await call('POST', '/api/auth/login', { body: { code: r.json?.code } })
const carla = cookieOf(r.setCookie)
ok(owner && bob && carla && me?.admin, 'Owner (Admin), Bob, Carla angemeldet')

// ---- Nicht-Admin
for (const [m, p] of [['GET', '/api/admin/users'], ['GET', `/api/admin/users/${carlaId}`], ['GET', '/api/admin/server'],
  ['POST', `/api/admin/users/${carlaId}/logout`], ['POST', '/api/admin/games/x/abort'], ['DELETE', '/api/admin/tables/X']]) {
  r = await call(m, p, { cookie: bob })
  ok(r.status === 403, `Bob ${m} ${p} -> ${r.status}`)
}
r = await call('GET', '/api/admin/users')
ok(r.status === 401, `ohne Cookie -> ${r.status}`)

// ---- Nutzerliste
await call('GET', '/api/social?after=0', { cookie: bob }) // Bob ist "online" (pollt)
let list = await users(owner)
let b = list?.find((u) => u.id === bobId)
ok(Array.isArray(list) && !list.some((u) => u.id === 1), `Nutzerliste: ${list?.length} Konten, ohne lokalen Nutzer 1`)
ok(b && b.name === `Bob${tag}` && b.admin === false && b.hasPassword === false && typeof b.createdAt === 'number' && typeof b.lastSeen === 'number',
  `Bob: Basisfelder (lastSeen ${b?.lastSeen})`)
ok(b && b.level >= 1 && typeof b.title === 'string' && b.xp >= 0 && b.games === 0 && b.wins === 0 && b.decks === 0 && b.sessions >= 1,
  `Bob: Level ${b?.level} "${b?.title}", Spiele ${b?.games}, Decks ${b?.decks}, Sessions ${b?.sessions}`)
ok(b?.status === 'online', `Bob: Status ${b?.status}`)
ok(list?.find((u) => u.id === me.id)?.admin === true, 'Owner als Admin markiert')

// ---- Tisch: Bob eroeffnet, Carla tritt bei
const samples = (await call('GET', '/api/samples', { cookie: owner })).json
const sample = (i) => ({ type: 'sample', id: samples[i].id })
r = await call('POST', '/api/tables', { cookie: bob, body: { name: `Admin-Test ${tag}`, tempo: 'BLITZ' } })
const tid = r.json?.id
r = await call('POST', `/api/tables/${tid}/join`, { cookie: carla })
ok(tid && r.status === 200, `Tisch ${tid} eroeffnet, Carla tritt bei`)
list = await users(owner)
b = list.find((u) => u.id === bobId)
ok(b?.status === 'table' && b?.tableName === `Admin-Test ${tag}`, `Bob: Status ${b?.status} (${b?.tableName})`)
let srv = await server(owner)
let tbl = srv?.tables?.find((t) => t.id === tid)
ok(srv && typeof srv.version === 'string' && srv.heapMax > 0 && srv.heapUsed > 0 && srv.maxGames >= 1 && srv.uptimeMs > 0,
  `Server: Version ${srv?.version}, Heap ${Math.round((srv?.heapUsed ?? 0) / 1e6)}/${Math.round((srv?.heapMax ?? 0) / 1e6)} MB, max ${srv?.maxGames} Spiele`)
ok(tbl && tbl.humans === 2 && tbl.open === 2 && tbl.state === 'LOBBY' && tbl.hostName === `Bob${tag}`, `Server: Tisch ${tbl?.name} (${tbl?.humans} Menschen, ${tbl?.open} offen)`)
ok(srv.tableCount === srv.tables.length && srv.online >= 1, `Server: ${srv.tableCount} Tische, ${srv.online} online`)

// ---- Spiel starten
await call('PUT', `/api/tables/${tid}/seat`, { cookie: bob, body: { deck: sample(0) } })
await call('PUT', `/api/tables/${tid}/seat`, { cookie: carla, body: { deck: sample(1) } })
await call('PUT', `/api/tables/${tid}/seats/2`, { cookie: bob, body: { kind: 'BOT', deck: sample(2) } })
r = await call('POST', `/api/tables/${tid}/start`, { cookie: bob })
const gameId = r.json?.gameId
ok(r.status === 200 && gameId, `Tischspiel gestartet -> ${r.status} ${gameId}`)
srv = await server(owner)
let g = srv?.games?.find((x) => x.id === gameId)
ok(g && g.table === `Admin-Test ${tag}` && g.bots === 1 && g.humans?.length === 2 && g.tempo === 'BLITZ' && g.startedAt > 0,
  `Server: Spiel am Tisch "${g?.table}", ${g?.humans?.map((h) => h.name).join('+')} + ${g?.bots} Bot`)
ok(g?.humans?.every((h) => h.conceded === false && typeof h.connected === 'boolean'), 'Server: Sitze mit connected/conceded')
ok(srv.running >= 1 && srv.running === srv.games.length, `Server: ${srv.running} laufend`)
list = await users(owner)
ok(list.find((u) => u.id === bobId)?.status === 'game', `Bob: Status ${list.find((u) => u.id === bobId)?.status}`)

// ---- Detail
r = await call('GET', `/api/admin/users/${bobId}`, { cookie: owner })
const d = r.json
ok(r.status === 200 && d?.user?.id === bobId && Array.isArray(d?.games) && Array.isArray(d?.decks) && Array.isArray(d?.sessions),
  `Detail Bob -> ${r.status} (${d?.games?.length} Partien, ${d?.decks?.length} Decks, ${d?.sessions?.length} Sessions)`)
const sessKeys = Object.keys(d?.sessions?.[0] ?? {}).sort().join(',')
ok(sessKeys === 'createdAt,id,lastSeen,via' && !JSON.stringify(d).includes('token'), `Sessions ohne Token (${sessKeys})`)
r = await call('GET', '/api/admin/users/999999', { cookie: owner })
ok(r.status === 404 && typeof r.json?.error === 'string', `Detail unbekannt -> ${r.status} "${r.json?.error}"`)
r = await call('GET', '/api/admin/users/1', { cookie: owner })
ok(r.status === 404, `Detail lokaler Nutzer 1 -> ${r.status}`)

// ---- Spiel beenden
r = await call('POST', `/api/admin/games/${gameId}/abort`, { cookie: owner })
ok(r.status === 200, `Spiel beenden -> ${r.status}`)
let gone = false
for (let i = 0; i < 40 && !gone; i++) {
  await sleep(500)
  srv = await server(owner)
  gone = !srv.games.some((x) => x.id === gameId)
}
ok(gone, 'Spiel ist aus der Uebersicht verschwunden')
r = await call('POST', `/api/admin/games/${gameId}/abort`, { cookie: owner })
ok(r.status === 404, `nochmal beenden -> ${r.status}`)
r = await call('POST', '/api/admin/games/kein-uuid/abort', { cookie: owner })
ok(r.status === 404, `ungueltige Spiel-id -> ${r.status}`)
r = await call('GET', `/api/tables/${tid}`, { cookie: bob })
ok(r.status === 200 && r.json?.state === 'LOBBY', `Tisch zurueck in der Lobby (${r.json?.state})`)

// ---- Tisch schliessen
r = await call('DELETE', `/api/admin/tables/${tid}`, { cookie: owner })
ok(r.status === 200, `Tisch schliessen -> ${r.status}`)
r = await call('GET', `/api/tables/${tid}`, { cookie: carla })
ok(r.status >= 400 && r.status < 500, `Carla pollt geschlossenen Tisch -> ${r.status}`)
r = await call('GET', '/api/tables/mine', { cookie: bob })
ok(r.status === 404, `Bob hat keinen Tisch mehr -> ${r.status}`)
r = await call('DELETE', `/api/admin/tables/${tid}`, { cookie: owner })
ok(r.status === 404, `nochmal schliessen -> ${r.status}`)

// ---- Abmelden
r = await call('POST', `/api/admin/users/${carlaId}/logout`, { cookie: owner })
ok(r.status === 200 && r.json?.ok === true, `Carla abmelden -> ${r.status}`)
r = await call('GET', '/api/me', { cookie: carla })
ok(r.status === 401, `Carla danach -> ${r.status}`)
r = await call('GET', '/api/me', { cookie: bob })
ok(r.status === 200, `Bob bleibt angemeldet -> ${r.status}`)
list = await users(owner)
ok(list.find((u) => u.id === carlaId)?.sessions === 0, 'Carla: 0 Sessions')
r = await call('POST', `/api/admin/users/${me.id}/logout`, { cookie: owner })
ok(r.status === 400, `eigenes Konto abmelden -> ${r.status}`)
r = await call('GET', '/api/me', { cookie: owner })
ok(r.status === 200, 'Owner bleibt angemeldet')

// ---- Aufraeumen
for (const id of [bobId, carlaId]) {
  r = await call('DELETE', `/api/admin/invites/${id}`, { cookie: owner })
  ok(r.status === 200, `Konto #${id} entfernt`)
}

console.log(failed ? `\n${failed} FEHLGESCHLAGEN` : '\nalles gruen')
process.exit(failed ? 1 : 0)
