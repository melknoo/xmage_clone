// End-to-End-Test fuer Lobby-Chat, Freunde und Tisch-Einladungen gegen eine Dev-Engine im Server-Modus:
//   isolierte Engine im Server-Modus starten (nie Port 7317), dann
//   MAGELITE_URL=http://127.0.0.1:7401 MAGELITE_OWNER_CODE=DEV-OWNER-CODE node scripts/e2e-social.mjs
// Ablauf: Owner, Bob, Carla melden sich an -> Lobby-Chat (Cursor, Kuerzung, Rate-Limit, Verlassen/Beitreten)
// -> Freundschaft per Name/ID, Gegenanfrage, Ablehnen -> Status online/am Tisch -> Einladung an Freund/Nicht-Freund,
// Beitritt erledigt die Einladung, Ablehnen, voller Tisch filtert Einladungen. Dazu Poll-Felder now/online/tables/
// myTable/sent, Systemzeile beim Wiedereintritt und die Fehlertexte.
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
const poll = async (cookie, after = 0) => (await call('GET', `/api/social?after=${after}`, { cookie })).json

// ---- Konten (eindeutige Namen, damit "per Name" nicht mehrdeutig wird)
const tag = String(Date.now() % 100000)
let r = await call('POST', '/api/auth/login', { body: { code: ownerCode } })
const owner = cookieOf(r.setCookie)
const me = (await call('GET', '/api/me', { cookie: owner })).json?.user
r = await call('POST', '/api/admin/invites', { cookie: owner, body: { name: `Bob${tag}` } })
const bobId = r.json?.id
const bobName = `Bob${tag}`
r = await call('POST', '/api/auth/login', { body: { code: r.json?.code } })
const bob = cookieOf(r.setCookie)
r = await call('POST', '/api/admin/invites', { cookie: owner, body: { name: `Carla${tag}` } })
const carlaId = r.json?.id
r = await call('POST', '/api/auth/login', { body: { code: r.json?.code } })
const carla = cookieOf(r.setCookie)
ok(owner && bob && carla && me?.id, 'Owner, Bob, Carla angemeldet')

// ---- Lobby-Chat
let s = await poll(owner)
ok(s && s.chatIn === true && Array.isArray(s.msgs) && Array.isArray(s.friends), `Poll Owner: im Chat, ${s?.msgs?.length} Nachrichten`)
await poll(bob)
await poll(carla)
const seq0 = s.seq
r = await call('POST', '/api/social/chat', { cookie: owner, body: { text: 'Hallo Lobby' } })
ok(r.status === 200 && r.json?.seq === seq0 + 1 && r.json?.name === me.name, `Owner schreibt -> ${r.status} seq=${r.json?.seq}`)
s = await poll(bob, seq0)
ok(s.msgs.length === 1 && s.msgs[0].text === 'Hallo Lobby', `Bob bekommt nur die neue Nachricht (${s.msgs.length})`)
ok(s.members.some((m) => m.id === me.id) && s.members.some((m) => m.id === bobId) && s.members.some((m) => m.id === carlaId), `Mitglieder: ${s.members.length} (Owner, Bob, Carla dabei)`)
ok(typeof s.now === 'number' && Math.abs(s.now - Date.now()) < 60000, `Poll: now=${s.now}`)
ok(s.online === s.members.length && s.online >= 3, `Poll: online=${s.online} (= sichtbare Mitglieder)`)
ok(typeof s.tables === 'number' && s.myTable === null && Array.isArray(s.sent) && s.sent.length === 0, `Poll: tables=${s.tables}, myTable=${JSON.stringify(s.myTable)}, sent=${s.sent?.length}`)
r = await call('POST', '/api/social/chat', { cookie: bob, body: { text: 'z'.repeat(301) } })
ok(r.status === 200 && r.json?.text?.length === 300, `lange Nachricht gekuerzt (${r.json?.text?.length})`)
r = await call('POST', '/api/social/chat', { cookie: bob, body: { text: '   ' } })
ok(r.status === 409, `leere Nachricht -> ${r.status}`)
let limited = 0
for (let i = 0; i < 6; i++) {
  r = await call('POST', '/api/social/chat', { cookie: carla, body: { text: `spam ${i}` } })
  if (r.status === 409) limited++
}
ok(limited >= 1, `Rate-Limit greift (${limited}x 409)`)

