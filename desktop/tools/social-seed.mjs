// Testdaten fuer die Online-Screenshots (Server-Modus, isolierte Engine 7401): Konten, Freunde, Anfragen, Lobby-Chat,
// Tische, ein laufendes Spiel mit passiven Piloten (fuer "Läuft · Zug N" und Zuschauen) - wie im Online-Prototyp.
// Die App selbst meldet sich als Owner "Anna" an (Engine mit MAGELITE_OWNER_NAME=Anna starten); das Skript nutzt eine
// eigene Anna-Sitzung nur fuer Tisch/Freunde-Aktionen in ihrem Namen.
//
// Aufruf:  MAGELITE_URL=http://127.0.0.1:7401 node desktop/tools/social-seed.mjs [--phase=home] [--duration=60]
//          [--control=7499] [--no-running] [--no-cleanup] [--purge] [--pace=6500]
//   --phase     home | table | invite | running | kick | sysline (Startphase; spaeter per Steuerung umschaltbar)
//               home/table/running: Grundzustand (Annas Tisch mit Bob, Freitagsrunde, Ilias' Tisch laeuft)
//               invite: + Clara laedt Anna an die Freitagsrunde ein
//               kick:   Anna sitzt (statt an ihrem Tisch) bei Clara; POST /do/kick entfernt sie (Opfer-Sicht)
//               sysline: + "Fritz ist dem Lobby-Chat beigetreten"
//   --duration  Minuten bis zum Aufraeumen (Standard 60)
//   --control   Port der Steuerung auf 127.0.0.1 (0 = aus). CORS offen, damit Steps per fetch() steuern koennen:
//                 GET  /status                 Zustand (ohne Codes/Cookies)
//                 POST /phase/<name>           Phase anwenden (s. o.)
//                 POST /do/invite              Clara laedt Anna ein
//                 POST /do/kick                Clara entfernt Anna von der Freitagsrunde (nach Phase kick)
//                 POST /do/anna-table          Annas Tisch (wieder) eroeffnen, Bob setzt sich (ohne Deck)
//                 POST /do/ready               Bob sitzt an Annas Tisch (ggf. neu eingeladen) und waehlt ein Deck
//                 POST /do/sysline?name=Fritz  Systemzeile "X ist dem Lobby-Chat beigetreten"
//                 POST /do/chat?name=Bob&text=...   Lobby-Chat-Zeile
//                 POST /do/crowd?n=8           n Zuschauer an Ilias' Tisch (fuer "zu viele Zuschauer")
//                 POST /quit?cleanup=1         beenden (Tische schliessen)
// Sicherheit: bricht ohne MAGELITE_URL sowie bei Port 7317 / *.fly.dev ab. Gibt nie Codes oder Cookies aus.
// Logins sind auf 10/min begrenzt (zaehlt auch erfolgreiche) -> Abstand >= 6,5 s, bei 429 eine Minute warten.
import http from 'node:http'

const base = process.env.MAGELITE_URL
if (!base) {
  console.error('MAGELITE_URL fehlt (z. B. http://127.0.0.1:7401) - kein Default')
  process.exit(2)
}
if (/:7317\b|\.fly\.dev/i.test(base)) {
  console.error('Gesperrt: social-seed laeuft nie gegen 7317 oder *.fly.dev')
  process.exit(2)
}
const arg = (name, def) => {
  const a = process.argv.find((x) => x === `--${name}` || x.startsWith(`--${name}=`))
  if (!a) return def
  return a.includes('=') ? a.slice(a.indexOf('=') + 1) : true
}
const ownerCode = process.env.MAGELITE_OWNER_CODE ?? 'DEV-OWNER-CODE'
const startPhase = String(arg('phase', 'home'))
const durationMin = Number(arg('duration', 60))
const controlPort = Number(arg('control', 7499))
const withRunning = !arg('no-running', false)
const cleanupOnExit = !arg('no-cleanup', false)
const purge = !!arg('purge', false)
const pace = Number(arg('pace', 6500))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a)

// ------------------------------------------------------------------ HTTP
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
const errText = (r) => (r.json && r.json.error) || r.status

