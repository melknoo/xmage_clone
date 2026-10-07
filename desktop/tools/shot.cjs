// Entwickler-Werkzeug: oeffnet eine URL, fuehrt Schritte aus und speichert Screenshots.
// Aufruf (Git Bash, aus desktop/):  env -u ELECTRON_RUN_AS_NODE npx electron tools/shot.cjs tools/<steps>.json
//
// steps.json (alle Felder optional ausser url):
//   url, width (1680), height (1000), timeout (120000 ms fuer den ganzen Lauf)
//   show: true        sichtbares Fenster (showInactive, stiehlt keinen Fokus). Noetig fuer Screen-Wechsel mit
//                     AnimatePresence und fuer das Social-Polling (pausiert bei document.hidden).
//   exact: true       Inhalt exakt width x height (useContentSize, kein Menue, force-device-scale-factor=1);
//                     wird nach dem Laden geprueft (Abweichung = Fehler).
//   freshSession      In-Memory-Partition (keine Cookies/localStorage aus frueheren Laeufen). Standard = exact.
//   background        Fensterfarbe (Standard #0d0c0b bei exact, sonst #07090f)
//   vars: {NAME: wert} Vorgaben fuer Platzhalter, per Umgebungsvariable SHOT_<NAME> ueberschreibbar.
//
// Platzhalter in allen Strings (url, js, jsFile-Inhalt, shot, goto, click, ...):
//   {{UI}}   SHOT_UI    (http://localhost:5173)   {{PORT}}  SHOT_PORT  (7400)
//   {{OUT}}  SHOT_OUT   (<repo>/design/redesign-shots/app)
//   {{PROTO}} SHOT_PROTO (http://127.0.0.1:8777)  {{SEED}}  SHOT_SEED  (http://127.0.0.1:7499, social-seed-Steuerung)
//   {{REPO}} SHOT_REPO  (Repo-Wurzel mit /)
//   {{OWNER_CODE}} MAGELITE_OWNER_CODE (DEV-OWNER-CODE) - nur fuer 127.0.0.1/localhost, wird in Logs maskiert.
// Sperre: Port 7317 und *.fly.dev werden abgelehnt (Exit 3), ausser SHOT_ALLOW_LIVE=1.
//
// Schritte (ein Objekt darf mehrere Teile kombinieren; Reihenfolge wie hier):
//   {"wait": ms}
//   {"size": [w, h]}                       Inhaltsgroesse aendern (danach 500 ms Ruhe)
//   {"network": "offline"|"online"}        Netzwerk-Emulation der Session
//   {"goto": "url"}                        neue URL laden
//   {"localStorage": {k: v}, "reload": true}  setzen (Objekte als JSON), optional neu laden
//   {"reload": true}
//   {"waitFor": "css" | Ziel, "timeout": 30000}       Ziel siehe click; Fehler, wenn nicht gefunden
//   {"waitGone": "css" | Ziel, "timeout": 30000}      wartet, bis nichts mehr passt
//   {"hover": Ziel} / {"click": Ziel, "shift": true, "double": true}   echte Maus-Events (sendInputEvent)
//   {"type": {"target": Ziel, "text": "...", "clear": true}}          echte Tastatur-Zeichen
//   {"key": "Escape"|"Tab"|"Space"|"Enter"|"F2"|"F4"|"F5"|"F9"|"F10"|..., "shift": true}
//   {"js": "...", "expect": "regex"} / {"jsFile": "x.js", "args": {...}}   Ergebnis wird geloggt; Fehler bei
//        Exception, bei Nicht-Treffer von expect bzw. ohne expect bei /^(kein|Timeout)/.
//        args: window.__args (Werte "@file:pfad.json" werden als JSON eingelesen).
//   {"shot": "pfad.png", "clip": "css" | {x,y,width,height}}   Screenshot (optional nur ein Ausschnitt)
//   "optional": true                       Fehler dieses Schritts zaehlen nicht
//   "name": "..."                          Bezeichnung im Log
// Ziel = CSS-Selektor (String) oder {sel, text, re, i, prefix, within, nth, pointer, visible, js}:
//   text = exakter (normalisierter) textContent, prefix = startsWith, re = RegExp, i = ohne Gross/Klein,
//   within = Container-Selektor, pointer = nur Elemente mit cursor:pointer, nth = Index (Standard 0),
//   js = per element.click() statt echter Maus (z. B. fuer sr-only-Checkboxen). Bei Text gewinnt das tiefste Element.
// Relative Pfade (jsFile, shot, @file) beziehen sich auf den Ordner der steps.json.
// Ende: Zusammenfassung; Exit 0 = alles ok, 1 = Fehler, 2 = Gesamt-Timeout, 3 = gesperrte URL.
const { app, BrowserWindow, Menu } = require('electron')
const fs = require('node:fs')
const path = require('node:path')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const out = (s) => process.stdout.write(s + '\n')

