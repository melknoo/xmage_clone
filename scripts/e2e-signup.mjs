// End-to-End-Test fuer die Selbstregistrierung und die Kostenbremsen (Server-Modus).
// Startet selbst eine Engine aus engine/build/install (vorher: cd engine; .\gradlew.bat installDist):
//   --server --dev auf 7421 (Owner-Code DEV-OWNER-CODE), Daten engine/run/signup-e2e, Mails als JSON in mail-outbox
// Geprueft: Registrierung -> Login vor Bestaetigung 403 -> Link aus der Mail -> angemeldet (tier public);
// doppelte E-Mail (gleiche Antwort, Hinweis-Mail), Name vergeben, Limits pro IP und pro Tag, Passwort vergessen/Reset;
// oeffentliche Konten: keine Server-Spiele/-Tische, Beitritt zu Freundes-Tisch ok; Admin: tier/verified, Umschalten;
// Neustarts: Budget erschoepft (--budget-min=0), volle Plaetze, Aufraeumen unbestaetigter Konten,
// Weck-Schutz (nur anonyme Anfragen -> Engine endet nach --anon-exit-min; angemeldete halten sie wach).
//   node scripts/e2e-signup.mjs          (SIGNUP_SKIP_IDLE=1 ueberspringt den ~4-min-Leerlauftest)
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const lib = path.join(root, 'engine', 'build', 'install', 'magelite-engine', 'lib')
const forge = path.join(root, 'vendor', 'forge')
const PORT = Number(process.env.SIGNUP_PORT ?? 7421)
const BASE = `http://127.0.0.1:${PORT}`
const dataDir = path.join(root, 'engine', 'run', 'signup-e2e')
const outbox = path.join(dataDir, 'mail-outbox')
const ownerCode = 'DEV-OWNER-CODE'
const DOWNLOAD = 'https://example.org/releases/v{v}/MageLite-Setup-{v}.exe'

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
async function call(method, apiPath, { body, cookie, ip } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (cookie) headers.Cookie = cookie
  // eigene "Client-IP" je Schritt: das Login-Rate-Limit (10/min/IP) gilt auch fuer Registrierung & Co.
  headers['Fly-Client-IP'] = ip ?? `10.9.${Math.floor(Math.random() * 250)}.${Math.floor(Math.random() * 250)}`
  const r = await fetch(BASE + apiPath, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined })
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

/** Mails an `to` (aelteste zuerst) */
function mailsTo(to) {
  if (!fs.existsSync(outbox)) return []
  return fs
    .readdirSync(outbox)
    .filter((f) => f.endsWith('.json'))
    .sort((a, b) => Number(a.split('-')[0]) - Number(b.split('-')[0]) || Number(a.split('-')[1].split('.')[0]) - Number(b.split('-')[1].split('.')[0]))
    .map((f) => JSON.parse(fs.readFileSync(path.join(outbox, f), 'utf8')))
    .filter((m) => m.to === to)
}
const tokenIn = (mail, kind) => mail?.text.match(new RegExp(`#${kind}=([A-Za-z0-9_-]+)`))?.[1] ?? null

// ---- Engine
let proc = null
let exited = null
function startEngine({ fresh = false, args = [], env = {} } = {}) {
  fs.mkdirSync(dataDir, { recursive: true })
  if (fresh) {
    for (const f of ['magelite.db', 'magelite.db-journal', 'magelite.db-wal', 'magelite.db-shm']) {
      try {
        fs.rmSync(path.join(dataDir, f))
      } catch {}
    }
    fs.rmSync(outbox, { recursive: true, force: true })
  }
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java'
  const jvm = [
    '-Xmx768m', '-XX:+UseG1GC', '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', `-Dmagelite.forge=${forge}`,
    '-cp', `${path.join(lib, 'magelite-engine.jar')}${path.delimiter}${path.join(lib, '*')}`,
    'dev.magelite.Main', `--data=${dataDir}`, `--port=${PORT}`, `--forge=${forge}`, '--dev', '--server', ...args,
  ]
  const fullEnv = {
    ...process.env,
    MAGELITE_OWNER_CODE: ownerCode,
    MAGELITE_OWNER_NAME: 'Owner',
    MAGELITE_SIGNUP: 'open',
    MAGELITE_SIGNUPS_PER_DAY: '5',
    MAGELITE_MAX_PUBLIC_USERS: '100',
    MAGELITE_DOWNLOAD_URL: DOWNLOAD,
    MAGELITE_PUBLIC_URL: 'http://localhost:5173',
    ...env,
  }
  // nie echte Mails/Captchas aus dem Test heraus
  delete fullEnv.MAGELITE_MAIL_API_KEY
  delete fullEnv.MAGELITE_TURNSTILE_SECRET
  const p = spawn(java, jvm, { cwd: dataDir, env: fullEnv, stdio: ['ignore', 'pipe', 'pipe'] })
  proc = p
  exited = null
  const log = fs.createWriteStream(path.join(dataDir, 'e2e-signup.log'), { flags: 'a' })
  p.stderr.pipe(log)
  p.on('exit', (code) => {
    exited = { code, at: Date.now() }
  })
  return new Promise((resolve, reject) => {
    let buf = ''
    p.stdout.on('data', (d) => {
      const s = d.toString()
      log.write(s)
      buf += s
      const m = buf.match(/MAGELITE_READY (\{.*\})/)
      if (m) resolve(JSON.parse(m[1]))
    })
    p.on('exit', (code) => reject(new Error(`Engine beendet (Exit ${code})`)))
    setTimeout(() => reject(new Error('kein MAGELITE_READY nach 180 s')), 180_000)
  })
}
async function stopEngine() {
  if (!proc || exited) return
  proc.kill()
  for (let i = 0; i < 50 && !exited; i++) await sleep(100)
}
process.on('exit', () => {
  try {
    proc?.kill()
  } catch {}
})

