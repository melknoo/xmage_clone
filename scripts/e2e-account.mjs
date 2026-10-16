// End-to-End-Test "Ein Konto ueberall" (Desktop-App mit Online-Konto, Engine-Flag --account).
// Startet selbst zwei Engines aus engine/build/install (vorher: cd engine; .\gradlew.bat installDist):
//   X = "fly"     : --server --dev auf 7431 (Owner-Code DEV-OWNER-CODE), Daten engine/run/account-fly
//   Y = "Desktop" : --dev --account auf 7432 (lokaler Modus wie in der App), Daten engine/run/account-host
// Ablauf: Zustaende (CHECKING/NONE/EXPIRED/OFFLINE/OK) -> Kontodaten durchreichen (alle Nutzerdaten-Routen = X) ->
// Spiel mit Konto-Deck (gameOver pending -> Belohnung von X, nichts lokal) -> X weg: Bericht wartet, nach Neustart
// genau einmal gebucht -> Report-Route gegen Missbrauch -> lokale Decks uebernehmen -> abgelaufen -> offline = lokale
// Daten -> Budget aufgebraucht (LIMITED) -> Host-Link ruht nach dem Leerlauf-Exit (4504).
//   node scripts/e2e-account.mjs     (ACCOUNT_SKIP_IDLE=1 spart ~2,5 min; ACCOUNT_ONLY_START=1 startet nur die Engines)
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const lib = path.join(root, 'engine', 'build', 'install', 'magelite-engine', 'lib')
const forge = path.join(root, 'vendor', 'forge')
const PORT_X = Number(process.env.ACCOUNT_PORT_X ?? 7431)
const PORT_Y = Number(process.env.ACCOUNT_PORT_Y ?? 7432)
const X = `http://127.0.0.1:${PORT_X}`
const Y = `http://127.0.0.1:${PORT_Y}`
const ownerCode = 'DEV-OWNER-CODE'
const dataX = path.join(root, 'engine', 'run', 'account-fly')
const dataY = path.join(root, 'engine', 'run', 'account-host')
const ENGINE = { 'X-MageLite-Engine': 'forge/1' }

if (!fs.existsSync(path.join(lib, 'magelite-engine.jar'))) {
  console.error(`Engine fehlt: ${lib} - erst "cd engine; .\\gradlew.bat installDist"`)
  process.exit(2)
}

let failed = 0
const ok = (cond, what) => {
  console.log(`${cond ? 'OK  ' : 'FAIL'} ${what}`)
  if (!cond) failed++
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
async function call(base, method, apiPath, { body, cookie, headers: extra } = {}) {
  const headers = { ...(extra ?? {}) }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (cookie) headers.Cookie = cookie
  let r
  try {
    r = await fetch(base + apiPath, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined })
  } catch (e) {
    return { status: 0, json: null, error: e.message }
  }
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
const x = (m, p, o) => call(X, m, p, o)
const y = (m, p, o) => call(Y, m, p, o)
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)

// ---- Engines
const procs = {}
function startEngine(name, dataDir, port, extraArgs, env, xmx, fresh = true) {
  fs.mkdirSync(dataDir, { recursive: true })
  if (fresh) {
    for (const f of ['magelite.db', 'magelite.db-journal', 'magelite.db-wal', 'magelite.db-shm']) {
      try {
        fs.rmSync(path.join(dataDir, f))
      } catch {}
    }
  }
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java'
  const args = [
    `-Xmx${xmx}`, '-XX:+UseG1GC', '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
    `-Dmagelite.forge=${forge}`, '-Dmagelite.kickAfterMs=5000',
    '-cp', `${path.join(lib, 'magelite-engine.jar')}${path.delimiter}${path.join(lib, '*')}`,
    'dev.magelite.Main', `--data=${dataDir}`, `--port=${port}`, `--forge=${forge}`, '--dev', ...extraArgs,
  ]
  const p = spawn(java, args, { cwd: dataDir, env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] })
  procs[name] = p
  p.exited = new Promise((r) => p.on('exit', (code) => r(code)))
  const log = fs.createWriteStream(path.join(dataDir, `e2e-${name}.log`), { flags: 'a' })
  p.stderr.pipe(log)
  return new Promise((resolve, reject) => {
    let buf = ''
    p.stdout.on('data', (d) => {
      const s = d.toString()
      log.write(s)
      buf += s
      const m = buf.match(/MAGELITE_READY (\{.*\})/)
      if (m) resolve(JSON.parse(m[1]))
    })
    p.on('exit', (code) => reject(new Error(`${name} beendet (Exit ${code})`)))
    setTimeout(() => reject(new Error(`${name}: kein MAGELITE_READY nach 180 s`)), 180_000)
  })
}
async function stopEngine(name) {
  const p = procs[name]
  if (!p) return
  try {
    p.kill()
  } catch {}
  await Promise.race([p.exited, sleep(10_000)])
  delete procs[name]
}
function stopAll() {
  for (const p of Object.values(procs)) {
    try {
      p.kill()
    } catch {}
  }
}
process.on('exit', stopAll)

