// Entwickler-Werkzeug: oeffnet eine URL, fuehrt Schritte aus und speichert Screenshots.
// Aufruf: electron tools/shot.cjs <steps.json>
// steps.json: { "url": "...", "width": 1680, "height": 1000, "steps": [ {"wait": 2000}, {"js": "..."}, {"shot": "a.png"}, {"waitFor": "css-selector", "timeout": 30000} ] }
const { app, BrowserWindow } = require('electron')
const fs = require('node:fs')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

app.whenReady().then(async () => {
  const file = process.argv[process.argv.length - 1]
  const spec = JSON.parse(fs.readFileSync(file, 'utf8'))
  const win = new BrowserWindow({ width: spec.width ?? 1680, height: spec.height ?? 1000, show: false, paintWhenInitiallyHidden: true, backgroundColor: '#07090f', webPreferences: { backgroundThrottling: false } })
  win.webContents.on('console-message', (_e, level, message) => {
    if (level >= 2) process.stdout.write(`[console] ${message}\n`)
  })
  setTimeout(() => {
    process.stdout.write('TIMEOUT\n')
    app.exit(2)
  }, spec.timeout ?? 120000)
  win.loadURL(spec.url).catch((e) => process.stdout.write(`load error: ${e}\n`))
  await sleep(500)
  for (const step of spec.steps ?? []) {
    try {
      if (step.wait) await sleep(step.wait)
      if (step.waitFor) {
        const until = Date.now() + (step.timeout ?? 30000)
        let ok = false
        while (Date.now() < until) {
          ok = await win.webContents.executeJavaScript(`!!document.querySelector(${JSON.stringify(step.waitFor)})`)
          if (ok) break
          await sleep(250)
        }
        process.stdout.write(`waitFor ${step.waitFor}: ${ok}\n`)
      }
      if (step.js) {
        const r = await win.webContents.executeJavaScript(step.js)
        if (r !== undefined) process.stdout.write(`js: ${JSON.stringify(r)}\n`)
      }
      if (step.shot) {
        const img = await win.webContents.capturePage()
        fs.writeFileSync(step.shot, img.toPNG())
        process.stdout.write(`shot ${step.shot}\n`)
      }
    } catch (e) {
      process.stdout.write(`step error: ${e}\n`)
    }
  }
  app.exit(0)
})