const file = process.argv[process.argv.length - 1]
const spec = JSON.parse(fs.readFileSync(file, 'utf8'))
const base = path.dirname(path.resolve(file))
const rel = (p) => (path.isAbsolute(p) ? p : path.join(base, p))
const exact = !!spec.exact
if (exact) app.commandLine.appendSwitch('force-device-scale-factor', '1')
// Verdeckte/inaktive Fenster nicht drosseln: sonst kommen Eingaben, Timer und Frames nicht an (Windows-Okklusion)
app.commandLine.appendSwitch('disable-features', 'CalculateNativeWinOcclusion')
app.commandLine.appendSwitch('disable-renderer-backgrounding')
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows')
app.commandLine.appendSwitch('disable-background-timer-throttling')

const repo = path.resolve(__dirname, '..', '..')
const ownerCode = process.env.MAGELITE_OWNER_CODE || 'DEV-OWNER-CODE'
const allowLive = process.env.SHOT_ALLOW_LIVE === '1'
const defaults = {
  UI: 'http://localhost:5173',
  PORT: '7400',
  OUT: path.join(repo, 'design', 'redesign-shots', 'app').replace(/\\/g, '/'),
  PROTO: 'http://127.0.0.1:8777',
  SEED: 'http://127.0.0.1:7499',
  REPO: repo.split(path.sep).join('/'),
  ...(spec.vars ?? {}),
}
const vars = {}
for (const k of Object.keys(defaults)) vars[k] = process.env['SHOT_' + k] ?? String(defaults[k])