// X liefert auch die Server-UI aus (ui/dist), damit die echte App "Zur Lobby" gegen X testen kann
const uiDist = path.join(root, 'ui', 'dist')
const startX = (extra = [], fresh = true) =>
  startEngine('fly', dataX, PORT_X, ['--server', ...(fs.existsSync(uiDist) ? [`--ui=${uiDist}`] : []), ...extra],
    { MAGELITE_OWNER_CODE: ownerCode, MAGELITE_OWNER_NAME: 'Owner' }, '768m', fresh)

console.log('Starte Engines X (fly) und Y (Desktop mit --account) ...')
try {
  await startX()
  await startEngine('desktop', dataY, PORT_Y, ['--account'], {}, '2g')
} catch (e) {
  console.error(`FEHLER ${e.message}`)
  stopAll()
  process.exit(2)
}
ok(true, `Engines bereit: X=${X} Y=${Y}`)
if (process.env.ACCOUNT_ONLY_START) {
  console.log('Engines laufen (ACCOUNT_ONLY_START) - Strg+C zum Beenden')
  await new Promise(() => {})
}

/** Y: warten, bis die Konto-Pruefung fertig ist */
async function settle(ms = 30_000) {
  const t0 = Date.now()
  let st = null
  while (Date.now() - t0 < ms) {
    st = (await y('GET', '/api/account')).json
    if (st && st.state !== 'CHECKING') return st
    await sleep(200)
  }
  return st
}

/** Spieler-Pilot auf Y (lokale Engine, Dev ohne Token): antwortet minimal, sammelt gameOver */
function pilot(gameId) {
  const st = { prompts: 0, overs: [], last: null }
  const ws = new WebSocket(`${Y.replace(/^http/, 'ws')}/ws/game/${gameId}`)
  st.ws = ws
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data)
    if (m.t === 'state') st.last = m
    if (m.t === 'gameOver') st.overs.push(m)
    if (m.t !== 'prompt') return
    st.prompts++
    let ans
    if (m.kind === 'ASK') ans = { bool: !m.mulligan }
    else if (m.kind === 'SELECT') ans = { bool: m.mode !== 'priority' }
    else if (m.kind === 'PICK_TARGET') ans = m.targets?.length && !m.chosen?.length ? { uuid: m.targets[0] } : { bool: false }
    else if (m.kind === 'CHOOSE_CHOICE') ans = { str: m.choice.keyed ? m.choice.items[0].key : m.choice.items[0].value }
    else if (m.kind === 'AMOUNT') ans = { int: m.min ?? 0 }
    else if (m.kind === 'MULTI_AMOUNT') ans = { str: m.items.map((i) => i.value).join(' ') }
    else ans = m.choices?.length ? { uuid: m.choices[0].id } : { bool: false }
    ws.send(JSON.stringify({ t: 'respond', id: m.id, ...ans }))
  }
  ws.onerror = () => {}
  return st
}

/** Spiel auf Y starten, ein paar Prompts spielen, aufgeben; liefert {gameId, overs} */
async function playAndConcede(deck, waitFor = (overs) => overs.some((o) => o.reward)) {
  const r = await y('POST', '/api/games', { body: { deck, bots: [{ type: 'random' }, { type: 'random' }, { type: 'random' }], tempo: 'BLITZ' } })
  if (r.status !== 200) return { status: r.status, json: r.json, overs: [] }
  const gameId = r.json.gameId
  const P = pilot(gameId)
  const t0 = Date.now()
  while (Date.now() - t0 < 30_000 && P.prompts < 3 && !P.overs.length) await sleep(300)
  // ein paar eigene Zuege, damit Statistik/XP nicht wegen "frueh aufgegeben" leer bleiben
  const t1 = Date.now()
  while (Date.now() - t1 < 60_000 && (P.last?.turn ?? 0) < 9 && !P.overs.length) await sleep(500)
  P.ws.send(JSON.stringify({ t: 'leave' }))
  const t2 = Date.now()
  while (Date.now() - t2 < 60_000 && !waitFor(P.overs)) await sleep(300)
  P.ws.close()
  return { status: 200, gameId, overs: P.overs, turn: P.last?.turn }
}

