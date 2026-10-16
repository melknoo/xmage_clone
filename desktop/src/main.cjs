// MageLite Electron-Hauptprozess: startet die Engine und zeigt die UI.
const { app, BrowserWindow, Menu, ipcMain, net, session, shell, dialog } = require('electron')
const fs = require('node:fs')
const path = require('node:path')
const { Engine } = require('./engine.cjs')

const DEV_UI = process.env.MAGELITE_UI_DEV === '1'

// Datenverzeichnis: Die nicht gepackte App (MageLite.cmd, Repo-Stand) nutzt %APPDATA%\MageLite-dev, damit sie die
// Daten der installierten App nicht anfasst (die Forge-Engine stellt gespeicherte Decks beim Start auf Decktext v2
// um). Beim ersten Start kopiert die Engine die echte DB nur lesend dorthin (--seed-db). MAGELITE_USER_DATA
// ueberschreibt beides. Muss vor requestSingleInstanceLock stehen (eigene Sperre je Verzeichnis).
const INSTALLED_USER_DATA = app.getPath('userData')
const DEV_DATA = !app.isPackaged || !!process.env.MAGELITE_USER_DATA
if (process.env.MAGELITE_USER_DATA) {
  app.setPath('userData', path.resolve(process.env.MAGELITE_USER_DATA))
} else if (!app.isPackaged) {
  app.setPath('userData', path.join(app.getPath('appData'), 'MageLite-dev'))
}
const logFile = () => path.join(app.getPath('userData'), 'desktop.log')
const settingsFile = () => path.join(app.getPath('userData'), 'settings.json')
const DEFAULT_SERVER = 'https://magelite.fly.dev'

/** settings.json: { serverUrl } - Online-Server, den "Online spielen" im selben Fenster laedt (MAGELITE_SERVER_URL ueberschreibt) */
function loadSettings() {
  let s = {}
  try {
    s = JSON.parse(fs.readFileSync(settingsFile(), 'utf8'))
  } catch {
    /* keine Einstellungen */
  }
  const env = process.env.MAGELITE_SERVER_URL
  const url = String(env || s.serverUrl || DEFAULT_SERVER).replace(/\/+$/, '')
  return { serverUrl: /^https?:\/\/[^/]+$/.test(url) ? url : DEFAULT_SERVER }
}
const settings = loadSettings()
const serverOrigin = () => new URL(settings.serverUrl).origin

function log(msg) {
  const line = `${new Date().toISOString()} ${msg}\n`
  process.stdout.write(line)
  try {
    fs.appendFileSync(logFile(), line)
  } catch {
    /* egal */
  }
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
}

let win = null
let engine = null
let restarts = 0
/** MAGELITE_READY der laufenden Engine ({port, token}) */
let engineInfo = null

function uiUrl(info) {
  const q = `port=${info.port}${info.token ? `&token=${info.token}` : ''}&server=${encodeURIComponent(settings.serverUrl)}`
  return DEV_UI ? `http://localhost:5173/?${q}` : `http://127.0.0.1:${info.port}/?${q}`
}

// ---------------------------------------------------------------- Online-Konto
// Die App zeigt angemeldet die Daten des Online-Kontos (Decks, Held, Statistik), Spiele laufen trotzdem lokal. Die
// Session ist das Cookie ml_sess des Servers in der Electron-Session (HttpOnly - nur hier lesbar); wir melden es der
// Engine (/api/account/sync), die damit Kontodaten holt und den Host-Link (Tische auf diesem Rechner) bei Bedarf
// verbindet. Ohne Cookie zeigt die UI das Login-Gate, "Offline spielen" nutzt die lokalen Daten.
/** zuletzt an die Engine gemeldete Session (undefined = noch nie) */
let syncedSession
/** "Offline spielen" gewaehlt (nur diese Sitzung) */
let offline = false
/** Registrieren/Passwort vergessen auf der Server-Seite: sobald dort ein Login klappt, zurueck zur App */
let pendingLogin = false
let syncTimer = null

