// End-to-End-Test fuer den Server-Modus (Konten, Cookie-Login, Nutzertrennung) gegen eine laufende Dev-Engine:
//   cd engine; .\gradlew.bat runServer        (Owner-Code DEV-OWNER-CODE, Port 7317)
//   node scripts\e2e-login.mjs
// Env: MAGELITE_URL (Default http://127.0.0.1:7317), MAGELITE_OWNER_CODE (Default DEV-OWNER-CODE)
const base = process.env.MAGELITE_URL ?? 'http://127.0.0.1:7317'
const ownerCode = process.env.MAGELITE_OWNER_CODE ?? 'DEV-OWNER-CODE'

let failed = 0
const ok = (cond, what) => {
  console.log(`${cond ? 'OK  ' : 'FAIL'} ${what}`)
  if (!cond) failed++
}

/** Anfrage mit optionalem Cookie; liefert {status, json, setCookie} statt zu werfen. */
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
const cookieOf = (setCookie) => (setCookie ? setCookie.split(';')[0] : null)

// 1. Health ohne Cookie
let r = await call('GET', '/api/health')
ok(r.status === 200 && r.json?.mode === 'server', `health ohne Cookie: ${r.status} mode=${r.json?.mode}`)

// 2./3. ohne Login
r = await call('GET', '/api/me')
ok(r.status === 401, `/api/me ohne Cookie -> ${r.status}`)
r = await call('POST', '/api/auth/login', { body: { code: 'ZZZZ-ZZZZ-ZZZZ-ZZZZ' } })
ok(r.status === 401, `Login mit falschem Code -> ${r.status}`)

// 4./5. Owner-Login
r = await call('POST', '/api/auth/login', { body: { code: ownerCode } })
ok(r.status === 200 && r.json?.user?.admin === true, `Owner-Login -> ${r.status} admin=${r.json?.user?.admin}`)
ok(/HttpOnly/i.test(r.setCookie ?? '') && /SameSite=Lax/i.test(r.setCookie ?? ''), `Cookie HttpOnly+Lax: ${r.setCookie}`)
const owner = cookieOf(r.setCookie)
r = await call('GET', '/api/me', { cookie: owner })
ok(r.status === 200 && r.json?.mode === 'server', `/api/me mit Cookie -> ${r.status}`)

// 6. Einladung anlegen
r = await call('POST', '/api/admin/invites', { cookie: owner, body: { name: 'Anna' } })
ok(r.status === 200 && /^[A-Z2-7]{4}(-[A-Z2-7]{4}){3}$/.test(r.json?.code ?? ''), `Einladung Anna -> ${r.status} code=${r.json?.code}`)
const anna = r.json
const annaCode = anna.code

// 7. Login mit vertipptem Code (klein, ohne Bindestriche, O->0, I->1, B->8)
const mangled = annaCode.toLowerCase().replace(/-/g, '').replace(/o/g, '0').replace(/i/g, '1').replace(/b/g, '8')
r = await call('POST', '/api/auth/login', { body: { code: mangled } })
ok(r.status === 200 && r.json?.user?.name === 'Anna' && r.json?.user?.admin === false, `Anna-Login mit "${mangled}" -> ${r.status}`)
let annaCookie = cookieOf(r.setCookie)
ok(/^ml_sess=/.test(annaCookie ?? ''), `Login setzt Session-Cookie: ${annaCookie?.split('=')[0]}`)