async function loginOwner() {
  const r = await call('POST', '/api/auth/login', { body: { code: ownerCode } })
  return cookieOf(r.setCookie)
}

console.log('Starte Engine (Server-Modus, Registrierung offen) ...')
try {
  await startEngine({ fresh: true })
} catch (e) {
  console.error(`FEHLER ${e.message}`)
  process.exit(2)
}

// SIGNUP_ONLY_START=1: Engine mit Testdaten laufen lassen (Screenshots, Handtests): Alice (registriert, bestaetigt,
// Passwort geheim123), Bob (unbestaetigt), Owner mit E-Mail. Strg+C beendet sie.
if (process.env.SIGNUP_ONLY_START) {
  await call('POST', '/api/auth/signup', { body: { name: 'Alice', email: 'alice@example.org', password: 'geheim123', captcha: '' } })
  await call('POST', '/api/auth/verify', { body: { token: tokenIn(mailsTo('alice@example.org').at(-1), 'verify') } })
  await call('POST', '/api/auth/signup', { body: { name: 'Bob', email: 'bob@example.org', password: 'geheim123', captcha: '' } })
  await call('POST', '/api/auth/register', { cookie: await loginOwner(), body: { email: 'owner@example.org', password: 'ownerPW123' } })
  console.log(`Engine laeuft auf ${BASE} (SIGNUP_ONLY_START) - Alice: alice@example.org / geheim123 - Strg+C zum Beenden`)
  await new Promise(() => {})
}