let lastLogin = 0
async function login(code, label) {
  for (let attempt = 0; attempt < 4; attempt++) {
    const wait = lastLogin + pace - Date.now()
    if (wait > 0) await sleep(wait)
    lastLogin = Date.now()
    const r = await call('POST', '/api/auth/login', { body: { code } })
    if (r.status === 429) {
      log(`Login ${label}: Rate-Limit, warte 61 s`)
      await sleep(61000)
      continue
    }
    if (r.status !== 200) throw new Error(`Login ${label} fehlgeschlagen (${r.status})`)
    return { cookie: cookieOf(r.setCookie), id: r.json.user.id, name: r.json.user.name }
  }
  throw new Error(`Login ${label}: Rate-Limit bleibt`)
}

// ------------------------------------------------------------------ Zustand
const NAMES = ['Bob', 'Clara', 'David', 'Emil', 'Fritz', 'Greta', 'Hanna', 'Ilias']
const POLLERS = ['Bob', 'Clara', 'Fritz', 'Hanna', 'Ilias'] // online im Lobby-Chat; David spielt, Emil/Greta offline
const users = {} // name -> {cookie, id, name}
let anna = null // eigene Anna-Sitzung des Skripts
let samples = []
const tables = { anna: null, clara: null, ilias: null }
const pilots = new Map() // `${name}:${gameId}` -> {ws, chatted}
const crowd = [] // Zuschauer-Sockets
let phase = startPhase
let stopping = false

const deckPref = {
  Anna: /Vampiric Bloodline|Heavenly Inferno/i,
  Bob: /Elven Empire/i,
  Clara: /Heavenly Inferno|Sworn to Darkness/i,
  Hanna: /Breed Lethality|Plunder the Graves/i,
  Ilias: /Buckle Up/i,
  David: /Sworn to Darkness/i,
  Bot: /Peer Through Time/i,
}
function deckFor(who) {
  const re = deckPref[who]
  const s = (re && samples.find((x) => re.test(x.name))) || samples[Math.abs(hash(who)) % Math.max(1, samples.length)]
  return s ? { type: 'sample', id: s.id } : { type: 'random' }
}
function hash(s) {
  let h = 0
  for (const c of s) h = (h * 31 + c.charCodeAt(0)) | 0
  return h
}

// ------------------------------------------------------------------ Aufbau
async function accounts() {
  anna = await login(ownerCode, 'Owner')
  if (anna.name !== 'Anna') log(`Hinweis: Owner heisst "${anna.name}" (Engine mit MAGELITE_OWNER_NAME=Anna und leerem Datenverzeichnis starten)`)
  const list = (await call('GET', '/api/admin/invites', { cookie: anna.cookie })).json ?? []
  for (const n of NAMES) {
    users[n] = await account(n, list)
    log(`${n} angemeldet (id ${users[n].id})`)
  }
}
async function account(n, list) {
  const known = Array.isArray(list) ? list.find((a) => a.name === n) : null
  let r
  if (known) r = await call('POST', `/api/admin/invites/${known.id}/rotate`, { cookie: anna.cookie })
  else r = await call('POST', '/api/admin/invites', { cookie: anna.cookie, body: { name: n } })
  if (r.status !== 200 || !r.json?.code) throw new Error(`Konto ${n}: ${errText(r)}`)
  return login(r.json.code, n)
}

async function poll(u) {
  u.seq = u.seq ?? 0
  const r = await call('GET', `/api/social?after=${u.seq}`, { cookie: u.cookie })
  if (r.status === 200 && r.json) u.seq = Math.max(u.seq, r.json.seq ?? 0)
  return r
}
async function setIn(u, inChat) {
  return call('PUT', '/api/social/chat', { cookie: u.cookie, body: { in: inChat } })
}
async function say(u, text) {
  const r = await call('POST', '/api/social/chat', { cookie: u.cookie, body: { text } })
  if (r.status !== 200) log(`Chat ${u.name}: ${errText(r)}`)
}

async function friends() {
  for (const n of ['Bob', 'Clara', 'David', 'Emil']) {
    const r = await call('POST', '/api/friends', { cookie: anna.cookie, body: { name: n } })
    if (r.status === 200 && r.json?.state !== 'friend') await call('POST', `/api/friends/${anna.id}/accept`, { cookie: users[n].cookie })
  }
  await call('POST', '/api/friends', { cookie: users.Fritz.cookie, body: { name: anna.name } }) // eingehend
  await call('POST', '/api/friends', { cookie: anna.cookie, body: { name: 'Greta' } }) // ausgehend
  log('Freunde: Bob, Clara, David, Emil · Anfrage von Fritz · an Greta')
}

