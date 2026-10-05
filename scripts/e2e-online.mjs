// End-to-End-Test fuer mehrere Menschen in einem Spiel (E3) gegen eine laufende Dev-Engine im Server-Modus:
//   cd engine; .\gradlew.bat runServer        (Owner-Code DEV-OWNER-CODE, Port 7317)
//   node scripts\e2e-online.mjs
// Ablauf: Owner legt Einladung "Bob" an -> beide loggen sich ein (Cookies) -> Owner startet ein Spiel mit Bob als
// zweitem Menschen (dev-only Feld `humans`) -> zwei WebSocket-Autopiloten spielen -> Bob gibt nach 90 s auf, das
// Spiel laeuft weiter -> Owner gibt nach weiteren 60 s auf oder das Spiel endet -> beide haben gameOver + reward,
// jeder eine eigene games-Zeile.
// Env: MAGELITE_URL (Default http://127.0.0.1:7317), MAGELITE_OWNER_CODE (Default DEV-OWNER-CODE)
const base = process.env.MAGELITE_URL ?? 'http://127.0.0.1:7317'
const ownerCode = process.env.MAGELITE_OWNER_CODE ?? 'DEV-OWNER-CODE'

let failed = 0
const ok = (cond, what) => {
  console.log(`${cond ? 'OK  ' : 'FAIL'} ${what}`)
  if (!cond) failed++
}
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
const cookieOf = (setCookie) => (setCookie ? setCookie.split(';')[0] : null)

// ---- Login Owner, Einladung Bob
let r = await call('POST', '/api/auth/login', { body: { code: ownerCode } })
ok(r.status === 200, `Owner-Login -> ${r.status}`)
const owner = cookieOf(r.setCookie)
const ownerId = r.json?.user?.id
r = await call('POST', '/api/admin/invites', { cookie: owner, body: { name: 'Bob' } })
ok(r.status === 200, `Einladung Bob -> ${r.status}`)
const bobId = r.json?.id
r = await call('POST', '/api/auth/login', { body: { code: r.json?.code } })
ok(r.status === 200, `Bob-Login -> ${r.status}`)
const bob = cookieOf(r.setCookie)

// ---- Spiel mit zwei Menschen
r = await call('POST', '/api/games', { cookie: owner, body: { deck: { type: 'random' }, humans: [{ userId: bobId, name: 'Bob' }], bots: [], tempo: 'BLITZ' } })
ok(r.status === 200 && r.json?.gameId, `Spiel mit 2 Menschen gestartet -> ${r.status} ${r.json?.error ?? ''}`)
const gameId = r.json?.gameId
r = await call('GET', '/api/games/current', { cookie: bob })
ok(r.status === 200 && r.json?.gameId === gameId, `Bob sieht das Spiel als sein laufendes -> ${r.status}`)