const mask = (s) => String(s).split(ownerCode).join('***')
const isLocalUrl = (u) => {
  try {
    const h = new URL(u).hostname
    return h === '127.0.0.1' || h === 'localhost'
  } catch {
    return false
  }
}
let currentUrl = ''
function subst(str, targetUrl) {
  if (typeof str !== 'string') return str
  let s = str.replace(/\{\{(UI|PORT|OUT|PROTO|SEED|REPO)\}\}/g, (_m, k) => vars[k])
  if (s.includes('{{OWNER_CODE}}')) {
    // Owner-Code nur fuer lokale Ziele einsetzen (Ziel-URL bzw. aktuelle Seite)
    if (!isLocalUrl(targetUrl ?? s.match(/https?:\/\/[^\s'"]+/)?.[0] ?? currentUrl)) throw new Error('{{OWNER_CODE}} nur fuer 127.0.0.1/localhost')
    s = s.split('{{OWNER_CODE}}').join(ownerCode)
  }
  return s
}
function substDeep(v, targetUrl) {
  if (typeof v === 'string') return subst(v, targetUrl)
  if (Array.isArray(v)) return v.map((x) => substDeep(x, targetUrl))
  if (v && typeof v === 'object') {
    const o = {}
    for (const [k, x] of Object.entries(v)) o[k] = substDeep(x, targetUrl)
    return o
  }
  return v
}
const LIVE = /(:|port=)7317\b|\.fly\.dev/i
function guard(text, what) {
  if (!allowLive && LIVE.test(text)) {
    out(`GESPERRT (${what}): Port 7317 bzw. *.fly.dev nur mit SHOT_ALLOW_LIVE=1`)
    app.exit(3)
    return false
  }
  return true
}

// ---- Seiten-Helfer: Ziel finden (String = CSS, Objekt = Text-Suche) -> {ok, x, y, w, h, n, tag}
const FIND = `(t, act) => {
  if (typeof t === 'string') t = { sel: t }
  const norm = (s) => (s || '').replace(/\\s+/g, ' ').trim()
  const vis = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' }
  const roots = t.within ? [...document.querySelectorAll(t.within)] : [document]
  let c = []
  for (const r of roots) c.push(...r.querySelectorAll(t.sel || '*'))
  if (t.text != null || t.re) {
    const want = t.text != null ? (t.i ? norm(t.text).toLowerCase() : norm(t.text)) : null
    const rx = t.re ? new RegExp(t.re, t.i ? 'i' : '') : null
    c = c.filter((el) => { let s = norm(el.textContent); if (t.i) s = s.toLowerCase(); return rx ? rx.test(s) : t.prefix ? s.startsWith(want) : s === want })
    c = c.filter((el) => !c.some((o) => o !== el && el.contains(o)))
  }
  if (t.visible !== false && !t.js) c = c.filter(vis)
  if (t.pointer) c = c.filter((el) => getComputedStyle(el).cursor === 'pointer')
  const el = c[t.nth || 0]
  if (!el) return { ok: false, n: c.length }
  if (act === 'click' && t.js) { el.click(); return { ok: true, n: c.length, js: true } }
  if (act) el.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  const r = el.getBoundingClientRect()
  return { ok: true, n: c.length, x: r.left + r.width / 2, y: r.top + r.height / 2, left: r.left, top: r.top, w: r.width, h: r.height, tag: el.tagName }
}`

let win
let failures = []
let shots = 0
let stepNo = 0
let summarized = false

function summary(code) {
  if (summarized) return
  summarized = true
  out(`--- Zusammenfassung ${path.basename(file)}: ${stepNo} Schritte, ${shots} Screenshots, ${failures.length} Fehler`)
  for (const f of failures) out(`  FEHLER #${f.i}${f.name ? ' (' + f.name + ')' : ''}: ${f.msg}`)
  app.exit(code ?? (failures.length ? 1 : 0))
}

async function find(target, act) {
  return win.webContents.executeJavaScript(`(${FIND})(${JSON.stringify(target)}, ${JSON.stringify(act ?? null)})`)
}

async function waitFind(target, timeout, gone) {
  const until = Date.now() + (timeout ?? 30000)
  let r
  for (;;) {
    r = await find(target)
    if (gone ? !r.ok : r.ok) return { ok: true, r }
    if (Date.now() > until) return { ok: false, r }
    await sleep(250)
  }
}

function waitLoad() {
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(t)
      win.webContents.removeListener('did-finish-load', done)
      win.webContents.removeListener('did-fail-load', done)
      resolve()
    }
    const t = setTimeout(done, 30000)
    win.webContents.once('did-finish-load', done)
    win.webContents.once('did-fail-load', done)
  })
}

async function load(url) {
  if (!guard(url, 'url')) return
  currentUrl = url
  try {
    await win.loadURL(url)
  } catch (e) {
    out(mask(`load error: ${e}`)) // z. B. ERR_ABORTED bei Hash-Navigation - Seite laeuft trotzdem
  }
  await sleep(500)
}

async function checkSize(w, h) {
  const [iw, ih, dpr] = await win.webContents.executeJavaScript('[innerWidth, innerHeight, devicePixelRatio]')
  const ok = iw === w && ih === h && dpr === 1
  out(`viewport ${iw}x${ih} @${dpr}${ok ? '' : ` (erwartet ${w}x${h} @1)`}`)
  return ok
}

async function mouse(target, opts) {
  const r = await find(target, opts.click ? 'click' : 'hover')
  if (!r.ok) return `kein Ziel ${JSON.stringify(target)} (${r.n} Treffer)`
  if (r.js) return `click(js) ${r.n > 1 ? `[${r.n} Treffer]` : ''}`
  await sleep(60)
  const again = await find(target, 'hover') // Position nach scrollIntoView
  const x = Math.round(again.x ?? r.x)
  const y = Math.round(again.y ?? r.y)
  const modifiers = opts.shift ? ['shift'] : []
  const wc = win.webContents
  primeInput()
  wc.sendInputEvent({ type: 'mouseMove', x, y, modifiers })
  if (opts.click) {
    await sleep(40)
    const clicks = opts.double ? 2 : 1
    for (let i = 1; i <= clicks; i++) {
      wc.sendInputEvent({ type: 'mouseDown', x, y, button: 'left', clickCount: i, modifiers })
      await sleep(30)
      wc.sendInputEvent({ type: 'mouseUp', x, y, button: 'left', clickCount: i, modifiers })
      await sleep(30)
    }
  }
  await sleep(150) // Event-Verarbeitung + React-Render abwarten
  return `${opts.click ? 'click' : 'hover'} ${r.tag} @${x},${y}${r.n > 1 ? ` [${r.n} Treffer]` : ''}`
}

// Ohne vorheriges Tastatur-Event kommen Maus-Events in einem nie fokussierten (showInactive) Fenster nicht an;
// ein harmloses keyUp aktiviert die Eingabe-Weiterleitung, ohne der Seite etwas Sinnvolles zu schicken.
function primeInput() {
  if (process.env.SHOT_PRIME === 'focus') win.webContents.focus()
  win.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Shift' })
  win.webContents.sendInputEvent({ type: 'keyUp', keyCode: 'Shift' })
}

const KEYS = { Esc: 'Escape', Return: 'Enter', ' ': 'Space' }
async function key(k, shift) {
  const keyCode = KEYS[k] ?? k
  const modifiers = shift ? ['shift'] : []
  const wc = win.webContents
  wc.sendInputEvent({ type: 'keyDown', keyCode, modifiers })
  if (keyCode === 'Space') wc.sendInputEvent({ type: 'char', keyCode: ' ', modifiers })
  else if (keyCode === 'Enter') wc.sendInputEvent({ type: 'char', keyCode: '\r', modifiers })
  else if (keyCode.length === 1) wc.sendInputEvent({ type: 'char', keyCode, modifiers })
  await sleep(30)
  wc.sendInputEvent({ type: 'keyUp', keyCode, modifiers })
  await sleep(120)
}

async function typeText(step) {
  const t = step.type
  const r = await mouse(t.target, { click: true })
  if (r.startsWith('kein')) return r
  if (t.clear !== false) {
    await win.webContents.executeJavaScript(
      `(() => { const a = document.activeElement; if (a && 'select' in a) a.select(); return true })()`,
    )
    win.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Backspace' })
    win.webContents.sendInputEvent({ type: 'keyUp', keyCode: 'Backspace' })
  }
  for (const ch of String(t.text)) {
    win.webContents.sendInputEvent({ type: 'char', keyCode: ch })
    await sleep(15)
  }
  return `getippt (${String(t.text).length} Zeichen)`
}

function readArgs(args) {
  if (!args) return null
  const o = {}
  for (const [k, v] of Object.entries(args)) {
    o[k] = typeof v === 'string' && v.startsWith('@file:') ? JSON.parse(fs.readFileSync(rel(v.slice(6)), 'utf8')) : v
  }
  return o
}

async function clipRect(clip) {
  if (!clip) return null
  let r = clip
  if (typeof clip === 'string' || clip.sel || clip.text) {
    const f = await find(clip)
    if (!f.ok) throw new Error(`clip: kein Element ${JSON.stringify(clip)}`)
    r = { x: f.left, y: f.top, width: f.w, height: f.h }
  }
  const frac = [r.x, r.y, r.width, r.height].some((v) => Math.abs(v - Math.round(v)) > 0.01)
  if (frac) out(`WARNUNG clip nicht ganzzahlig: ${JSON.stringify(r)}`)
  return { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height) }
}