// Verlassen: unsichtbar, keine Nachrichten, Senden verboten; Einstellung bleibt nach neuem Login
r = await call('PUT', '/api/social/chat', { cookie: carla, body: { in: false } })
ok(r.status === 200 && r.json?.in === false, 'Carla verlaesst den Chat')
s = await poll(carla, 0)
ok(s.chatIn === false && s.msgs.length === 0, `Carla: chatIn=false, ${s.msgs.length} Nachrichten`)
s = await poll(owner, 0)
ok(!s.members.some((m) => m.id === carlaId), 'Carla nicht mehr in der Mitgliederliste')
ok(s.online === s.members.length, `unsichtbare Carla zaehlt nicht als online (${s.online})`)
r = await call('POST', '/api/social/chat', { cookie: carla, body: { text: 'bin weg' } })
ok(r.status === 409, `Senden ausserhalb des Chats -> ${r.status}`)
r = await call('POST', `/api/admin/invites/${carlaId}/rotate`, { cookie: owner })
const carla2 = r.json?.code ? cookieOf((await call('POST', '/api/auth/login', { body: { code: r.json.code } })).setCookie) : null
if (carla2) {
  s = await poll(carla2, 0)
  ok(s.chatIn === false, 'Einstellung "verlassen" ueberlebt neuen Login')
}
const carlaC = carla2 ?? carla
const seqBefore = (await poll(owner, 0)).seq
r = await call('PUT', '/api/social/chat', { cookie: carlaC, body: { in: true } })
s = await poll(carlaC, 0)
ok(s.chatIn === true && s.msgs.length >= 2, `Carla wieder drin, sieht Verlauf (${s.msgs.length})`)
{
  const sys = (await poll(owner, seqBefore)).msgs.filter((m) => m.sys)
  ok(sys.length === 1 && sys[0].userId === 0 && sys[0].text === `Carla${tag} ist dem Lobby-Chat beigetreten`, `Systemzeile: ${JSON.stringify(sys[0]?.text)}`)
  await call('PUT', '/api/social/chat', { cookie: carlaC, body: { in: false } })
  await call('PUT', '/api/social/chat', { cookie: carlaC, body: { in: true } })
  await call('PUT', '/api/social/chat', { cookie: carlaC, body: { in: true } })
  const sys2 = (await poll(owner, seqBefore)).msgs.filter((m) => m.sys)
  ok(sys2.length === 1, `Systemzeile begrenzt (nach erneutem Aus/An: ${sys2.length})`)
  const normal = (await poll(owner, 0)).msgs.find((m) => !m.sys)
  ok(normal && normal.sys === undefined, 'normale Nachricht ohne sys-Feld')
}