async function localApi(method, apiPath, body) {
  if (!engineInfo) throw new Error('Engine nicht bereit')
  const res = await net.fetch(`http://127.0.0.1:${engineInfo.port}${apiPath}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(engineInfo.token ? { 'X-MageLite-Token': engineInfo.token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

async function currentSession() {
  const cookies = await session.defaultSession.cookies.get({ url: settings.serverUrl, name: 'ml_sess' })
  return cookies.find((c) => c.value)?.value ?? null
}

/** Session (oder ihr Fehlen) der Engine melden; force = auch ohne Aenderung (Start, Engine-Neustart, Offline). */
async function syncAccount(force = false) {
  if (!engineInfo) return
  let token
  try {
    token = await currentSession()
  } catch (e) {
    log(`Cookie lesen: ${e.message}`)
    return
  }
  if (!force && token === syncedSession) return
  try {
    const st = await localApi('POST', '/api/account/sync', { server: settings.serverUrl, session: token, offline })
    syncedSession = token
    log(`Konto: ${token ? 'Session gemeldet' : 'nicht angemeldet'}${offline ? ' (offline)' : ''} -> ${st.state}`)
  } catch (e) {
    log(`Konto-Sync: ${e.message}`)
  }
}

function scheduleSync(delay = 300) {
  clearTimeout(syncTimer)
  syncTimer = setTimeout(() => void syncAccount(), delay)
}

/** Entwickler: MAGELITE_DEV_SESSION=<Token> simuliert eine Anmeldung (vor dem ersten Sync setzen). */
async function setDevSession() {
  const dev = process.env.MAGELITE_DEV_SESSION
  if (!dev) return
  try {
    await session.defaultSession.cookies.set({ url: settings.serverUrl, name: 'ml_sess', value: dev, httpOnly: true, sameSite: 'lax' })
    log('Dev-Session gesetzt')
  } catch (e) {
    log(`Dev-Session: ${e.message}`)
  }
}

function watchCookies() {
  session.defaultSession.cookies.on('changed', (_e, cookie, _cause, removed) => {
    if (cookie.name !== 'ml_sess') return
    if (!removed && cookie.value) {
      offline = false
      if (pendingLogin) {
        // Login auf der Server-Seite (nach Registrieren/Passwort vergessen) geklappt: zurueck in die App
        pendingLogin = false
        void syncAccount(true).then(() => openLocal())
        return
      }
    }
    scheduleSync()
  })
  setInterval(() => void syncAccount(), 30_000)
}

/** ml_sess aus den Set-Cookie-Zeilen der Login-Antwort */
function sessionFromSetCookie(res) {
  const lines = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [res.headers.get('set-cookie') || '']
  for (const line of lines) {
    const m = /(?:^|[;,]\s*)ml_sess=([^;,\s]+)/.exec(line)
    if (m) return m[1]
  }
  return null
}

/**
 * Login aus dem Gate der App ({code} oder {email, password}): POST /api/auth/login am Server, Session-Cookie in die
 * Electron-Session, Engine informieren. Der Renderer sieht das Token nie.
 */
async function loginOnline(creds) {
  const body = creds && creds.code ? { code: String(creds.code) } : { email: String(creds?.email ?? ''), password: String(creds?.password ?? '') }
  let res
  try {
    res = await fetch(`${settings.serverUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(40_000),
    })
  } catch (e) {
    log(`Login: ${e.message}`)
    return { ok: false, status: 0, error: 'Server nicht erreichbar – später nochmal versuchen' }
  }
  let data = {}
  try {
    data = await res.json()
  } catch {
    /* kein JSON (fly-Proxy) */
  }
  if (!res.ok) {
    return { ok: false, status: res.status, error: data.error || `Anmeldung fehlgeschlagen (HTTP ${res.status})`, unverified: !!data.unverified }
  }
  const token = sessionFromSetCookie(res)
  if (!token) return { ok: false, status: 500, error: 'Der Server hat keine Sitzung geliefert' }
  await session.defaultSession.cookies.set({
    url: settings.serverUrl,
    name: 'ml_sess',
    value: token,
    httpOnly: true,
    secure: settings.serverUrl.startsWith('https:'),
    sameSite: 'lax',
    expirationDate: Math.floor(Date.now() / 1000) + 365 * 24 * 3600,
  })
  offline = false
  pendingLogin = false
  await syncAccount(true)
  log(`Angemeldet als ${data.user?.name ?? '?'}`)
  return { ok: true }
}