// capturePage wirft gelegentlich "UnknownVizError" - neu zeichnen und bis zu 3x wiederholen, zuletzt mit Rechteck
async function capture(rect) {
  let last
  for (let i = 0; i < 4; i++) {
    try {
      if (rect) return await win.webContents.capturePage(rect)
      if (i < 2) return await win.webContents.capturePage()
      const [w, h] = await win.webContents.executeJavaScript('[innerWidth, innerHeight]')
      return await win.webContents.capturePage({ x: 0, y: 0, width: w, height: h })
    } catch (e) {
      last = e
      win.webContents.invalidate()
      await sleep(600)
    }
  }
  throw last
}

async function runStep(raw) {
  const step = substDeep(raw, raw.goto ? undefined : currentUrl)
  const msgs = []
  let fail = null
  const failIf = (m) => {
    if (!fail) fail = m
  }

  if (step.wait) await sleep(step.wait)
  if (step.size) {
    const [w, h] = step.size
    win.setContentSize(w, h)
    await sleep(500)
    if (!(await checkSize(w, h)) && exact) failIf(`Groesse ${w}x${h} nicht erreicht`)
  }
  if (step.network) {
    const ses = win.webContents.session
    if (step.network === 'offline') ses.enableNetworkEmulation({ offline: true })
    else ses.disableNetworkEmulation()
    msgs.push(`network ${step.network}`)
  }
  if (step.goto) {
    await load(step.goto)
    msgs.push('goto')
  }
  if (step.localStorage) {
    const entries = Object.entries(step.localStorage).map(([k, v]) => [k, typeof v === 'string' ? v : JSON.stringify(v)])
    await win.webContents.executeJavaScript(
      `(() => { for (const [k, v] of ${JSON.stringify(entries)}) localStorage.setItem(k, v); return true })()`,
    )
    msgs.push(`localStorage ${entries.map((e) => e[0]).join(',')}`)
  }
  if (step.reload) {
    const p = waitLoad()
    win.webContents.reload()
    await p
    await sleep(500)
    msgs.push('reload')
  }
  if (step.waitFor || step.waitGone) {
    const target = step.waitFor ?? step.waitGone
    const r = await waitFind(target, step.timeout, !!step.waitGone)
    msgs.push(`${step.waitFor ? 'waitFor' : 'waitGone'} ${typeof target === 'string' ? target : JSON.stringify(target)}: ${r.ok}`)
    if (!r.ok) failIf(`${step.waitFor ? 'waitFor' : 'waitGone'} ${JSON.stringify(target)} nach ${step.timeout ?? 30000} ms`)
  }
  if (step.hover) {
    const m = await mouse(step.hover, { click: false })
    msgs.push(m)
    if (m.startsWith('kein')) failIf(m)
  }
  if (step.click) {
    const m = await mouse(step.click, { click: true, shift: step.shift, double: step.double })
    msgs.push(m)
    if (m.startsWith('kein')) failIf(m)
  }
  if (step.type) {
    const m = await typeText(step)
    msgs.push(m)
    if (m.startsWith('kein')) failIf(m)
  }
  if (step.key) {
    await key(step.key, step.shift)
    msgs.push(`key ${step.key}`)
  }
  const code = step.js ?? (step.jsFile ? subst(fs.readFileSync(rel(step.jsFile), 'utf8'), currentUrl) : null)
  if (code) {
    if (!guard(code, 'js')) return
    const args = readArgs(step.args)
    const full = args ? `window.__args = ${JSON.stringify(args)};\n${code}` : code
    const r = await win.webContents.executeJavaScript(full)
    const txt = r === undefined ? '' : typeof r === 'string' ? r : JSON.stringify(r)
    if (r !== undefined) msgs.push(`js: ${txt}`)
    if (step.expect) {
      if (!new RegExp(step.expect).test(txt)) failIf(`erwartet /${step.expect}/, bekam ${txt.slice(0, 300)}`)
    } else if (/^(kein|Timeout)/.test(txt)) failIf(txt.slice(0, 300))
  }
  if (step.shot) {
    win.webContents.invalidate() // verstecktes Fenster zeichnet sonst evtl. nicht neu
    await sleep(step.settle ?? 300)
    const rect = await clipRect(step.clip)
    const img = await capture(rect)
    const target = rel(step.shot)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, img.toPNG())
    shots++
    const sz = img.getSize()
    msgs.push(`shot ${target} (${sz.width}x${sz.height})`)
  }
  return { msgs, fail }
}