// ---- Freunde
r = await call('POST', '/api/friends', { cookie: owner, body: { name: bobName.toLowerCase() } })
ok(r.status === 200 && r.json?.state === 'outgoing', `Owner -> Anfrage an Bob per Name (klein) -> ${r.json?.state}`)
r = await call('POST', '/api/friends', { cookie: owner, body: { name: bobName } })
ok(r.status === 409 && r.json?.error === `Anfrage an ${bobName} läuft bereits.`, `doppelte Anfrage -> ${r.status} ${r.json?.error}`)
r = await call('POST', '/api/friends', { cookie: owner, body: { name: 'gibtsnicht-xyz' } })
ok(r.status === 409 && r.json?.error === 'Kein Konto mit dem Namen „gibtsnicht-xyz“.', `unbekannter Name -> ${r.status} ${r.json?.error}`)
r = await call('POST', '/api/friends', { cookie: owner, body: { userId: me.id } })
ok(r.status === 409, `sich selbst -> ${r.status}`)
s = await poll(bob)
ok(s.incoming.some((x) => x.userId === me.id), 'Bob sieht eingehende Anfrage')
s = await poll(carlaC)
ok(!s.incoming.length && !s.outgoing.length, 'Carla sieht Bobs Anfragen nicht')
r = await call('POST', `/api/friends/${me.id}/accept`, { cookie: carlaC })
ok(r.status === 409, `Carla kann fremde Anfrage nicht annehmen -> ${r.status}`)
r = await call('POST', `/api/friends/${bobId}/accept`, { cookie: owner })
ok(r.status === 409, `eigene Anfrage nicht selbst annehmen -> ${r.status}`)
r = await call('POST', `/api/friends/${me.id}/accept`, { cookie: bob })
ok(r.status === 200, `Bob nimmt an -> ${r.status}`)
s = await poll(owner)
let bobF = s.friends.find((f) => f.id === bobId)
ok(bobF && bobF.status === 'online', `Owner: Bob ist Freund, Status ${bobF?.status}`)
r = await call('POST', '/api/friends', { cookie: owner, body: { userId: bobId } })
ok(r.status === 409 && r.json?.error === `${bobName} ist schon dein Freund.`, `schon befreundet -> ${r.status} ${r.json?.error}`)

// Gegenanfrage = annehmen (Carla -> Bob, Bob -> Carla)
r = await call('POST', '/api/friends', { cookie: carlaC, body: { userId: bobId } })
ok(r.json?.state === 'outgoing', 'Carla -> Anfrage an Bob per ID')
r = await call('POST', '/api/friends', { cookie: bob, body: { userId: carlaId } })
ok(r.json?.state === 'friend', `Bob -> Carla (Gegenanfrage) -> ${r.json?.state}`)
r = await call('DELETE', `/api/friends/${carlaId}`, { cookie: bob })
ok(r.status === 200, 'Bob entfernt Carla wieder')
s = await poll(carlaC)
ok(!s.friends.some((f) => f.id === bobId), 'Carla: Bob nicht mehr Freund')
r = await call('DELETE', `/api/friends/${bobId}`, { cookie: carlaC })
ok(r.status === 409, `nichts zu entfernen -> ${r.status}`)

// ---- Einladungen
r = await call('POST', '/api/tables', { cookie: owner, body: { name: 'Einladungsrunde' } })
ok(r.status === 200, `Owner eroeffnet Tisch -> ${r.status}`)
const tid = r.json?.id
r = await call('POST', `/api/tables/${tid}/invite`, { cookie: owner, body: { userId: carlaId } })
ok(r.status === 409, `Einladung an Nicht-Freund -> ${r.status}`)
r = await call('POST', `/api/tables/${tid}/invite`, { cookie: bob, body: { userId: me.id } })
ok(r.status === 409, `Bob (sitzt nicht) laedt ein -> ${r.status}`)
r = await call('POST', `/api/tables/${tid}/invite`, { cookie: owner, body: { userId: bobId } })
ok(r.status === 200 && r.json?.tableId === tid, `Owner laedt Bob ein -> ${r.status}`)
const inv1 = r.json?.id
ok(r.json?.expiresAt - r.json?.ts === 600000, `Einladung laeuft 10 min (${r.json?.expiresAt - r.json?.ts} ms)`)
s = await poll(bob)
ok(s.invites.length === 1 && s.invites[0].tableId === tid && s.invites[0].fromName === me.name, `Bob sieht Einladung (${s.invites.length})`)
ok(s.invites[0]?.expiresAt > s.now && s.invites[0]?.humans === 1 && s.invites[0]?.tempo === 'NORMAL', `Einladung: expiresAt, humans=${s.invites[0]?.humans}, tempo=${s.invites[0]?.tempo}`)
s = await poll(owner)
ok(s.sent.length === 1 && s.sent[0].toUserId === bobId && s.sent[0].toName === bobName && s.sent[0].tableId === tid && s.sent[0].expiresAt > s.now,
  `Owner: sent=${JSON.stringify(s.sent)}`)