// 7b. Konto sichern (E-Mail + Passwort), Login damit, Logout nur fuer diese Session, Passwortwechsel
r = await call('GET', '/api/me', { cookie: annaCookie })
ok(r.json?.user?.hasPassword === false && r.json?.user?.email === null, `Anna ist Gast (hasPassword=${r.json?.user?.hasPassword})`)
r = await call('POST', '/api/auth/register', { cookie: annaCookie, body: { email: 'anna@example.de', password: 'kurz' } })
ok(r.status === 400, `Passwort zu kurz -> ${r.status}`)
r = await call('POST', '/api/auth/register', { cookie: annaCookie, body: { email: 'keine-mail', password: 'geheim123' } })
ok(r.status === 400, `ungueltige E-Mail -> ${r.status}`)
r = await call('POST', '/api/auth/register', { cookie: annaCookie, body: { email: 'Anna@Example.de', password: 'geheim123' } })
ok(r.status === 200 && r.json?.user?.hasPassword === true && r.json?.user?.email === 'anna@example.de', `Konto gesichert -> ${r.status} email=${r.json?.user?.email}`)
r = await call('POST', '/api/auth/register', { cookie: annaCookie, body: { email: 'anna2@example.de', password: 'geheim123' } })
ok(r.status === 409, `nochmal sichern -> ${r.status}`)
r = await call('POST', '/api/auth/register', { cookie: owner, body: { email: 'ANNA@example.de', password: 'geheim123' } })
ok(r.status === 409, `Owner mit Annas E-Mail -> ${r.status}`)
r = await call('POST', '/api/auth/login', { body: { email: 'anna@EXAMPLE.de', password: 'falsch123' } })
ok(r.status === 401, `E-Mail-Login mit falschem Passwort -> ${r.status}`)
r = await call('POST', '/api/auth/login', { body: { email: 'anna@EXAMPLE.de', password: 'geheim123' } })
ok(r.status === 200 && r.json?.user?.name === 'Anna', `E-Mail-Login (andere Schreibweise) -> ${r.status}`)
const annaSess2 = cookieOf(r.setCookie)
r = await call('POST', '/api/auth/logout', { cookie: annaSess2 })
ok(r.status === 200, `Logout -> ${r.status}`)
r = await call('GET', '/api/me', { cookie: annaSess2 })
ok(r.status === 401, `abgemeldete Session -> ${r.status}`)
r = await call('GET', '/api/me', { cookie: annaCookie })
ok(r.status === 200, `andere Session von Anna lebt weiter -> ${r.status}`)
r = await call('PUT', '/api/auth/account', { cookie: annaCookie, body: { current: 'falsch123', password: 'neues12345' } })
ok(r.status === 401, `Passwort aendern mit falschem aktuellen Passwort -> ${r.status}`)
r = await call('POST', '/api/auth/login', { body: { email: 'anna@example.de', password: 'geheim123' } })
const annaSess3 = cookieOf(r.setCookie)
r = await call('PUT', '/api/auth/account', { cookie: annaCookie, body: { current: 'geheim123', password: 'neues12345' } })
ok(r.status === 200 && r.json?.user?.hasPassword === true, `Passwort geaendert -> ${r.status}`)
r = await call('GET', '/api/me', { cookie: annaSess3 })
ok(r.status === 401, `andere Session nach Passwortwechsel beendet -> ${r.status}`)
r = await call('GET', '/api/me', { cookie: annaCookie })
ok(r.status === 200, `eigene Session nach Passwortwechsel bleibt -> ${r.status}`)
r = await call('POST', '/api/auth/login', { body: { email: 'anna@example.de', password: 'geheim123' } })
ok(r.status === 401, `altes Passwort -> ${r.status}`)

// 8. Nutzertrennung: Decks und Profil
r = await call('POST', '/api/decks', { cookie: owner, body: { name: 'Owner-Testdeck', text: '1 Krenko, Mob Boss\n30 Mountain', commanders: ['Krenko, Mob Boss'] } })
ok(r.status === 200 && r.json?.id, `Owner speichert Deck -> ${r.status} id=${r.json?.id}`)
const ownerDeckId = r.json?.id
r = await call('GET', '/api/decks', { cookie: annaCookie })
ok(r.status === 200 && Array.isArray(r.json) && r.json.length === 0, `Anna sieht ${Array.isArray(r.json) ? r.json.length : '?'} Decks (erwartet 0)`)
r = await call('GET', `/api/decks/${ownerDeckId}`, { cookie: annaCookie })
ok(r.status === 400 || r.status === 404, `Anna liest Owner-Deck -> ${r.status}`)
r = await call('POST', '/api/decks', { cookie: annaCookie, body: { id: ownerDeckId, name: 'Gekapert', text: '1 Krenko, Mob Boss\n30 Mountain', commanders: ['Krenko, Mob Boss'] } })
ok(r.status === 400 || r.status === 404, `Anna ueberschreibt Owner-Deck per id -> ${r.status}`)
r = await call('GET', '/api/profile', { cookie: annaCookie })
ok(r.status === 200 && r.json?.name === 'Anna' && r.json?.level === 1, `Anna-Profil: ${r.json?.name} Level ${r.json?.level}`)
r = await call('GET', '/api/stats/overview', { cookie: annaCookie })
ok(r.status === 200 && (r.json?.totals?.games ?? 0) === 0, `Anna-Statistik: ${r.json?.totals?.games ?? '?'} Spiele`)