app.whenReady().then(async () => {
  const W = spec.width ?? 1680
  const H = spec.height ?? 1000
  const fresh = spec.freshSession ?? exact
  if (exact) Menu.setApplicationMenu(null)
  win = new BrowserWindow({
    width: W,
    height: H,
    useContentSize: exact,
    show: false,
    autoHideMenuBar: true,
    paintWhenInitiallyHidden: true,
    backgroundColor: spec.background ?? (exact ? '#0d0c0b' : '#07090f'),
    webPreferences: { backgroundThrottling: false, ...(fresh ? { partition: 'shot-' + Date.now() } : {}) },
  })
  if (spec.show) win.showInactive()
  win.webContents.on('console-message', function (e) {
    // Electron >= 35: Details im Event-Objekt (level 'info'|'warning'|'error'|'debug'); aeltere: positionale Argumente
    const lvl = e && e.level !== undefined ? e.level : arguments[1]
    const msg = e && e.message !== undefined ? e.message : arguments[2]
    const bad = typeof lvl === 'string' ? lvl === 'warning' || lvl === 'error' : lvl >= 2
    if (bad && !/Electron Security Warning/.test(msg)) out(mask(`[console] ${msg}`))
  })
  win.webContents.on('render-process-gone', (_e, d) => out(`[renderer weg] ${d && d.reason}`))
  setTimeout(() => {
    out('TIMEOUT (gesamter Lauf)')
    failures.push({ i: stepNo, msg: 'Gesamt-Timeout' })
    summary(2)
  }, spec.timeout ?? 120000)

  let url
  try {
    url = subst(spec.url)
  } catch (e) {
    out(`url: ${e.message}`)
    return summary(3)
  }
  await load(url)
  if (exact && !(await checkSize(W, H))) failures.push({ i: 0, msg: `Viewport nicht ${W}x${H}` })

  for (const raw of spec.steps ?? []) {
    stepNo++
    try {
      const res = await runStep(raw)
      if (!res) return // gesperrt -> app.exit(3) laeuft
      const label = raw.name ? `[${raw.name}] ` : ''
      for (const m of res.msgs) out(mask(`#${stepNo} ${label}${m}`))
      if (res.fail) {
        out(mask(`#${stepNo} ${label}${raw.optional ? 'WARNUNG' : 'FEHLER'}: ${res.fail}`))
        if (!raw.optional) failures.push({ i: stepNo, name: raw.name, msg: mask(res.fail) })
      }
    } catch (e) {
      out(mask(`#${stepNo} step error: ${e}`))
      if (!raw.optional) failures.push({ i: stepNo, name: raw.name, msg: mask(String(e)) })
    }
  }
  summary()
})