ok(s.myTable?.id === tid && s.myTable.name === 'Einladungsrunde' && s.myTable.host === true && s.myTable.invitable === true
  && s.myTable.humans === 1 && s.myTable.state === 'LOBBY' && s.myTable.tempo === 'NORMAL' && s.tables >= 1, `Owner: myTable=${JSON.stringify(s.myTable)}, tables=${s.tables}`)
s = await poll(carlaC)
ok(s.invites.length === 0, 'Carla sieht keine Einladung')
r = await call('DELETE', `/api/social/invites/${inv1}`, { cookie: carlaC })
ok(r.status === 409, `Carla kann Bobs Einladung nicht ablehnen -> ${r.status}`)
r = await call('DELETE', `/api/social/invites/${inv1}`, { cookie: bob })
ok(r.status === 200, 'Bob lehnt ab')
s = await poll(bob)
ok(s.invites.length === 0, 'Einladung weg')
r = await call('POST', `/api/tables/${tid}/invite`, { cookie: owner, body: { userId: bobId } })
s = await poll(owner)
bobF = s.friends.find((f) => f.id === bobId)
ok(bobF?.status === 'online', `Bob noch nicht am Tisch: ${bobF?.status}`)
r = await call('POST', `/api/tables/${tid}/join`, { cookie: bob })
ok(r.status === 200, 'Bob tritt bei')
s = await poll(bob)
ok(s.invites.length === 0, 'Beitritt erledigt die Einladung')
ok(s.myTable?.id === tid && s.myTable.host === false && s.myTable.humans === 2, `Bob: myTable host=${s.myTable?.host} humans=${s.myTable?.humans}`)
ok((await poll(owner)).sent.length === 0, 'Owner: erledigte Einladung nicht mehr unter sent')
s = await poll(owner)
bobF = s.friends.find((f) => f.id === bobId)
ok(bobF?.status === 'table' && bobF?.tableId === tid, `Status am Tisch: ${bobF?.status} ${bobF?.tableId}`)
r = await call('POST', `/api/tables/${tid}/invite`, { cookie: owner, body: { userId: bobId } })
ok(r.status === 409, `sitzt schon -> ${r.status}`)

// Voller Tisch filtert Einladungen: Owner+Carla befreunden, einladen, Tisch mit Bots fuellen
await call('POST', '/api/friends', { cookie: owner, body: { userId: carlaId } })
await call('POST', `/api/friends/${me.id}/accept`, { cookie: carlaC })
r = await call('POST', `/api/tables/${tid}/invite`, { cookie: owner, body: { userId: carlaId } })
ok(r.status === 200, 'Owner laedt Carla ein')
ok((await poll(carlaC)).invites.length === 1, 'Carla sieht Einladung')
await call('PUT', `/api/tables/${tid}/seats/2`, { cookie: owner, body: { kind: 'BOT' } })
await call('PUT', `/api/tables/${tid}/seats/3`, { cookie: owner, body: { kind: 'BOT' } })
ok((await poll(carlaC)).invites.length === 0, 'voller Tisch: Einladung ausgeblendet')
r = await call('POST', `/api/tables/${tid}/invite`, { cookie: owner, body: { userId: carlaId } })
ok(r.status === 409, `Einladen an vollen Tisch -> ${r.status}`)

// Aufraeumen
await call('POST', `/api/tables/${tid}/leave`, { cookie: owner })
await call('DELETE', `/api/friends/${bobId}`, { cookie: owner })
await call('DELETE', `/api/friends/${carlaId}`, { cookie: owner })
await call('DELETE', `/api/admin/invites/${bobId}`, { cookie: owner })
await call('DELETE', `/api/admin/invites/${carlaId}`, { cookie: owner })
console.log(failed === 0 ? '\n=== alles gruen ===' : `\n=== ${failed} Pruefungen fehlgeschlagen ===`)
process.exit(failed === 0 ? 0 : 1)