/** Autopilot: spielt Laender, passt sonst; zaehlt Nachrichten. */
function pilot(name, cookie) {
  const st = { name, hello: null, last: null, over: null, prompts: 0, foreignPrompts: 0, states: 0, waitingFor: new Set(), closeCode: null, ws: null }
  // Origin wie ein Browser mitschicken: der Server prueft ihn im Server-Modus (ausser --dev)
  const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/ws/game/${gameId}`, { headers: { Cookie: cookie, Origin: base } })
  st.ws = ws
  ws.onclose = (ev) => {
    st.closeCode = ev.code
  }
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data)
    if (m.t === 'hello') st.hello = m
    if (m.t === 'state') {
      st.states++
      st.last = m
      if (st.hello && m.myPlayerId !== st.hello.myPlayerId) st.foreignPrompts++
    }
    if (m.t === 'status' && m.waitingFor) st.waitingFor.add(m.waitingFor)
    if (m.t === 'seat') st.seat = m
    if (m.t === 'gameOver') st.over = m
    if (m.t !== 'prompt') return
    st.prompts++
    let ans
    switch (m.kind) {
      case 'ASK':
        ans = { bool: !m.mulligan }
        break
      case 'SELECT': {
        if (m.mode !== 'priority') {
          ans = { bool: true }
          break
        }
        const land = st.last?.hand.find((c) => st.last.actions?.includes(c.id) && c.types?.includes('LAND'))
        ans = land ? { uuid: land.id } : { bool: false }
        break
      }
      case 'PICK_TARGET':
        ans = m.targets?.length && !m.chosen?.length ? { uuid: m.targets[0] } : { bool: false }
        break
      case 'CHOOSE_CHOICE':
        ans = { str: m.choice.keyed ? m.choice.items[0].key : m.choice.items[0].value }
        break
      case 'AMOUNT':
        ans = { int: m.min ?? 0 }
        break
      case 'MULTI_AMOUNT':
        ans = { str: m.items.map((i) => i.value).join(' ') }
        break
      default:
        ans = m.choices?.length ? { uuid: m.choices[0].id } : { bool: false }
    }
    ws.send(JSON.stringify({ t: 'respond', id: m.id, ...ans }))
  }
  return st
}

const A = pilot('Owner', owner)
const B = pilot('Bob', bob)
await sleep(3000)
ok(A.hello && B.hello, `beide hello: Owner=${!!A.hello} Bob=${!!B.hello}`)
ok(A.hello?.myPlayerId !== B.hello?.myPlayerId, 'verschiedene myPlayerId')
ok(A.hello?.host === true && B.hello?.host === false, `host-Flag: Owner=${A.hello?.host} Bob=${B.hello?.host}`)
ok(A.hello?.seats?.filter((s) => s.human).length === 2, `2 menschliche Sitze im hello (${A.hello?.seats?.filter((s) => s.human).length})`)

// Bob versucht, als Fremder in ein anderes Spiel zu kommen -> es gibt nur dieses; stattdessen: Tempo von Bob ignoriert
B.ws.send(JSON.stringify({ t: 'tempo', preset: 'MAX' }))

// spielen lassen, bis ein paar Zuege vorbei sind (Blitz-Spiele koennen schnell enden), hoechstens 90 s
const t0 = Date.now()
while (Date.now() - t0 < 90000 && !A.over && !B.over && (A.last?.turn ?? 0) < 6) await sleep(1000)
ok(A.prompts > 0 && B.prompts > 0, `beide bekamen Prompts: Owner=${A.prompts} Bob=${B.prompts}`)
ok(A.states > 0 && B.states > 0 && A.foreignPrompts === 0 && B.foreignPrompts === 0, `States je aus eigener Sicht (fremd: ${A.foreignPrompts}/${B.foreignPrompts})`)
ok([...A.waitingFor].includes('Bob') || [...B.waitingFor].includes('Owner'), `Warte-Hinweis auf den anderen Menschen (Owner sah: ${[...A.waitingFor].join(',')}; Bob sah: ${[...B.waitingFor].join(',')})`)
console.log(`     Zug ${A.last?.turn ?? '?'} nach 90 s`)

// Bob gibt auf -> Spiel laeuft fuer Owner weiter; Bob bleibt Zuschauer und bekommt sein gameOver erst am Ende
if (!B.over) {
  B.ws.send(JSON.stringify({ t: 'leave' }))
  await sleep(5000)
}
ok(!B.over, `Bob nach dem Aufgeben noch ohne gameOver (Zuschauer): ${B.over ? 'FEHLER, hat ' + B.over.result : 'ok'}`)
ok(B.last?.players?.find((p) => p.id === B.hello?.myPlayerId)?.lost === true, 'Bob ist im State als ausgeschieden markiert')
ok(B.seat?.conceded === true && !A.seat, `seat-Nachricht: Bob conceded=${B.seat?.conceded}, Owner keine`)
r = await call('GET', '/api/games/current', { cookie: bob })
ok(r.status === 404, `Bob /api/games/current nach Aufgeben -> ${r.status} (kein Rueckholen beim Neuladen)`)
r = await call('GET', '/api/games/current', { cookie: owner })
ok(r.status === 200, `Owner /api/games/current weiterhin -> ${r.status}`)
r = await call('GET', '/api/health')
ok(r.json?.games === 1, `Spiel laeuft fuer Owner weiter (games=${r.json?.games})`)
const turnAtLeave = A.last?.turn ?? 0
await sleep(15000)
ok((A.last?.turn ?? 0) >= turnAtLeave && !A.over, `Owner spielt weiter (Zug ${A.last?.turn}), kein gameOver`)

// Owner gibt auf -> alle Menschen weg -> Bots geben auf -> Spiel endet
A.ws.send(JSON.stringify({ t: 'leave' }))
const t2 = Date.now()
while (Date.now() - t2 < 30000 && !A.over) await sleep(500)
ok(!!A.over, `Owner gameOver nach Aufgeben: ${A.over ? A.over.result : 'FEHLT'}`)
r = await call('GET', '/api/health')
ok(r.json?.games === 0, `kein laufendes Spiel mehr (games=${r.json?.games})`)

// Belohnung + eigene games-Zeilen
ok(A.over?.reward && B.over?.reward, `reward fuer beide: Owner=${!!A.over?.reward} Bob=${!!B.over?.reward}`)
const hA = await call('GET', '/api/history?limit=3', { cookie: owner })
const hB = await call('GET', '/api/history?limit=3', { cookie: bob })
const rowA = hA.json?.find((g) => g.id === gameId)
const rowB = hB.json?.find((g) => g.id === gameId)
ok(rowA && rowB, `je eine games-Zeile: Owner=${!!rowA} Bob=${!!rowB}`)
ok(rowB?.endReason === 'concede', `Bob end_reason=${rowB?.endReason}`)
ok(rowA?.seats?.length === 4 && rowA.seats.filter((s) => s.human).length === 2, `game_seats: ${rowA?.seats?.length} Sitze, ${rowA?.seats?.filter((s) => s.human).length} Menschen`)
const pB = await call('GET', '/api/profile', { cookie: bob })
ok(pB.json?.games === 1, `Bob-Profil zaehlt ${pB.json?.games} Spiel`)

// Aufraeumen
await call('DELETE', `/api/admin/invites/${bobId}`, { cookie: owner })
A.ws.close()
B.ws.close()
console.log(failed === 0 ? '\n=== alles gruen ===' : `\n=== ${failed} Pruefungen fehlgeschlagen ===`)
process.exit(failed === 0 ? 0 : 1)