/**
 * Online abmelden aus der lokalen App (Konto-Menue): Session am Server beenden (best effort; Node-fetch, weil das
 * Cookie dort per Header mitgeht), Cookie loeschen, Engine informieren (-> Gate). Laufende Relay-Spiele enden.
 */
async function logoutOnline() {
  const token = await currentSession()
  if (token) {
    try {
      const res = await fetch(`${settings.serverUrl}/api/auth/logout`, {
        method: 'POST',
        headers: { Cookie: `ml_sess=${token}` },
        signal: AbortSignal.timeout(8000),
      })
      log(`Abmelden am Server: HTTP ${res.status}`)
    } catch (e) {
      log(`Abmelden am Server: ${e.message}`)
    }
  }
  await session.defaultSession.cookies.remove(settings.serverUrl, 'ml_sess')
  await syncAccount(true)
}

/** Aus "Offline" wieder anmelden: gemerkte Session pruefen (ohne Session zeigt die UI das Gate). */
async function goOnline() {
  offline = false
  await syncAccount(true)
}

/** "Offline spielen": lokale Daten bis zum naechsten Login bzw. Neustart. */
async function goOffline() {
  offline = true
  pendingLogin = false
  await syncAccount(true)
}

/**
 * Server-Seite im Fenster laden. {hash}: Unterseite des Logins ("signup", "forgot") - danach geht es nach dem Login
 * automatisch zurueck zur App. Ohne hash ("Zur Lobby"): Host-Link wecken, damit Tische auf diesem Rechner gehen.
 */
async function openOnline(opts) {
  if (!win) return
  const hash = opts && typeof opts.hash === 'string' && /^[a-z]+$/.test(opts.hash) ? opts.hash : null
  pendingLogin = !!hash
  if (!hash) {
    try {
      await localApi('POST', '/api/account/wake')
    } catch (e) {
      log(`Host-Link wecken: ${e.message}`)
    }
  }
  const url = hash ? `${settings.serverUrl}/#${hash}` : settings.serverUrl
  log(`Online: ${url}`)
  win.loadURL(url).catch((e) => {
    log(`Online-Server nicht erreichbar: ${e.message}`)
    pendingLogin = false
    dialog.showErrorBox('MageLite', `Der Server ${settings.serverUrl} ist gerade nicht erreichbar.\n\n${e.message}`)
    if (engineInfo) win.loadURL(uiUrl(engineInfo)).catch(() => undefined)
  })
}

async function openLocal(opts) {
  pendingLogin = false
  if (opts && opts.offline) await goOffline()
  if (win && engineInfo) win.loadURL(uiUrl(engineInfo)).catch((e) => log(`Lokale UI: ${e.message}`))
}