// 9. Nicht-Admin
r = await call('GET', '/api/admin/invites', { cookie: annaCookie })
ok(r.status === 403, `Anna auf /api/admin/invites -> ${r.status}`)

// 10. Belegter Tisch: Owner startet, Anna bekommt 409
r = await call('POST', '/api/games', { cookie: owner, body: { deck: { type: 'random' }, bots: [{ type: 'random' }, { type: 'random' }, { type: 'random' }], tempo: 'BLITZ' } })
ok(r.status === 200 && r.json?.gameId, `Owner startet Spiel -> ${r.status}`)
const gameId = r.json?.gameId
r = await call('POST', '/api/games', { cookie: annaCookie, body: { deck: { type: 'random' }, bots: [], tempo: 'BLITZ' } })
ok(r.status === 409 && r.json?.busy === true, `Anna startet Spiel waehrend Owner spielt -> ${r.status} (${r.json?.error})`)
r = await call('GET', '/api/games/current', { cookie: annaCookie })
ok(r.status === 404, `Anna /api/games/current -> ${r.status}`)
r = await call('GET', '/api/games/current', { cookie: owner })
ok(r.status === 200 && r.json?.gameId === gameId, `Owner /api/games/current -> ${r.status}`)

// 11. WebSocket: ohne Cookie 4401; Anna auf Owner-Spiel 4403 (Node-WebSocket kann keine Cookies setzen -> nur 4401 pruefbar)
const wsClose = (url) =>
  new Promise((resolve) => {
    const ws = new WebSocket(url)
    const t = setTimeout(() => {
      ws.close()
      resolve(-1)
    }, 5000)
    ws.onclose = (ev) => {
      clearTimeout(t)
      resolve(ev.code)
    }
    ws.onerror = () => {}
  })
const code = await wsClose(`${base.replace(/^http/, 'ws')}/ws/game/${gameId}`)
ok(code === 4401, `WS ohne Cookie -> Close ${code}`)

// 12. Rotieren: altes Cookie ungueltig, neuer Code geht
r = await call('POST', `/api/admin/invites/${anna.id}/rotate`, { cookie: owner })
ok(r.status === 200 && r.json?.code, `Anna rotieren -> ${r.status}`)
const annaCode2 = r.json?.code
r = await call('GET', '/api/me', { cookie: annaCookie })
ok(r.status === 401, `Anna mit altem Cookie -> ${r.status}`)
r = await call('POST', '/api/auth/login', { body: { code: annaCode2 } })
ok(r.status === 200, `Anna mit neuem Code -> ${r.status}`)
annaCookie = cookieOf(r.setCookie)
r = await call('GET', '/api/me', { cookie: annaCookie })
ok(r.json?.user?.hasPassword === true && r.json?.user?.email === 'anna@example.de', 'E-Mail/Passwort ueberleben das Rotieren')

// 13. Rate-Limit: spaetestens der 11. Fehlversuch innerhalb einer Minute -> 429
let got429 = false
for (let i = 0; i < 12 && !got429; i++) {
  r = await call('POST', '/api/auth/login', { body: { code: 'AAAA-AAAA-AAAA-AAAA' } })
  got429 = r.status === 429
}
ok(got429, `Rate-Limit greift (429)`)

// 14. Entfernen: Cookie ungueltig, Konto weg
r = await call('DELETE', `/api/admin/invites/${anna.id}`, { cookie: owner })
ok(r.status === 200 && r.json?.deleted === true, `Anna entfernen -> ${r.status}`)
r = await call('GET', '/api/me', { cookie: annaCookie })
ok(r.status === 401, `Anna nach Entfernen -> ${r.status}`)
r = await call('POST', '/api/auth/login', { body: { email: 'anna@example.de', password: 'neues12345' } })
ok(r.status === 401 || r.status === 429, `E-Mail-Login nach Entfernen -> ${r.status} (401, oder 429 wegen Rate-Limit aus Schritt 13)`)
r = await call('GET', '/api/admin/invites', { cookie: owner })
ok(r.status === 200 && !r.json.some((a) => a.id === anna.id), `Einladungsliste ohne Anna (${r.json.length} Eintraege)`)

// Aufraeumen: Owner-Testdeck loeschen
await call('DELETE', `/api/decks/${ownerDeckId}`, { cookie: owner })

console.log(failed === 0 ? '\n=== alles gruen ===' : `\n=== ${failed} Pruefungen fehlgeschlagen ===`)
process.exit(failed === 0 ? 0 : 1)