async function mine(u) {
  const r = await call('GET', '/api/tables/mine', { cookie: u.cookie })
  return r.status === 200 ? r.json : null
}
async function ensureTable(host, name, tempo) {
  const cur = await mine(host)
  if (cur && cur.hostUserId === host.id && cur.name === name) return cur
  if (cur) await call('POST', `/api/tables/${cur.id}/leave`, { cookie: host.cookie })
  const r = await call('POST', '/api/tables', { cookie: host.cookie, body: { name, tempo } })
  if (r.status !== 200) throw new Error(`Tisch ${name}: ${errText(r)}`)
  return r.json
}
async function joinTable(u, t, deck) {
  let r = await call('POST', `/api/tables/${t.id}/join`, { cookie: u.cookie })
  if (r.status !== 200) {
    log(`${u.name} -> ${t.name}: ${errText(r)}`)
    return null
  }
  if (deck !== undefined) r = await call('PUT', `/api/tables/${t.id}/seat`, { cookie: u.cookie, body: { deck } })
  return r.json
}
async function setBot(host, t, n, who = 'Bot') {
  return call('PUT', `/api/tables/${t.id}/seats/${n}`, { cookie: host.cookie, body: { kind: 'BOT', deck: deckFor(who) } })
}
async function tableOf(u, id) {
  const r = await call('GET', `/api/tables/${id}`, { cookie: u.cookie })
  return r.status === 200 ? r.json : null
}

async function annaTable() {
  const t = await ensureTable(anna, 'Annas Tisch', 'BLITZ')
  await call('PUT', `/api/tables/${t.id}/seat`, { cookie: anna.cookie, body: { deck: deckFor('Anna') } })
  const view = await tableOf(anna, t.id)
  if (view && view.seats[3]?.kind !== 'BOT') await setBot(anna, t, 3)
  if (view && !view.seats.some((s) => s.userId === users.Bob.id)) {
    await call('POST', `/api/tables/${t.id}/invite`, { cookie: anna.cookie, body: { userId: users.Bob.id } }) // hebt Entfernen auf
    await joinTable(users.Bob, t)
  }
  tables.anna = t
  log('Annas Tisch: Anna (Deck), Bob (waehlt Deck), Bot · Blitz')
  return t
}
async function annaTableChat() {
  const t = tables.anna
  if (!t) return
  await call('POST', `/api/tables/${t.id}/chat`, { cookie: users.Bob.cookie, body: { text: 'Nehme Sliver Swarm, ok?' } })
  await sleep(600)
  await call('POST', `/api/tables/${t.id}/chat`, { cookie: anna.cookie, body: { text: 'Klar. Ich lade noch Clara ein.' } })
}
async function claraTable() {
  const t = await ensureTable(users.Clara, 'Freitagsrunde', 'NORMAL')
  await call('PUT', `/api/tables/${t.id}/seat`, { cookie: users.Clara.cookie, body: { deck: deckFor('Clara') } })
  const view = await tableOf(users.Clara, t.id)
  if (view && !view.seats.some((s) => s.userId === users.Hanna.id)) await joinTable(users.Hanna, t, deckFor('Hanna'))
  tables.clara = t
  log('Freitagsrunde: Clara, Hanna · Normal')
  return t
}
async function iliasTable() {
  const t = await ensureTable(users.Ilias, "Ilias' Tisch", 'BEDACHT')
  const view = await tableOf(users.Ilias, t.id)
  tables.ilias = t
  if (view?.state === 'RUNNING') return t
  await call('PUT', `/api/tables/${t.id}/seat`, { cookie: users.Ilias.cookie, body: { deck: deckFor('Ilias') } })
  if (!view?.seats.some((s) => s.userId === users.David.id)) await joinTable(users.David, t, deckFor('David'))
  else await call('PUT', `/api/tables/${t.id}/seat`, { cookie: users.David.cookie, body: { deck: deckFor('David') } })
  await setBot(users.Ilias, t, 2, 'Bot2')
  await setBot(users.Ilias, t, 3, 'Bot3')
  const r = await call('POST', `/api/tables/${t.id}/start`, { cookie: users.Ilias.cookie })
  log(r.status === 200 ? "Ilias' Tisch: Spiel gestartet (Ilias, David, 2 Bots · Bedacht)" : `Ilias' Tisch: Start fehlgeschlagen (${errText(r)})`)
  return t
}