async function boot() {
  win = new BrowserWindow({
    width: 1680,
    height: 1000,
    minWidth: 1280,
    minHeight: 760,
    backgroundColor: '#121110',
    title: DEV_DATA ? 'MageLite (Test)' : 'MageLite',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      sandbox: true,
      additionalArguments: [`--ml-version=${app.getVersion()}`, `--ml-server=${serverOrigin()}`],
    },
  })
  // Nur die lokale UI und der eingestellte Online-Server duerfen im Fenster laufen; alles andere in den Browser
  win.webContents.on('will-navigate', (event, url) => {
    let origin = ''
    try {
      origin = new URL(url).origin
    } catch {
      return
    }
    const local = /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(origin)
    if (!local && origin !== serverOrigin()) {
      event.preventDefault()
      shell.openExternal(url)
    }
  })
  win.webContents.on('did-navigate', () => scheduleSync())
  // F5/F11 gehoeren dem Spiel (Pass-Tasten), daher kein Standardmenue
  Menu.setApplicationMenu(null)
  win.webContents.on('before-input-event', (event, input) => {
    if (input.type === 'keyDown' && input.control && input.shift && input.key.toLowerCase() === 'i') {
      win.webContents.toggleDevTools()
      event.preventDefault()
    }
  })
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })
  win.once('ready-to-show', () => win.show())
  await win.loadFile(path.join(__dirname, 'splash.html'))
  win.show()

  engine = new Engine(app, log, DEV_DATA ? path.join(INSTALLED_USER_DATA, 'engine', 'magelite.db') : null)
  engine.onExit = async (code) => {
    engineInfo = null
    syncedSession = undefined
    if (restarts++ < 2) {
      log('Engine abgestuerzt - starte neu')
      try {
        const info = await engine.start()
        engineInfo = info
        syncedSession = undefined
        await syncAccount(true) // neue Engine kennt die Session noch nicht
        await win.loadURL(uiUrl(info))
      } catch (e) {
        fail(e)
      }
    } else {
      fail(new Error(`Engine wiederholt abgestuerzt (Code ${code})`))
    }
  }
  try {
    const info = await engine.start()
    engineInfo = info
    // erst die Session melden, dann die UI laden: sie zeigt sofort Konto oder Login statt "pruefen"
    await setDevSession()
    watchCookies()
    await syncAccount(true)
    await win.loadURL(uiUrl(info))
    autoShot()
  } catch (e) {
    fail(e)
  }
}

ipcMain.on('magelite:openOnline', (_e, opts) => void openOnline(opts))
ipcMain.on('magelite:openLocal', (_e, opts) => void openLocal(opts))
ipcMain.handle('magelite:logoutOnline', () => logoutOnline())
ipcMain.handle('magelite:loginOnline', (_e, creds) => loginOnline(creds))
ipcMain.handle('magelite:goOffline', () => goOffline())
ipcMain.handle('magelite:goOnline', () => goOnline())

// Entwickler: MAGELITE_AUTOSHOT=<datei.png>;<ms> speichert nach <ms> einen Screenshot und beendet die App
function autoShot() {
  const spec = process.env.MAGELITE_AUTOSHOT
  if (!spec) return
  const [file, ms] = spec.split(';')
  setTimeout(async () => {
    const img = await win.webContents.capturePage()
    fs.writeFileSync(file, img.toPNG())
    log(`Screenshot ${file}`)
    app.quit()
  }, Number(ms || 8000))
}

function fail(e) {
  log(`FEHLER: ${e.stack || e}`)
  // Die gepackte App bringt ihre eigene Java-Laufzeit mit
  const hint = app.isPackaged ? '' : '\n\nIst Java 17+ installiert?'
  dialog.showErrorBox('MageLite', `Die Engine konnte nicht gestartet werden:\n\n${e.message}\n\nLog: ${logFile()}${hint}`)
}

// Moxfield & Co. blocken Server-Requests -> Abruf ueber Chromium-Netzwerkstack
ipcMain.handle('magelite:fetchText', async (_e, url) => {
  if (!/^https:\/\/(api2?\.)?(moxfield\.com|archidekt\.com)\//.test(url)) throw new Error('URL nicht erlaubt')
  const res = await net.fetch(url, { headers: { Accept: 'application/json' } })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return await res.text()
})

app.whenReady().then(boot)

app.on('second-instance', () => {
  if (win) {
    if (win.isMinimized()) win.restore()
    win.focus()
  }
})

let quitting = false
app.on('before-quit', async (e) => {
  if (quitting || !engine) return
  e.preventDefault()
  quitting = true
  await engine.stop()
  app.quit()
})

app.on('window-all-closed', () => app.quit())