const sampleText = (rel) => fs.readFileSync(path.join(root, 'engine', 'src', 'main', 'resources', 'sample-decks', rel), 'utf8')
const deckA = sampleText('Commander 2014/Built from Scratch.dck')
const deckB = sampleText('Commander 2014/Forged in Stone.dck')

try {
  // ---- Konten auf X
  let r = await x('POST', '/api/auth/login', { body: { code: ownerCode } })
  const owner = cookieOf(r.setCookie)
  r = await x('POST', '/api/admin/invites', { cookie: owner, body: { name: 'Dana' } })
  const danaId = r.json?.id
  const danaCode = r.json?.code
  r = await x('POST', '/api/auth/login', { body: { code: danaCode } })
  let dana = cookieOf(r.setCookie)
  let danaToken = dana?.split('=')[1]
  ok(owner && dana && danaId, 'Owner und Dana auf X angemeldet')

  // ---- 1. Zustaende
  r = await y('GET', '/api/me')
  ok(r.status === 200 && r.json?.mode === 'local' && ['CHECKING', 'NONE'].includes(r.json?.account?.state), `Y /api/me: mode=${r.json?.mode}, account=${r.json?.account?.state} (vor dem Sync)`)
  r = await y('POST', '/api/account/sync', { body: { server: X, session: null } })
  ok(r.json?.state === 'NONE', `Sync ohne Session -> ${r.json?.state}`)
  r = await y('GET', '/api/decks')
  ok(r.status === 409 && r.json?.account === 'NONE', `Y Decks ohne Anmeldung -> ${r.status} ${r.json?.account}`)
  r = await y('POST', '/api/games', { body: { deck: { type: 'random' } } })
  ok(r.status === 409 && r.json?.account === 'NONE', `Y Spielstart ohne Anmeldung -> ${r.status}`)
  r = await y('POST', '/api/account/sync', { body: { server: 'ftp://x', session: null } })
  ok(r.status === 400, `Sync mit ungueltiger Server-URL -> ${r.status}`)
  await y('POST', '/api/account/sync', { body: { server: X, session: 'abgelaufen-123' } })
  let st = await settle()
  ok(st?.state === 'EXPIRED', `Sync mit ungueltiger Session -> ${st?.state}`)

  // offline: lokale Daten; ein lokales Deck fuer den spaeteren Import anlegen
  r = await y('POST', '/api/account/sync', { body: { server: X, session: null, offline: true } })
  ok(r.json?.state === 'OFFLINE', `Offline -> ${r.json?.state}`)
  r = await y('POST', '/api/decks', { body: { text: deckA, folder: 'Lokal' } })
  const localDeckId = r.json?.id
  ok(r.status === 200 && localDeckId, `Offline: lokales Deck gespeichert (${r.status})`)
  r = await y('GET', '/api/decks')
  ok(r.json?.length === 1, `Offline: Y zeigt ${r.json?.length} lokales Deck`)

  await y('POST', '/api/account/sync', { body: { server: X, session: danaToken } })
  st = await settle()
  ok(st?.state === 'OK' && st.user?.id === danaId && st.user?.name === 'Dana', `Angemeldet: ${st?.state} als ${st?.user?.name} (${st?.user?.id})`)
  r = await y('GET', '/api/me')
  ok(r.json?.account?.state === 'OK' && r.json?.account?.user?.name === 'Dana', `/api/me.account = ${r.json?.account?.state} ${r.json?.account?.user?.name}`)

  // ---- 2. Kontodaten durchreichen
  r = await y('GET', '/api/decks')
  ok(r.status === 200 && Array.isArray(r.json) && r.json.length === 0, `Y Decks = Kontodecks (${r.json?.length}, lokal waere 1)`)
  r = await y('POST', '/api/decks', { body: { text: deckB, folder: 'Test' } })
  const deckId = r.json?.id
  ok(r.status === 200 && deckId, `Y speichert Deck im Konto (${r.status}, id ${deckId})`)
  const xd = (await x('GET', '/api/decks', { cookie: dana })).json
  ok(xd?.length === 1 && xd[0].id === deckId && xd[0].folder === 'Test', 'X: Deck liegt in Danas Konto')
  for (const p of [`/api/decks/${deckId}`, `/api/decks/${deckId}/text`, '/api/decks', '/api/profile']) {
    const a = await y('GET', p)
    const b = await x('GET', p, { cookie: dana })
    ok(a.status === 200 && same(a.json, b.json), `GET ${p}: Y = X`)
  }
  r = await y('POST', `/api/decks/${deckId}/meta`, { body: { bracket: 3 } })
  ok(r.status === 200 && r.json?.bracket === 3, `meta (bracket 3) -> ${r.status}`)
  r = await y('POST', '/api/decks/order', { body: { folder: 'Test', ids: [deckId] } })
  ok(r.status === 200, `order -> ${r.status}`)
  r = await y('POST', '/api/decks/folders/rename', { body: { from: 'Test', to: 'Neu' } })
  ok(r.status === 200 && r.json?.decks === 1, `Ordner umbenennen -> ${r.status}`)
  r = await x('GET', `/api/decks/${deckId}`, { cookie: dana })
  ok(r.json?.deck?.folder === 'Neu' && r.json?.deck?.bracket === 3, `X: Ordner=${r.json?.deck?.folder}, Bracket=${r.json?.deck?.bracket}`)
  r = await y('PUT', '/api/profile', { body: { name: 'Dana Heldin' } })
  ok(r.status === 200 && r.json?.name === 'Dana Heldin', `Held umbenennen -> ${r.json?.name}`)
  r = await x('GET', '/api/profile', { cookie: dana })
  ok(r.json?.name === 'Dana Heldin', 'X: Heldenname geaendert')
  r = await y('POST', '/api/decks/parse', { body: { text: deckA } })
  ok(r.status === 200 && r.json?.commanders?.length === 1, `Vorschau bleibt lokal (${r.status})`)

  // ---- 3. Spiel mit Konto-Deck
  const g1 = await playAndConcede({ type: 'user', id: deckId })
  ok(g1.status === 200 && g1.gameId, `Spiel mit Konto-Deck gestartet (${g1.status} ${g1.json?.error ?? ''})`)
  ok(g1.overs[0]?.rewardStatus === 'pending' && !g1.overs[0]?.reward, `1. gameOver: rewardStatus=${g1.overs[0]?.rewardStatus}`)
  const final1 = g1.overs.find((o) => o.reward)
  ok(final1 && typeof final1.reward.xpGained === 'number' && !final1.rewardStatus, `2. gameOver mit Belohnung: ${final1?.reward?.xpGained} XP (Zug ${g1.turn})`)
  ok(final1?.reward?.deckId === deckId, `Meisterschaft aufs Konto-Deck (deckId ${final1?.reward?.deckId})`)
  r = await x('GET', '/api/history', { cookie: dana })
  const h1 = r.json?.find?.((g) => g.id === g1.gameId)
  ok(h1 && h1.deckId === deckId, `X: Spiel in Danas Verlauf (deckId ${h1?.deckId})`)
  r = await y('GET', '/api/history')
  ok(r.json?.some?.((g) => g.id === g1.gameId), 'Y zeigt den Verlauf des Kontos')
  r = await y('GET', '/api/stats/overview')
  const xo = await x('GET', '/api/stats/overview', { cookie: dana })
  ok(r.status === 200 && same(r.json, xo.json) && r.json?.totals?.games === 1, `stats/overview: Y = X (${r.json?.totals?.games} Spiel)`)
  for (const p of ['/api/stats/decks', `/api/stats/decks/${deckId}/cards`, '/api/history?limit=5']) {
    const a = await y('GET', p)
    const b = await x('GET', p, { cookie: dana })
    ok(a.status === 200 && same(a.json, b.json), `GET ${p}: Y = X`)
  }

  // ---- 4. X weg: Bericht wartet, nach dem Neustart genau einmal gebucht
  await stopEngine('fly')
  const g2 = await playAndConcede({ type: 'sample', id: (await y('GET', '/api/samples')).json[0].id }, (overs) => overs.some((o) => o.rewardStatus === 'queued' || o.reward))
  ok(g2.status === 200, `Spiel bei weggefallenem Server gestartet (${g2.status})`)
  ok(g2.overs.some((o) => o.rewardStatus === 'queued'), `gameOver: queued (${g2.overs.map((o) => o.rewardStatus ?? (o.reward ? 'reward' : '-')).join(', ')})`)
  r = await y('GET', '/api/account')
  ok(r.json?.queued === 1, `Y: ${r.json?.queued} Bericht wartet`)
  r = await y('GET', '/api/decks')
  ok(r.status === 503 && r.json?.account === 'UNREACHABLE', `Y Decks bei weggefallenem Server -> ${r.status} ${r.json?.account} (keine lokalen Daten)`)
  await startX([], false)
  await y('POST', '/api/account/retry')
  st = await settle()
  ok(st?.state === 'OK', `Nach X-Neustart wieder angemeldet (${st?.state})`)
  let q = st?.queued
  for (let i = 0; i < 40 && q !== 0; i++) {
    await sleep(250)
    q = (await y('GET', '/api/account')).json?.queued
  }
  ok(q === 0, `Warteschlange abgearbeitet (queued=${q})`)
  r = await x('GET', '/api/history', { cookie: dana })
  ok(r.json?.filter?.((g) => g.id === g2.gameId).length === 1, 'X: nachgereichtes Spiel genau einmal im Verlauf')

  // ---- 5. Report-Route gegen Missbrauch (direkt an X)
  const validResult = { gameId: crypto.randomUUID(), tempo: 'NORMAL', winnerId: null, result: 'draw', turns: 3, durationMs: 1000,
    placements: [{ playerId: '00000000-0000-0000-0000-000000000001', name: 'Du', place: 1, human: true, life: 40, mulligans: 0 }],
    seats: [{ userId: 1, name: 'Du', playerId: '00000000-0000-0000-0000-000000000001', deckId: null, deckName: 'D', commanders: [], conceded: false, humanTurns: 2, cards: {} }] }
  r = await x('POST', '/api/games/report', { cookie: dana, body: { result: validResult } })
  ok(r.status === 426, `Report ohne Engine-Kennung -> ${r.status}`)
  r = await x('POST', '/api/games/report', { cookie: dana, headers: ENGINE, body: { result: { ...validResult, seats: [validResult.seats[0], validResult.seats[0]] } } })
  ok(r.status === 400, `Report mit 2 Sitzen -> ${r.status}`)
  r = await x('POST', '/api/games/report', { headers: ENGINE, body: { result: validResult } })
  ok(r.status === 401, `Report ohne Anmeldung -> ${r.status}`)
  const ownerDeck = (await x('POST', '/api/decks', { cookie: owner, body: { text: deckA } })).json?.id
  const foreign = { ...validResult, gameId: crypto.randomUUID(), seats: [{ ...validResult.seats[0], deckId: ownerDeck, userId: 1 }] }
  r = await x('POST', '/api/games/report', { cookie: dana, headers: ENGINE, body: { result: foreign } })
  ok(r.status === 200 && r.json?.reward && r.json.reward.deckId == null, `Report mit fremdem Deck -> ${r.status}, deckId=${r.json?.reward?.deckId}`)
  r = await x('GET', '/api/history', { cookie: owner })
  ok(!r.json?.some?.((g) => g.id === foreign.gameId), 'Fremdes Spiel nicht beim Owner gebucht')
  r = await x('POST', '/api/games/report', { cookie: dana, headers: ENGINE, body: { result: foreign } })
  ok(r.status === 200 && r.json?.duplicate === true, `Report doppelt -> duplicate=${r.json?.duplicate}`)
  let limited = 0
  for (let i = 0; i < 32; i++) {
    const rr = await x('POST', '/api/games/report', { cookie: dana, headers: ENGINE, body: { result: { ...validResult, gameId: 'kaputt' } } })
    if (rr.status === 429) limited++
  }
  ok(limited > 0, `Rate-Limit: ${limited}x 429`)

  // ---- 6. Lokale Decks uebernehmen
  r = await y('GET', '/api/account/local-decks')
  ok(r.json?.decks?.length === 1 && r.json.decks[0].id === localDeckId && r.json.dismissed === false, `Lokale Decks offen: ${r.json?.decks?.length}`)
  r = await y('POST', '/api/account/import-local', { body: {} })
  ok(r.status === 200 && r.json?.imported === 1 && r.json?.decks?.length === 0, `Uebernommen: ${r.json?.imported}, offen ${r.json?.decks?.length}`)
  r = await x('GET', '/api/decks', { cookie: dana })
  const imported = r.json?.find?.((d) => d.folder === 'Lokal')
  ok(r.json?.length === 2 && imported, `X: ${r.json?.length} Decks, uebernommenes im Ordner "Lokal"`)
  r = await y('POST', '/api/account/import-local', { body: {} })
  ok(r.json?.imported === 0, 'Zweites Uebernehmen legt nichts doppelt an')
  r = await y('POST', '/api/account/import-local/dismiss', { body: {} })
  ok(r.json?.dismissed === true, 'Hinweis ausgeblendet')

  // ---- 7. Abgemeldet (Session auf X beendet) -> EXPIRED
  await x('POST', '/api/auth/logout', { cookie: dana })
  r = await y('GET', '/api/decks')
  ok(r.status === 401 && r.json?.account === 'EXPIRED', `Y nach Abmelden auf X -> ${r.status} ${r.json?.account}`)
  r = await y('GET', '/api/account')
  ok(r.json?.state === 'EXPIRED', `Y Konto -> ${r.json?.state}`)
  r = await y('POST', '/api/games', { body: { deck: { type: 'random' } } })
  ok(r.status === 409, `Spielstart bei abgelaufener Anmeldung -> ${r.status}`)

  // ---- 8. Offline: lokale Daten, lokale Statistik unberuehrt
  await y('POST', '/api/account/offline')
  r = await y('GET', '/api/decks')
  ok(r.status === 200 && r.json?.length === 1 && r.json[0].id === localDeckId, `Offline: lokale Decks (${r.json?.length})`)
  r = await y('GET', '/api/history')
  ok(r.status === 200 && r.json?.length === 0, `Offline: lokale Statistik leer (${r.json?.length}) - Konto-Spiele nie lokal`)

  // ---- 9. Budget aufgebraucht (oeffentliches Konto) -> LIMITED
  r = await x('POST', '/api/auth/login', { body: { code: danaCode } })
  dana = cookieOf(r.setCookie)
  danaToken = dana?.split('=')[1]
  r = await x('POST', `/api/admin/users/${danaId}/tier`, { cookie: owner, body: { tier: 'public' } })
  ok(r.status === 200, `Dana -> public (${r.status})`)
  await stopEngine('fly')
  await startX(['--budget-min=0', '--idle-exit-min=1'], false)
  await y('POST', '/api/account/sync', { body: { server: X, session: danaToken } })
  st = await settle()
  ok(st?.state === 'LIMITED', `Oeffentliches Konto ohne Budget -> ${st?.state}`)
  r = await y('GET', '/api/decks')
  ok(r.status === 409 && r.json?.account === 'LIMITED', `Y Decks -> ${r.status} ${r.json?.account}`)

  // ---- 10. Host-Link ruht nach dem Leerlauf-Exit (4504) statt fly neu zu wecken
  if (!process.env.ACCOUNT_SKIP_IDLE) {
    const ownerAgain = cookieOf((await x('POST', '/api/auth/login', { body: { code: ownerCode } })).setCookie)
    await x('POST', `/api/admin/users/${danaId}/tier`, { cookie: ownerAgain, body: { tier: 'friend' } })
    await y('POST', '/api/account/retry')
    st = await settle()
    ok(st?.state === 'OK', `Wieder Freund -> ${st?.state}`)
    await y('POST', '/api/account/wake')
    let linked = false
    for (let i = 0; i < 40 && !linked; i++) {
      await sleep(250)
      linked = (await y('GET', '/api/host/link')).json?.connected === true
    }
    ok(linked, 'Host-Link nach wake verbunden')
    console.log('     warte auf den Leerlauf-Exit von X (bis 3 min) ...')
    const code = await Promise.race([procs.fly.exited, sleep(200_000).then(() => 'timeout')])
    ok(code !== 'timeout', `X im Leerlauf beendet (Exit ${code})`)
    delete procs.fly
    await sleep(5000)
    r = await y('GET', '/api/host/link')
    ok(r.json?.enabled === false && r.json?.connected === false, `Host-Link ruht (enabled=${r.json?.enabled}, connected=${r.json?.connected}, error=${r.json?.error ?? '-'})`)
  }
} catch (e) {
  console.error(e)
  failed++
} finally {
  stopAll()
}
console.log(failed ? `\n${failed} FEHLER` : '\nalles gruen')
process.exit(failed ? 1 : 0)
