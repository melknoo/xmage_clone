// End-to-End-Test fuer den Host-Link (Tisch auf dem eigenen Rechner, fly nur als Lobby + Relay).
// Startet selbst zwei Engines aus engine/build/install (vorher: cd engine; .\gradlew.bat installDist):
//   X = "fly"  : --server --dev auf 7411 (Owner-Code DEV-OWNER-CODE), Daten engine/run/relay-fly
//   Y = "Host" : --dev auf 7412 (lokaler Modus), Daten engine/run/relay-host
// Ablauf: Owner haengt Y per POST /api/host/link an X -> Tisch REMOTE mit Passwort -> Beitritt (403/200/Einladung)
// -> Start laeuft auf Y, Spieler spielen ueber X -> Chat, Reconnect, Aufgeben -> gameOver mit Belohnung, Statistik nur
// auf X -> Revanche: Link-Reconnect (hostLink false/true), Admin-Abbruch, Link weg -> Abbruch nach Frist.
//   node scripts/e2e-relay.mjs            (RELAY_KEEP=1 laesst die Engines nach dem Test laufen)
//   RELAY_X=https://magelite.fly.dev node scripts/e2e-relay.mjs   -> X ist der echte Server (nur Y wird gestartet;
//   Owner-Code aus MAGELITE_OWNER_CODE oder engine/run/owner-code.txt; Frist 60 s statt 5 s)
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const lib = path.join(root, 'engine', 'build', 'install', 'magelite-engine', 'lib')
const vendor = path.join(root, 'vendor', 'xmage')
const PORT_X = Number(process.env.RELAY_PORT_X ?? 7411)
const PORT_Y = Number(process.env.RELAY_PORT_Y ?? 7412)
const liveX = process.env.RELAY_X?.replace(/\/+$/, '') || null
const X = liveX ?? `http://127.0.0.1:${PORT_X}`
const Y = `http://127.0.0.1:${PORT_Y}`
const codeFile = path.join(root, 'engine', 'run', 'owner-code.txt')
const ownerCode = process.env.MAGELITE_OWNER_CODE ?? (liveX ? (fs.existsSync(codeFile) ? fs.readFileSync(codeFile, 'utf8').trim() : '') : 'DEV-OWNER-CODE')
if (liveX && !ownerCode) {
  console.error('RELAY_X gesetzt, aber kein Owner-Code (MAGELITE_OWNER_CODE oder engine/run/owner-code.txt)')
  process.exit(2)
}
// Frist, bis fly ein Spiel ohne Host aufgibt: lokal per -Dmagelite.hostGraceMs=5000, live 60 s
const graceMs = liveX ? 60000 : 5000

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
async function call(base, method, apiPath, { body, cookie } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (cookie) headers.Cookie = cookie
  const r = await fetch(base + apiPath, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined })
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

// ---- Engines
const procs = []
function startEngine(name, dataDir, port, extraArgs, env, xmx) {
  fs.mkdirSync(dataDir, { recursive: true })
  // frische Nutzerdaten, Karten-DB bleibt (wird sonst aus vendor kopiert)
  for (const f of ['magelite.db', 'magelite.db-journal', 'magelite.db-wal', 'magelite.db-shm']) {
    try {
      fs.rmSync(path.join(dataDir, f))
    } catch {}
  }
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java'
  const args = [
    `-Xmx${xmx}`, '-XX:+UseG1GC', '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
    `-Dmagelite.vendor=${vendor}`, `-Dmagelite.hostGraceMs=${graceMs}`, '-Dmagelite.kickAfterMs=5000',
    '-cp', `${path.join(lib, 'magelite-engine.jar')}${path.delimiter}${path.join(lib, '*')}`,
    'dev.magelite.Main', `--data=${dataDir}`, `--port=${port}`, `--vendor=${vendor}`, '--dev', ...extraArgs,
  ]
  const p = spawn(java, args, { cwd: dataDir, env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] })
  procs.push(p)
  const log = fs.createWriteStream(path.join(dataDir, `e2e-${name}.log`))
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
function stopEngines() {
  for (const p of procs) {
    try {
      p.kill()
    } catch {}
  }
}
process.on('exit', stopEngines)