async function lobbyChat() {
  // Reihenfolge wie im Prototyp; Hannas Beitritt erzeugt die Systemzeile
  await setIn(users.Hanna, false)
  await say(users.Clara, 'Wer hat Lust auf eine Runde mit Präkons? Normales Tempo.')
  await sleep(1200)
  await say(users.Ilias, 'Bin gleich fertig, Zug 14 :)')
  await sleep(1200)
  await setIn(users.Hanna, true)
  await sleep(1200)
  await say(users.Bob, 'Sitze bei Anna. Ein Platz ist noch frei.')
  await sleep(1200)
  await say(users.Hanna, 'Hat jemand Kotori schon gegen Teferi getestet?')
  log('Lobby-Chat: 4 Zeilen + Systemzeile')
}

// ------------------------------------------------------------------ Aktionen / Phasen
async function doInvite() {
  if (!tables.clara) await claraTable()
  const r = await call('POST', `/api/tables/${tables.clara.id}/invite`, { cookie: users.Clara.cookie, body: { userId: anna.id } })
  return r.status === 200 ? 'Clara hat Anna eingeladen' : `Einladung: ${errText(r)}`
}
async function kickSetup() {
  if (!tables.clara) await claraTable()
  const cur = await mine(anna)
  if (cur && cur.id !== tables.clara.id) await call('POST', `/api/tables/${cur.id}/leave`, { cookie: anna.cookie })
  tables.anna = null
  const r = await joinTable(anna, tables.clara, deckFor('Anna'))
  return r ? 'Anna sitzt an der Freitagsrunde (Clara ist Gastgeberin)' : 'Anna konnte sich nicht setzen'
}
async function doKick() {
  const t = await tableOf(users.Clara, tables.clara?.id)
  const n = t ? t.seats.findIndex((s) => s.userId === anna.id) : -1
  if (n < 0) return 'Anna sitzt nicht an der Freitagsrunde (erst Phase kick)'
  const r = await call('PUT', `/api/tables/${t.id}/seats/${n}`, { cookie: users.Clara.cookie, body: { kind: 'OPEN' } })
  return r.status === 200 ? `Clara hat Anna entfernt (Platz ${n + 1})` : `Entfernen: ${errText(r)}`
}
async function doReady() {
  const t = tables.anna ?? (await annaTable())
  let view = await tableOf(anna, t.id)
  if (view && !view.seats.some((s) => s.userId === users.Bob.id)) {
    await call('POST', `/api/tables/${t.id}/invite`, { cookie: anna.cookie, body: { userId: users.Bob.id } })
    await joinTable(users.Bob, t)
  }
  const r = await call('PUT', `/api/tables/${t.id}/seat`, { cookie: users.Bob.cookie, body: { deck: deckFor('Bob') } })
  return r.status === 200 ? 'Bob ist bereit' : `Bob: ${errText(r)}`
}
async function doSysline(name = 'Fritz') {
  const u = users[name]
  if (!u) return `kein Nutzer ${name}`
  await setIn(u, false)
  await sleep(400)
  await setIn(u, true)
  return `${name} ist dem Lobby-Chat beigetreten (Systemzeile, falls nicht kuerzlich schon)`
}
async function doCrowd(n = 8) {
  const t = tables.ilias && (await tableOf(users.Ilias, tables.ilias.id))
  if (!t || t.state !== 'RUNNING' || !t.gameId) return "Ilias' Tisch laeuft nicht"
  // Wer an irgendeinem Tisch sitzt, darf nicht zuschauen (4409) -> nur freie Konten
  const all = (await call('GET', '/api/tables', { cookie: anna.cookie })).json ?? []
  const seated = new Set([t, ...all].flatMap((x) => x.seats ?? []).map((s) => s.userId).filter(Boolean))
  const pool = Object.values(users).filter((u) => !seated.has(u.id) && u.id !== anna?.id)
  let extra = 1
  while (pool.length < n) {
    const name = `Zuschauer ${extra++}`
    if (!users[name]) {
      const list = (await call('GET', '/api/admin/invites', { cookie: anna.cookie })).json ?? []
      users[name] = await account(name, list)
    }
    if (!pool.includes(users[name])) pool.push(users[name])
  }
  for (const u of pool.slice(0, n)) {
    const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/ws/game/${t.gameId}?spectate=1`, { headers: { Cookie: u.cookie, Origin: base } })
    const ping = setInterval(() => ws.readyState === 1 && ws.send(JSON.stringify({ t: 'ping' })), 20000)
    ws.onclose = (e) => {
      clearInterval(ping)
      log(`Zuschauer ${u.name} getrennt (${e.code})`)
    }
    ws.onmessage = () => {}
    crowd.push(ws)
  }
  return `${n} Zuschauer verbunden`
}

async function applyPhase(p) {
  phase = p
  if (p === 'invite') return doInvite()
  if (p === 'kick') return kickSetup()
  if (p === 'sysline') return doSysline('Fritz')
  if (!tables.anna) await annaTable()
  return `Phase ${p}: Grundzustand`
}

// ------------------------------------------------------------------ Piloten / Wachhund
function pilot(u, gameId, { chat } = {}) {
  const key = `${u.name}:${gameId}`
  if (pilots.has(key)) return
  const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/ws/game/${gameId}`, { headers: { Cookie: u.cookie, Origin: base } })
  const p = { ws, last: null, chatted: false }
  pilots.set(key, p)
  ws.onopen = () => log(`Pilot ${u.name} im Spiel ${gameId.slice(0, 8)}`)
  ws.onclose = () => pilots.delete(key)
  ws.onerror = () => {}
  ws.onmessage = (ev) => {
    let m
    try {
      m = JSON.parse(ev.data)
    } catch {
      return
    }
    if (m.t === 'state') {
      p.last = m
      if (chat && !p.chatted) {
        p.chatted = true
        setTimeout(() => ws.readyState === 1 && ws.send(JSON.stringify({ t: 'chat', text: chat })), 4000)
      }
    }
    if (m.t !== 'prompt') return
    const last = p.last
    let ans
    if (m.kind === 'ASK') ans = { bool: !m.mulligan }
    else if (m.kind === 'SELECT' && m.mode !== 'priority') ans = { bool: true }
    else if (m.kind === 'SELECT') {
      const land = last?.hand?.find((c) => last.actions?.includes(c.id) && c.types?.includes('LAND'))
      ans = land ? { uuid: land.id } : { bool: false }
    } else if (m.kind === 'PICK_TARGET') ans = m.targets?.length && !m.chosen?.length ? { uuid: m.targets[0] } : { bool: false }
    else if (m.kind === 'CHOOSE_CHOICE') ans = { str: m.choice.keyed ? m.choice.items[0].key : m.choice.items[0].value }
    else if (m.kind === 'AMOUNT') ans = { int: m.min ?? 0 }
    else if (m.kind === 'MULTI_AMOUNT') ans = { str: m.items.map((i) => i.value).join(' ') }
    else if (m.kind === 'PLAY_MANA' || m.kind === 'PLAY_X_MANA') ans = { bool: false }
    else ans = m.choices?.length ? { uuid: m.choices[0].id } : { bool: false }
    ws.send(JSON.stringify({ t: 'respond', id: m.id, ...ans }))
  }
}