try {
  // ---- oeffentliche Infos
  let r = await call('GET', '/api/auth/options')
  ok(r.status === 200 && r.json?.signup === 'open' && r.json?.forgot === true && r.json?.turnstileSiteKey === null, `options: ${JSON.stringify(r.json)}`)
  r = await call('GET', '/api/download/info')
  ok(r.status === 200 && (r.json?.available === false || (r.json?.url ?? '').startsWith('https://example.org/releases/v')), `download/info: ${JSON.stringify(r.json)}`)
  r = await call('GET', '/api/download/file')
  ok(r.status === 401, `/api/download/file gibt es nicht mehr (ohne Login 401): ${r.status}`)

  // ---- Registrierung
  const alice = 'alice@example.org'
  r = await call('POST', '/api/auth/signup', { body: { name: 'Alice', email: alice, password: 'geheim123', captcha: '' }, ip: '10.0.0.1' })
  ok(r.status === 200 && r.json?.ok === true, `Registrierung Alice: ${r.status}`)
  ok(mailsTo(alice).length === 1 && tokenIn(mailsTo(alice)[0], 'verify'), 'Bestaetigungsmail mit Link')
  r = await call('POST', '/api/auth/login', { body: { email: alice, password: 'geheim123' } })
  ok(r.status === 403 && r.json?.unverified === true && !r.setCookie, `Login vor Bestaetigung 403 unverified: ${r.status}`)
  r = await call('POST', '/api/auth/login', { body: { email: alice, password: 'falsch999' } })
  ok(r.status === 401, `falsches Passwort bleibt 401 (kein Hinweis auf Konto): ${r.status}`)
  r = await call('POST', '/api/auth/signup', { body: { name: 'Alice2', email: alice, password: 'egal12345', captcha: '' } })
  ok(r.status === 200 && mailsTo(alice).length === 1, 'doppelte Registrierung (unbestaetigt): gleiche Antwort, keine zweite Mail binnen 5 min')
  r = await call('POST', '/api/auth/signup', { body: { name: 'alice', email: 'other@example.org', password: 'geheim123', captcha: '' } })
  ok(r.status === 409, `Name vergeben (Gross/klein egal) 409: ${r.status}`)
  r = await call('POST', '/api/auth/signup', { body: { name: 'X', email: 'kaputt', password: 'geheim123', captcha: '' } })
  ok(r.status === 400, `ungueltige E-Mail 400: ${r.status}`)
  r = await call('POST', '/api/auth/signup', { body: { name: 'Y', email: 'y@example.org', password: 'kurz', captcha: '' } })
  ok(r.status === 400, `zu kurzes Passwort 400: ${r.status}`)

  r = await call('POST', '/api/auth/verify', { body: { token: 'quatsch' } })
  ok(r.status === 400, `falscher Bestaetigungslink 400: ${r.status}`)
  const verifyToken = tokenIn(mailsTo(alice)[0], 'verify')
  r = await call('POST', '/api/auth/verify', { body: { token: verifyToken } })
  let aliceCookie = cookieOf(r.setCookie)
  ok(r.status === 200 && !!aliceCookie, `Bestaetigung meldet an: ${r.status}`)
  r = await call('POST', '/api/auth/verify', { body: { token: verifyToken } })
  ok(r.status === 400, `Link gilt nur einmal: ${r.status}`)
  r = await call('GET', '/api/me', { cookie: aliceCookie })
  ok(r.status === 200 && r.json?.user?.tier === 'public' && r.json?.budget?.limited === false, `me: tier=${r.json?.user?.tier}, budget=${JSON.stringify(r.json?.budget)}`)
  r = await call('POST', '/api/auth/login', { body: { email: alice, password: 'geheim123' } })
  ok(r.status === 200, `Login nach Bestaetigung: ${r.status}`)

  r = await call('POST', '/api/auth/signup', { body: { name: 'Alice3', email: alice, password: 'egal12345', captcha: '' } })
  const notice = mailsTo(alice).at(-1)
  ok(r.status === 200 && /schon ein Konto/.test(notice?.subject ?? ''), `doppelte Registrierung (bestaetigt): Hinweis-Mail "${notice?.subject}"`)

  // ---- Passwort vergessen
  r = await call('POST', '/api/auth/forgot', { body: { email: 'niemand@example.org', captcha: '' } })
  ok(r.status === 200, `forgot fuer unbekannte E-Mail: gleiche Antwort ${r.status}`)
  r = await call('POST', '/api/auth/forgot', { body: { email: alice, captcha: '' } })
  const resetToken = tokenIn(mailsTo(alice).at(-1), 'reset')
  ok(r.status === 200 && !!resetToken, 'forgot: Reset-Mail mit Link')
  r = await call('POST', '/api/auth/reset', { body: { token: resetToken, password: 'neuesPW123' } })
  const aliceAfterReset = cookieOf(r.setCookie)
  ok(r.status === 200 && !!aliceAfterReset, `Reset setzt Passwort und meldet an: ${r.status}`)
  r = await call('GET', '/api/me', { cookie: aliceCookie })
  ok(r.status === 401, `alte Session nach Reset beendet: ${r.status}`)
  r = await call('POST', '/api/auth/login', { body: { email: alice, password: 'geheim123' } })
  ok(r.status === 401, `altes Passwort ungueltig: ${r.status}`)
  r = await call('POST', '/api/auth/login', { body: { email: alice, password: 'neuesPW123' } })
  aliceCookie = cookieOf(r.setCookie)
  ok(r.status === 200, `neues Passwort gilt: ${r.status}`)

  // ---- Limits: 3 pro IP und Tag, 5 pro Tag insgesamt (Alice zaehlt mit)
  const ip = '10.0.0.2'
  for (let i = 1; i <= 3; i++) {
    r = await call('POST', '/api/auth/signup', { body: { name: `Bob${i}`, email: `bob${i}@example.org`, password: 'geheim123', captcha: '' }, ip })
    ok(r.status === 200, `Bob${i} von ${ip}: ${r.status}`)
  }
  r = await call('POST', '/api/auth/signup', { body: { name: 'Bob4', email: 'bob4@example.org', password: 'geheim123', captcha: '' }, ip })
  ok(r.status === 429, `viertes Konto derselben IP 429: ${r.status}`)
  r = await call('POST', '/api/auth/signup', { body: { name: 'Dave', email: 'dave@example.org', password: 'geheim123', captcha: '' }, ip: '10.0.0.3' })
  ok(r.status === 200, `Dave (5. heute): ${r.status}`)
  r = await call('POST', '/api/auth/signup', { body: { name: 'Eve', email: 'eve@example.org', password: 'geheim123', captcha: '' }, ip: '10.0.0.4' })
  ok(r.status === 403, `Tageslimit erreicht 403: ${r.status} ${r.json?.error}`)
  r = await call('GET', '/api/auth/options')
  ok(r.json?.signup === 'daily', `options.signup=daily: ${r.json?.signup}`)

  // ---- oeffentliches Konto: keine Server-Spiele/-Tische
  r = await call('POST', '/api/games', { cookie: aliceCookie, body: { deck: { type: 'random' }, tempo: 'BLITZ' } })
  ok(r.status === 403 && r.json?.publicLimit === true, `Alice: Spiel gegen Bots auf dem Server 403: ${r.status}`)
  r = await call('POST', '/api/tables', { cookie: aliceCookie, body: { hosting: 'SERVER' } })
  ok(r.status === 403 && r.json?.publicLimit === true, `Alice: Server-Tisch 403: ${r.status}`)
  r = await call('POST', '/api/tables', { cookie: aliceCookie, body: { hosting: 'REMOTE' } })
  ok(r.status === 409, `Alice: Tisch auf eigenem Rechner ohne App 409: ${r.status} ${r.json?.error}`)
  const owner = await loginOwner()
  r = await call('POST', '/api/tables', { cookie: owner, body: { hosting: 'SERVER', name: 'Owners Tisch' } })
  const tableId = r.json?.id
  ok(r.status === 200 && !!tableId, `Owner eroeffnet Server-Tisch: ${r.status}`)
  r = await call('POST', `/api/tables/${tableId}/join`, { cookie: aliceCookie, body: {} })
  ok(r.status === 200, `Alice tritt dem Tisch des Owners bei: ${r.status}`)
  await call('POST', `/api/tables/${tableId}/leave`, { cookie: aliceCookie, body: {} })
  await call('POST', `/api/tables/${tableId}/leave`, { cookie: owner, body: {} })

  // ---- Admin
  r = await call('GET', '/api/admin/users', { cookie: owner })
  const rows = r.json ?? []
  const aliceRow = rows.find((u) => u.name === 'Alice')
  const bobRow = rows.find((u) => u.name === 'Bob1')
  const ownerRow = rows.find((u) => u.admin)
  ok(aliceRow?.tier === 'public' && aliceRow?.verified === true, `Admin: Alice public/bestaetigt ${aliceRow?.tier}/${aliceRow?.verified}`)
  ok(bobRow?.tier === 'public' && bobRow?.verified === false, `Admin: Bob1 public/unbestaetigt ${bobRow?.tier}/${bobRow?.verified}`)
  ok(ownerRow?.tier === 'friend', `Admin: Owner friend ${ownerRow?.tier}`)
  r = await call('POST', `/api/admin/users/${aliceRow?.id}/tier`, { cookie: owner, body: { tier: 'friend' } })
  ok(r.status === 200, `Alice zum Freund: ${r.status}`)
  r = await call('GET', '/api/me', { cookie: aliceCookie })
  ok(r.json?.user?.tier === 'friend', `Alice sofort friend: ${r.json?.user?.tier}`)
  r = await call('POST', '/api/tables', { cookie: aliceCookie, body: { hosting: 'SERVER' } })
  ok(r.status === 200, `Alice (friend) darf Server-Tisch: ${r.status}`)
  await call('POST', `/api/tables/${r.json?.id}/leave`, { cookie: aliceCookie, body: {} })
  r = await call('POST', `/api/admin/users/${aliceRow?.id}/tier`, { cookie: owner, body: { tier: 'public' } })
  ok(r.status === 200, `Alice zurueck auf public: ${r.status}`)
  r = await call('POST', `/api/admin/users/${ownerRow?.id}/tier`, { cookie: owner, body: { tier: 'public' } })
  ok(r.status === 404, `Owner laesst sich nicht herabstufen: ${r.status}`)
  r = await call('POST', `/api/admin/users/${aliceRow?.id}/tier`, { cookie: aliceCookie, body: { tier: 'friend' } })
  ok(r.status === 403, `Nicht-Admin darf tier nicht setzen: ${r.status}`)
  r = await call('GET', '/api/admin/server', { cookie: owner })
  ok(r.json?.budget && r.json.budget.budgetMin === 6000 && r.json.budget.exhausted === false, `Admin-Server: budget ${JSON.stringify(r.json?.budget)}`)
  const ownerPw = 'ownerPW123'
  r = await call('POST', '/api/auth/register', { cookie: owner, body: { email: 'owner@example.org', password: ownerPw } })
  ok(r.status === 200, `Owner sichert Konto (E-Mail fuer Budget-Warnungen): ${r.status}`)

  // ---- Neustart: Budget erschoepft
  await stopEngine()
  await startEngine({ args: ['--budget-min=0'] })
  r = await call('GET', '/api/me', { cookie: aliceCookie })
  ok(r.status === 200 && r.json?.budget?.limited === true, `Budget 0: Alice /api/me ok, limited=${r.json?.budget?.limited}`)
  r = await call('GET', '/api/decks', { cookie: aliceCookie })
  ok(r.status === 503 && r.json?.budget === true, `Budget 0: Alice /api/decks 503 budget: ${r.status}`)
  r = await call('GET', '/api/social', { cookie: aliceCookie })
  ok(r.status === 503, `Budget 0: Alice /api/social 503: ${r.status}`)
  const owner2 = await loginOwner()
  r = await call('GET', '/api/decks', { cookie: owner2 })
  ok(r.status === 200, `Budget 0: Owner normal: ${r.status}`)
  r = await call('GET', '/api/me', { cookie: owner2 })
  ok(r.json?.budget?.limited === false, `Budget 0: Owner nicht limited`)
  r = await call('GET', '/api/auth/options')
  ok(r.json?.signup === 'budget', `Budget 0: options.signup=budget: ${r.json?.signup}`)
  r = await call('POST', '/api/auth/signup', { body: { name: 'Fred', email: 'fred@example.org', password: 'geheim123', captcha: '' } })
  ok(r.status === 403, `Budget 0: Registrierung 403: ${r.status}`)
  r = await call('GET', '/api/admin/server', { cookie: owner2 })
  ok(r.json?.budget?.exhausted === true, 'Budget 0: Admin sieht exhausted')

  // ---- Neustart: Plaetze voll + unbestaetigte Konten aufraeumen
  await stopEngine()
  await startEngine({ args: ['--unverified-ttl-min=0'], env: { MAGELITE_MAX_PUBLIC_USERS: '1', MAGELITE_SIGNUPS_PER_DAY: '30' } })
  const owner3 = await loginOwner()
  r = await call('GET', '/api/admin/users', { cookie: owner3 })
  const names = (r.json ?? []).map((u) => u.name)
  ok(!names.includes('Bob1') && !names.includes('Dave') && names.includes('Alice'), `unbestaetigte geloescht, Alice bleibt: ${names.join(', ')}`)
  r = await call('GET', '/api/auth/options')
  ok(r.json?.signup === 'full', `MAX_PUBLIC_USERS=1: options.signup=full: ${r.json?.signup}`)

  // ---- Weck-Schutz
  if (process.env.SIGNUP_SKIP_IDLE) {
    console.log('SKIP Leerlauftest (SIGNUP_SKIP_IDLE)')
  } else {
    await stopEngine()
    console.log('Leerlauftest 1: nur anonyme Anfragen, --anon-exit-min=1 (dauert ~1-2 min) ...')
    await startEngine({ args: ['--idle-exit-min=3', '--anon-exit-min=1'] })
    const t0 = Date.now()
    while (!exited && Date.now() - t0 < 180_000) {
      await call('GET', '/api/health').catch(() => null)
      await call('GET', '/api/auth/options').catch(() => null)
      await call('GET', '/api/download/info').catch(() => null)
      await call('GET', '/api/me').catch(() => null)
      await sleep(5000)
    }
    ok(!!exited && Date.now() - t0 < 150_000, `nur anonym: Engine endet nach ${Math.round((Date.now() - t0) / 1000)} s`)

    console.log('Leerlauftest 2: angemeldet, alle 20 s eine Anfrage, 150 s lang ...')
    await startEngine({ args: ['--idle-exit-min=2', '--anon-exit-min=1'] })
    const ownerIdle = await loginOwner()
    const t1 = Date.now()
    while (!exited && Date.now() - t1 < 150_000) {
      await call('GET', '/api/decks', { cookie: ownerIdle }).catch(() => null)
      await sleep(20_000)
    }
    ok(!exited, `angemeldet: Engine laeuft nach ${Math.round((Date.now() - t1) / 1000)} s noch`)
  }
} catch (e) {
  ok(false, `Ausnahme: ${e.stack ?? e}`)
} finally {
  await stopEngine()
}

console.log(failed === 0 ? '\nAlle Pruefungen OK' : `\n${failed} Pruefung(en) fehlgeschlagen`)
process.exit(failed === 0 ? 0 : 1)