console.log(liveX ? `Starte Engine Y (Host) gegen ${X} ...` : 'Starte Engines X (fly) und Y (Host) ...')
try {
  if (!liveX) {
    await startEngine('fly', path.join(root, 'engine', 'run', 'relay-fly'), PORT_X, ['--server'],
      { MAGELITE_OWNER_CODE: ownerCode, MAGELITE_OWNER_NAME: 'Owner' }, '768m')
  }
  await startEngine('host', path.join(root, 'engine', 'run', 'relay-host'), PORT_Y, [], {}, '2g')
} catch (e) {
  console.error(`FEHLER ${e.message}`)
  stopEngines()
  process.exit(2)
}
ok(true, `Engines bereit: X=${X} Y=${Y}`)

// RELAY_ONLY_START=1: nur die beiden Engines starten und laufen lassen (Screenshots, Handtests); Strg+C beendet sie
if (process.env.RELAY_ONLY_START) {
  console.log('Engines laufen (RELAY_ONLY_START) - Strg+C zum Beenden')
  await new Promise(() => {})
}

try {
  // ---- Konten auf X
  let r = await x('POST', '/api/auth/login', { body: { code: ownerCode } })
  const owner = cookieOf(r.setCookie)
  const ownerToken = owner.split('=')[1]
  r = await x('GET', '/api/me', { cookie: owner })
  const ownerId = r.json?.user?.id
  ok(r.status === 200 && r.json?.hostLink === false, `Owner angemeldet, hostLink=${r.json?.hostLink}`)
  const baseGames = (await x('GET', '/api/health')).json?.games ?? 0
  const baseLinks = (await x('GET', '/api/admin/server', { cookie: owner })).json?.hostLinks ?? 0
  r = await x('POST', '/api/admin/invites', { cookie: owner, body: { name: 'Bob' } })
  const bobId = r.json?.id
  r = await x('POST', '/api/auth/login', { body: { code: r.json?.code } })
  const bob = cookieOf(r.setCookie)
  r = await x('POST', '/api/admin/invites', { cookie: owner, body: { name: 'Carla' } })
  const carlaId = r.json?.id
  r = await x('POST', '/api/auth/login', { body: { code: r.json?.code } })
  const carla = cookieOf(r.setCookie)
  ok(owner && bob && carla, 'Owner, Bob, Carla angemeldet')
  const samples = (await x('GET', '/api/samples', { cookie: owner })).json
  const sample = (i) => ({ type: 'sample', id: samples[i].id })

  // ---- Host-Link: Y haengt sich mit dem Owner-Cookie an X
  r = await y('GET', '/api/host/link')
  ok(r.status === 200 && r.json?.enabled === false, `Y: Link aus (${r.status})`)
  r = await y('POST', '/api/host/link', { body: { server: 'ftp://x', session: ownerToken } })
  ok(r.status === 400, `Y: ungueltige Server-URL -> ${r.status}`)
  r = await y('POST', '/api/host/link', { body: { server: X, session: ownerToken } })
  ok(r.status === 200 && r.json?.enabled === true, `Y: Link angefordert (${r.status})`)
  let linked = false
  for (let i = 0; i < 40 && !linked; i++) {
    await sleep(250)
    linked = (await y('GET', '/api/host/link')).json?.connected === true
  }
  ok(linked, 'Y: Link verbunden')
  r = await x('GET', '/api/me', { cookie: owner })
  ok(r.json?.hostLink === true, `X: Owner hostLink=${r.json?.hostLink}`)
  r = await x('GET', '/api/admin/server', { cookie: owner })
  ok(r.json?.hostLinks === baseLinks + 1, `Admin: hostLinks=${r.json?.hostLinks}`)

  // ---- Tische
  r = await x('POST', '/api/tables', { cookie: carla, body: { hosting: 'REMOTE' } })
  ok(r.status === 409, `Carla ohne Link: REMOTE-Tisch -> ${r.status}`)
  r = await x('POST', '/api/tables', { cookie: owner, body: { name: 'Relay-Runde', hosting: 'REMOTE', password: 'geheim', tempo: 'BLITZ' } })
  ok(r.status === 200 && r.json?.hosting === 'REMOTE' && r.json?.locked === true && r.json?.hostLinkOk === true,
    `Owner: REMOTE-Tisch privat -> ${r.status} (hosting=${r.json?.hosting}, locked=${r.json?.locked}, hostLinkOk=${r.json?.hostLinkOk})`)
  const tid = r.json?.id
  r = await x('POST', `/api/tables/${tid}/join`, { cookie: bob })
  ok(r.status === 403 && r.json?.needPassword === true, `Bob ohne Passwort -> ${r.status} needPassword=${r.json?.needPassword}`)
  r = await x('POST', `/api/tables/${tid}/join`, { cookie: bob, body: { password: 'falsch' } })
  ok(r.status === 403, `Bob falsches Passwort -> ${r.status}`)
  r = await x('POST', `/api/tables/${tid}/join`, { cookie: bob, body: { password: 'geheim' } })
  ok(r.status === 200 && r.json?.mySeat === 1, `Bob richtiges Passwort -> ${r.status} Platz ${r.json?.mySeat}`)
  // Einladung = Schluessel (Freundschaft noetig)
  r = await x('POST', '/api/friends', { cookie: owner, body: { userId: carlaId } })
  r = await x('POST', `/api/friends/${ownerId}/accept`, { cookie: carla })
  r = await x('POST', `/api/tables/${tid}/invite`, { cookie: owner, body: { userId: carlaId } })
  ok(r.status === 200, `Owner laedt Carla ein -> ${r.status}`)
  r = await x('POST', `/api/tables/${tid}/join`, { cookie: carla })
  ok(r.status === 200 && r.json?.mySeat === 2, `Carla per Einladung ohne Passwort -> ${r.status} Platz ${r.json?.mySeat}`)
  r = await x('GET', '/api/tables', { cookie: carla })
  const row = r.json?.find?.((t) => t.id === tid)
  ok(row?.locked === true && row?.hosting === 'REMOTE', 'Lobby zeigt Schloss + REMOTE')

  await x('PUT', `/api/tables/${tid}/seat`, { cookie: owner, body: { deck: sample(0) } })
  await x('PUT', `/api/tables/${tid}/seat`, { cookie: bob, body: { deck: sample(1) } })
  await x('PUT', `/api/tables/${tid}/seat`, { cookie: carla, body: { deck: sample(2) } })
  r = await x('PUT', `/api/tables/${tid}/seats/3`, { cookie: owner, body: { kind: 'BOT', deck: sample(3) } })
  ok(r.status === 200 && r.json?.seats?.[3]?.kind === 'BOT', 'Bot auf Platz 4')

  // ---- Start auf dem Host
  async function startGame(label) {
    const t0 = Date.now()
    const rs = await x('POST', `/api/tables/${tid}/start`, { cookie: owner })
    ok(rs.status === 200 && rs.json?.state === 'RUNNING' && rs.json?.gameId, `${label}: Start -> ${rs.status} ${rs.json?.state} (${Date.now() - t0} ms) ${rs.json?.error ?? ''}`)
    return rs.json?.gameId
  }
  const gameId = await startGame('Spiel 1')
  r = await y('GET', '/api/host/link')
  ok(r.json?.games?.length === 1 && r.json.games[0].gameId === gameId, `Y hostet ${r.json?.games?.length} Spiel`)
  r = await x('GET', '/api/health')
  ok(r.json?.games === baseGames + 1, `X health games=${r.json?.games} (Relay zaehlt)`)
  r = await x('GET', '/api/games/current', { cookie: bob })
  ok(r.status === 200 && r.json?.gameId === gameId, `X: Bobs aktuelles Spiel = Relay-Spiel`)
  r = await x('POST', '/api/games', { cookie: bob, body: { deck: sample(0), bots: [] } })
  ok(r.status === 409 && r.json?.busy === true, `X: Bob allein ueben waehrend Relay -> ${r.status}`)
  r = await y('GET', '/api/games/current')
  ok(r.status === 404, `Y: lokale App sieht kein Spiel -> ${r.status}`)
  r = await y('GET', '/api/history')
  const yHistBefore = r.json?.length ?? -1
  r = await x('GET', '/api/admin/server', { cookie: owner })
  const adminGame = r.json?.games?.find((g) => g.id === gameId)
  ok(!!adminGame?.remoteHost && r.json?.running === baseGames + 1, `Admin: Relay-Spiel mit remoteHost=${adminGame?.remoteHost}`)

  // Y-WebSocket auf das Relay-Spiel -> 4403 (kein Sitz fuer Nutzer 1)
  const yClose = await new Promise((resolve) => {
    const ws = new WebSocket(`${Y.replace(/^http/, 'ws')}/ws/game/${gameId}`)
    ws.onclose = (ev) => resolve(ev.code)
    ws.onerror = () => {}
    setTimeout(() => resolve(-1), 5000)
  })
  ok(yClose === 4403, `Y: WS auf Relay-Spiel -> Close ${yClose}`)

  // ---- Spieler-Piloten auf X
  function pilot(cookie, name, gid = gameId) {
    const st = { name, hello: null, over: null, prompts: 0, last: null, chats: [], seats: null, hostLink: [], hellos: 0 }
    const open = () => {
      const ws = new WebSocket(`${X.replace(/^http/, 'ws')}/ws/game/${gid}`, { headers: { Cookie: cookie, Origin: X } })
      st.ws = ws
      ws.onmessage = (ev) => {
        const m = JSON.parse(ev.data)
        if (m.t === 'hello') {
          st.hello = m
          st.hellos++
        }
        if (m.t === 'state') st.last = m
        if (m.t === 'gameOver') st.over = m
        if (m.t === 'chat') st.chats.push(...m.entries)
        if (m.t === 'seats') st.seats = m
        if (m.t === 'hostLink') st.hostLink.push(m)
        if (m.t !== 'prompt') return
        st.prompts++
        let ans
        if (m.kind === 'ASK') ans = { bool: !m.mulligan }
        else if (m.kind === 'SELECT' && m.mode !== 'priority') ans = { bool: true }
        else if (m.kind === 'SELECT') {
          const land = st.last?.hand?.find((c) => st.last.actions?.includes(c.id) && c.types?.includes('LAND'))
          ans = land ? { uuid: land.id } : { bool: false }
        } else if (m.kind === 'PICK_TARGET') ans = m.targets?.length && !m.chosen?.length ? { uuid: m.targets[0] } : { bool: false }
        else if (m.kind === 'CHOOSE_CHOICE') ans = { str: m.choice.keyed ? m.choice.items[0].key : m.choice.items[0].value }
        else if (m.kind === 'AMOUNT') ans = { int: m.min ?? 0 }
        else if (m.kind === 'MULTI_AMOUNT') ans = { str: m.items.map((i) => i.value).join(' ') }
        else ans = m.choices?.length ? { uuid: m.choices[0].id } : { bool: false }
        ws.send(JSON.stringify({ t: 'respond', id: m.id, ...ans }))
      }
    }
    open()
    st.reopen = open
    return st
  }
  const A = pilot(owner, 'Owner')
  const B = pilot(bob, 'Bob')
  const C = pilot(carla, 'Carla')
  await sleep(4000)
  ok(A.hello?.seats?.length === 4 && A.hello.seats.filter((s) => s.human).length === 3, `hello: ${A.hello?.seats?.length} Sitze, ${A.hello?.seats?.filter((s) => s.human).length} Menschen`)
  ok(A.hello?.host === true && B.hello?.host === false && A.hello?.myPlayerId && B.hello?.myPlayerId && A.hello.myPlayerId !== B.hello.myPlayerId, 'Gastgeber-Flag und eigene Spieler-ids')
  ok(A.hello?.gameId === gameId, 'hello.gameId = Relay-Spiel')
  const t0 = Date.now()
  while (Date.now() - t0 < 40000 && !(A.prompts > 0 && B.prompts > 0 && C.prompts > 0) && !A.over) await sleep(500)
  ok(A.prompts > 0 && B.prompts > 0 && C.prompts > 0, `Prompts ueber das Relay: Owner=${A.prompts} Bob=${B.prompts} Carla=${C.prompts} (Zug ${A.last?.turn})`)
  // Lobby zeigt den Zug des Relay-Spiels
  r = await x('GET', `/api/tables/${tid}`, { cookie: owner })
  ok(r.json?.state === 'RUNNING' && r.json?.turn >= 1 && r.json?.canSpectate === false, `Lobby: turn=${r.json?.turn}, canSpectate=${r.json?.canSpectate}`)
  // Chat
  A.ws.send(JSON.stringify({ t: 'chat', text: 'Relay-Moin' }))
  await sleep(1500)
  ok(B.chats.some((c) => c.text === 'Relay-Moin'), `Chat kommt bei Bob an (${B.chats.length} Zeilen)`)
  // Reconnect eines Spielers
  B.ws.close()
  await sleep(2500)
  ok(A.seats?.seats?.some((s) => s.playerId === B.hello?.myPlayerId && s.connected === false), 'Owner sieht Bob getrennt')
  B.reopen()
  await sleep(2500)
  ok(B.hellos === 2 && B.last, `Bob nach Reconnect: hello #${B.hellos}, State da`)

  // ---- Aufgeben -> gameOver mit Belohnung von X
  C.ws.send(JSON.stringify({ t: 'leave' }))
  await sleep(1500)
  B.ws.send(JSON.stringify({ t: 'leave' }))
  await sleep(1500)
  A.ws.send(JSON.stringify({ t: 'leave' }))
  const t1 = Date.now()
  while (Date.now() - t1 < 30000 && !(A.over && B.over && C.over)) await sleep(500)
  ok(!!A.over && !!B.over && !!C.over, `gameOver bei allen: ${!!A.over}/${!!B.over}/${!!C.over}`)
  ok(A.over?.reward && typeof A.over.reward.xpGained === 'number' && B.over?.reward && C.over?.reward,
    `Belohnung je Nutzer: Owner ${A.over?.reward?.xpGained} XP, Bob ${B.over?.reward?.xpGained}, Carla ${C.over?.reward?.xpGained}`)
  ok(!A.over?.error, `gameOver ohne Fehler (${A.over?.error ?? '-'})`)
  r = await x('GET', '/api/history', { cookie: owner })
  ok(r.json?.some?.((g) => g.id === gameId), 'X: Spiel in Owners Statistik')
  r = await x('GET', '/api/history', { cookie: bob })
  ok(r.json?.some?.((g) => g.id === gameId), 'X: Spiel in Bobs Statistik')
  r = await y('GET', '/api/history')
  ok((r.json?.length ?? -2) === yHistBefore, `Y: lokale Statistik unveraendert (${r.json?.length})`)
  let back = null
  for (let i = 0; i < 10 && !back; i++) {
    await sleep(1000)
    r = await x('GET', `/api/tables/${tid}`, { cookie: owner })
    if (r.json?.state === 'LOBBY') back = r.json
  }
  ok(back && back.lastGameId === gameId && !back.gameId, 'Tisch wieder in der Lobby')
  r = await y('GET', '/api/host/link')
  ok(r.json?.games?.length === 0, `Y hostet nichts mehr (${r.json?.games?.length})`)
  r = await x('GET', '/api/health')
  ok(r.json?.games === baseGames, `X health games=${r.json?.games}`)
  A.ws.close()
  B.ws.close()
  C.ws.close()

  // ---- Spiel 2: Link-Reconnect waehrend des Spiels
  const game2 = await startGame('Spiel 2')
  const A2 = pilot(owner, 'Owner', game2)
  const B2 = pilot(bob, 'Bob', game2)
  const C2 = pilot(carla, 'Carla', game2)
  await sleep(4000)
  ok(A2.hello?.gameId === game2, 'Spiel 2: hello')
  const promptsBefore = A2.prompts
  r = await y('POST', '/api/host/link/reconnect')
  await sleep(5000)
  ok(A2.hostLink.some((m) => m.ok === false) && A2.hostLink.some((m) => m.ok === true), `hostLink-Meldungen: ${JSON.stringify(A2.hostLink.map((m) => m.ok))}`)
  r = await y('GET', '/api/host/link')
  ok(r.json?.connected === true && r.json?.games?.length === 1, 'Y wieder verbunden, Spiel laeuft weiter')
  const t2 = Date.now()
  while (Date.now() - t2 < 20000 && A2.prompts <= promptsBefore && !A2.over) await sleep(500)
  ok(A2.prompts > promptsBefore && !A2.over, `nach Reconnect weitere Prompts (${promptsBefore} -> ${A2.prompts})`)
  // Admin beendet das Relay-Spiel
  r = await x('POST', `/api/admin/games/${game2}/abort`, { cookie: owner })
  ok(r.status === 200, `Admin beendet Relay-Spiel -> ${r.status}`)
  const t3 = Date.now()
  while (Date.now() - t3 < 20000 && !(A2.over && B2.over)) await sleep(500)
  ok(!!A2.over && !!B2.over, 'Spiel 2: gameOver nach Admin-Abbruch')
  await sleep(1500)
  r = await x('GET', `/api/tables/${tid}`, { cookie: owner })
  ok(r.json?.state === 'LOBBY', `Tisch nach Admin-Abbruch: ${r.json?.state}`)
  A2.ws.close()
  B2.ws.close()
  C2.ws.close()

  // ---- Spiel 3: Link weg -> Abbruch nach Frist, keine Statistik
  const game3 = await startGame('Spiel 3')
  const A3 = pilot(owner, 'Owner', game3)
  await sleep(3000)
  r = await y('DELETE', '/api/host/link')
  ok(r.status === 200, `Y: Link getrennt -> ${r.status}`)
  await sleep(1500)
  ok(A3.hostLink.some((m) => m.ok === false) && !A3.over, 'Owner sieht hostLink=false, Spiel noch offen')
  r = await x('GET', '/api/me', { cookie: owner })
  ok(r.json?.hostLink === false, 'X: Owner hostLink=false')
  const t4 = Date.now()
  while (Date.now() - t4 < graceMs + 8000 && !A3.over) await sleep(500)
  ok(!!A3.over && !!A3.over.error, `Spiel 3 nach Frist abgebrochen: error=${A3.over?.error}`)
  r = await x('GET', '/api/history', { cookie: owner })
  ok(!r.json?.some?.((g) => g.id === game3), 'X: abgebrochenes Spiel nicht in der Statistik')
  await sleep(1000)
  r = await x('GET', `/api/tables/${tid}`, { cookie: owner })
  ok(r.json?.state === 'LOBBY', `Tisch nach Host-Ausfall: ${r.json?.state}`)
  r = await x('POST', `/api/tables/${tid}/start`, { cookie: owner })
  ok(r.status === 409, `Start ohne Link -> ${r.status} (${r.json?.error})`)
  A3.ws.close()

  // ---- Negativ: /ws/host ohne Cookie (lokal --dev: 4401; live ohne Origin: 4403 oder gar kein Upgrade)
  const hostClose = await new Promise((resolve) => {
    const ws = new WebSocket(`${X.replace(/^http/, 'ws')}/ws/host`)
    ws.onopen = () => setTimeout(() => resolve(ws.readyState === WebSocket.OPEN ? 'offen' : 'zu'), 3000)
    ws.onclose = (ev) => resolve(ev.code)
    ws.onerror = () => {}
    setTimeout(() => resolve('timeout'), 10000)
  })
  ok(hostClose !== 'offen', `/ws/host ohne Cookie -> ${hostClose}`)

  // Aufraeumen
  await x('POST', `/api/tables/${tid}/leave`, { cookie: owner })
  await x('DELETE', `/api/admin/invites/${bobId}`, { cookie: owner })
  await x('DELETE', `/api/admin/invites/${carlaId}`, { cookie: owner })
} catch (e) {
  console.error(`FEHLER ${e.stack || e}`)
  failed++
}

console.log(failed === 0 ? '\n=== alles gruen ===' : `\n=== ${failed} Pruefungen fehlgeschlagen ===`)
if (!process.env.RELAY_KEEP) stopEngines()
process.exit(failed === 0 ? 0 : 1)