let restartAt = 0
async function watchdog() {
  for (const [key, t] of Object.entries(tables)) {
    if (!t) continue
    const host = key === 'anna' ? anna : key === 'clara' ? users.Clara : users.Ilias
    const view = await tableOf(host, t.id)
    if (!view) {
      tables[key] = null
      continue
    }
    if (view.state === 'RUNNING' && view.gameId) {
      for (const s of view.seats) {
        if (s.kind !== 'HUMAN' || s.userId === anna.id) continue // Anna spielt in der App
        const u = Object.values(users).find((x) => x.id === s.userId)
        if (u) pilot(u, view.gameId, { chat: u.name === 'Bob' ? 'gl hf!' : null })
      }
    } else if (key === 'ilias' && withRunning && Date.now() > restartAt) {
      restartAt = Date.now() + 15000
      log("Ilias' Spiel ist vorbei - neu starten")
      await iliasTable()
    }
  }
}

// ------------------------------------------------------------------ Steuerung
function control() {
  if (!controlPort) return null
  const srv = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x')
    const send = (code, obj) => {
      res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' })
      res.end(JSON.stringify(obj))
    }
    try {
      const parts = url.pathname.split('/').filter(Boolean)
      if (req.method === 'GET' && parts[0] === 'status') return send(200, await status())
      if (req.method !== 'POST') return send(405, { error: 'POST' })
      let msg
      if (parts[0] === 'phase') msg = await applyPhase(parts[1])
      else if (parts[0] === 'do') {
        const q = url.searchParams
        const a = parts[1]
        if (a === 'invite') msg = await doInvite()
        else if (a === 'kick') msg = await doKick()
        else if (a === 'anna-table') msg = (await annaTable()) && 'Annas Tisch steht'
        else if (a === 'ready') msg = await doReady()
        else if (a === 'sysline') msg = await doSysline(q.get('name') ?? 'Fritz')
        else if (a === 'chat') {
          const u = users[q.get('name') ?? 'Bob']
          if (u) await say(u, q.get('text') ?? 'Hallo')
          msg = 'gesendet'
        } else if (a === 'crowd') msg = await doCrowd(Number(q.get('n') ?? 8))
        else return send(404, { error: 'unbekannt' })
      } else if (parts[0] === 'quit') {
        send(200, { ok: true, msg: 'beende' })
        return shutdown(url.searchParams.get('cleanup') !== '0')
      } else return send(404, { error: 'unbekannt' })
      log(`Steuerung ${url.pathname}: ${msg}`)
      send(200, { ok: true, msg })
    } catch (e) {
      send(500, { error: String(e.message ?? e) })
    }
  })
  srv.listen(controlPort, '127.0.0.1', () => log(`Steuerung auf http://127.0.0.1:${controlPort}`))
  return srv
}
async function status() {
  const out = { phase, tables: {}, pilots: [...pilots.keys()].map((k) => k.split(':')[0]), crowd: crowd.length }
  for (const [k, t] of Object.entries(tables)) {
    if (!t) continue
    const v = await tableOf(anna, t.id)
    if (v) out.tables[k] = { name: v.name, state: v.state, turn: v.turn ?? null, humans: v.humans }
  }
  return out
}

