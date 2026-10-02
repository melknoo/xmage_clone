// MageLite Electron-Hauptprozess: startet die Engine und zeigt die UI.
const { app, BrowserWindow, Menu, ipcMain, net, shell, dialog } = require('electron')
const fs = require('node:fs')
const path = require('node:path')
const { Engine } = require('./engine.cjs')

const DEV_UI = process.env.MAGELITE_UI_DEV === '1'
const logFile = () => path.join(app.getPath('userData'), 'desktop.log')

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

function uiUrl(info) {
  const q = `port=${info.port}${info.token ? `&token=${info.token}` : ''}`
  return DEV_UI ? `http://localhost:5173/?${q}` : `http://127.0.0.1:${info.port}/?${q}`
}

async function boot() {
  win = new BrowserWindow({
    width: 1680,
    height: 1000,
    minWidth: 1280,
    minHeight: 760,
    backgroundColor: '#07090f',
    title: 'MageLite',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      sandbox: true,
    },
  })
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
    if (restarts++ < 2) {
      log('Engine abgestuerzt - starte neu')
      try {
        const info = await engine.start()
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
    await win.loadURL(uiUrl(info))
    autoShot()
  } catch (e) {
    fail(e)
  }
}

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
