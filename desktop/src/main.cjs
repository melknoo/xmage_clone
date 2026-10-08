// MageLite Electron-Hauptprozess: startet die Engine und zeigt die UI.
const { app, BrowserWindow, Menu, ipcMain, net, session, shell, dialog } = require('electron')
const fs = require('node:fs')
const path = require('node:path')
const { Engine } = require('./engine.cjs')

const DEV_UI = process.env.MAGELITE_UI_DEV === '1'
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

// ---------------------------------------------------------------- Host-Link
// Der Online-Server laeuft im selben Fenster. Nach der Anmeldung dort liegt das Session-Cookie (ml_sess, HttpOnly -
// aus dem Hauptprozess lesbar) in der Electron-Session; wir melden es der lokalen Engine, die sich ausgehend an den
// Server haengt (Tische auf diesem Rechner). Cookie weg (Abmelden) -> Link trennen.
let linkedSession = null
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

async function syncHostLink() {
  if (!engineInfo) return
  let token = null
  try {
    const cookies = await session.defaultSession.cookies.get({ url: settings.serverUrl, name: 'ml_sess' })
    token = cookies.find((c) => c.value)?.value ?? null
  } catch (e) {
    log(`Cookie lesen: ${e.message}`)
    return
  }
  if (token === linkedSession) return
  try {
    if (token) {
      await localApi('POST', '/api/host/link', { server: settings.serverUrl, session: token })
      log(`Host-Link an ${settings.serverUrl} angefordert`)
    } else {
      await localApi('DELETE', '/api/host/link')
      log('Host-Link getrennt (abgemeldet)')
    }
    linkedSession = token
  } catch (e) {
    log(`Host-Link: ${e.message}`)
  }
}

function scheduleSync(delay = 300) {
  clearTimeout(syncTimer)
  syncTimer = setTimeout(() => void syncHostLink(), delay)
}

function watchCookies() {
  session.defaultSession.cookies.on('changed', (_e, cookie) => {
    if (cookie.name === 'ml_sess') scheduleSync()
  })
  setInterval(() => void syncHostLink(), 30_000)
  // Entwickler: MAGELITE_DEV_SESSION=<Token> simuliert eine Anmeldung auf dem Server (Host-Link-Test ohne Klicks)
  const dev = process.env.MAGELITE_DEV_SESSION
  if (dev) {
    session.defaultSession.cookies
      .set({ url: settings.serverUrl, name: 'ml_sess', value: dev, httpOnly: true, sameSite: 'lax' })
      .then(() => log('Dev-Session gesetzt'))
      .catch((e) => log(`Dev-Session: ${e.message}`))
  }
}

function openOnline() {
  if (!win) return
  log(`Online: ${settings.serverUrl}`)
  win.loadURL(settings.serverUrl).catch((e) => {
    log(`Online-Server nicht erreichbar: ${e.message}`)
    dialog.showErrorBox('MageLite', `Der Server ${settings.serverUrl} ist gerade nicht erreichbar.\n\n${e.message}`)
    if (engineInfo) win.loadURL(uiUrl(engineInfo)).catch(() => undefined)
  })
}

function openLocal() {
  if (win && engineInfo) win.loadURL(uiUrl(engineInfo)).catch((e) => log(`Lokale UI: ${e.message}`))
}

async function boot() {
  win = new BrowserWindow({
    width: 1680,
    height: 1000,
    minWidth: 1280,
    minHeight: 760,
    backgroundColor: '#121110',
    title: 'MageLite',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      sandbox: true,
      additionalArguments: [`--ml-version=${app.getVersion()}`],
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

  engine = new Engine(app, log)
  engine.onExit = async (code) => {
    engineInfo = null
    linkedSession = null
    if (restarts++ < 2) {
      log('Engine abgestuerzt - starte neu')
      try {
        const info = await engine.start()
        engineInfo = info
        await win.loadURL(uiUrl(info))
        scheduleSync()
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
    await win.loadURL(uiUrl(info))
    watchCookies()
    scheduleSync()
    autoShot()
  } catch (e) {
    fail(e)
  }
}

ipcMain.on('magelite:openOnline', () => openOnline())
ipcMain.on('magelite:openLocal', () => openLocal())

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