// ------------------------------------------------------------------ Ablauf
let srv = null
const timers = []
async function shutdown(cleanup = cleanupOnExit) {
  if (stopping) return
  stopping = true
  timers.forEach(clearInterval)
  for (const p of pilots.values()) p.ws.close()
  for (const ws of crowd) ws.close()
  if (cleanup) {
    for (const [key, t] of Object.entries(tables)) {
      if (!t) continue
      const host = key === 'anna' ? anna : key === 'clara' ? users.Clara : users.Ilias
      await call('POST', `/api/tables/${t.id}/leave`, { cookie: host.cookie }).catch(() => {})
    }
    log('Tische geschlossen')
    if (purge) {
      for (const n of Object.keys(users)) {
        await call('DELETE', `/api/friends/${users[n].id}`, { cookie: anna.cookie }).catch(() => {})
        await call('DELETE', `/api/admin/invites/${users[n].id}`, { cookie: anna.cookie }).catch(() => {})
      }
      log('Freundschaften und Konten entfernt')
    }
  }
  if (srv) srv.close()
  log('social-seed beendet')
  process.exit(0)
}
process.on('SIGINT', () => void shutdown())
process.on('SIGTERM', () => void shutdown())

try {
  const health = await call('GET', '/api/health')
  if (health.json?.mode !== 'server') throw new Error('Engine laeuft nicht im Server-Modus (--server)')
  await accounts()
  samples = (await call('GET', '/api/samples', { cookie: anna.cookie })).json ?? []
  for (const n of POLLERS) await poll(users[n]) // online markieren
  timers.push(setInterval(() => POLLERS.forEach((n) => void poll(users[n]).catch(() => {})), 5000))
  await friends()
  await lobbyChat()
  await claraTable()
  if (withRunning) await iliasTable()
  if (startPhase !== 'kick') {
    await annaTable()
    await annaTableChat()
  }
  log(await applyPhase(startPhase))
  srv = control()
  timers.push(setInterval(() => void watchdog().catch((e) => log('Wachhund:', e.message)), 2000))
  setTimeout(() => void shutdown(), durationMin * 60000)
  log(`bereit (Phase ${phase}, ${durationMin} min)`)
} catch (e) {
  log('Fehler:', e.message)
  await shutdown(false)
}
