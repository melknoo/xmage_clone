// Startet und ueberwacht den Java-Engine-Prozess.
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const readline = require('node:readline')

/**
 * Pfade fuer Dev (Repo) und gepackte App (resources/).
 */
function resolvePaths(app) {
  const packaged = app.isPackaged
  const res = packaged ? process.resourcesPath : path.resolve(__dirname, '..', '..')
  const engineLib = packaged ? path.join(res, 'engine', 'lib') : path.join(res, 'engine', 'build', 'install', 'magelite-engine', 'lib')
  const forge = packaged ? path.join(res, 'forge') : path.join(res, 'vendor', 'forge')
  const ui = packaged ? path.join(res, 'ui') : path.join(res, 'ui', 'dist')
  const jreCandidates = [
    path.join(res, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java'),
    process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : null,
  ].filter(Boolean)
  const java = jreCandidates.find((p) => fs.existsSync(p)) ?? 'java'
  return { engineLib, forge, ui, java }
}

/**
 * Engine-Jar explizit VOR lib/* (stammt aus der XMage-Zeit mit Ersatzklassen; mit Forge harmlos, bleibt als Absicherung
 * gegen gleichnamige Klassen). Die Reihenfolge innerhalb von lib/* ist nicht festgelegt.
 */
function engineClasspath(engineLib) {
  const all = path.join(engineLib, '*')
  let jars = []
  try {
    jars = fs.readdirSync(engineLib).filter((f) => /^magelite-engine.*\.jar$/.test(f))
  } catch {
    return all
  }
  return [...jars.map((f) => path.join(engineLib, f)), all].join(path.delimiter)
}

class Engine {
  constructor(app, log) {
    this.app = app
    this.log = log
    this.proc = null
    this.ready = null
    this.info = null
    this.stopping = false
    this.onExit = null
  }

  start() {
    const { engineLib, forge, ui, java } = resolvePaths(this.app)
    const dataDir = path.join(this.app.getPath('userData'), 'engine')
    fs.mkdirSync(dataDir, { recursive: true })
    const args = [
      '-Xmx3g',
      '-XX:+UseG1GC',
      '-Djava.awt.headless=true',
      '-Dfile.encoding=UTF-8',
      '-cp',
      engineClasspath(engineLib),
      'dev.magelite.Main',
      '--port=0',
      `--data=${dataDir}`,
      `--forge=${forge}`,
      `--ui=${ui}`,
      `--parent-pid=${process.pid}`,
    ]
    this.log(`Starte Engine: ${java} (${engineLib})`)
    this.stopping = false
    this.proc = spawn(java, args, { cwd: dataDir, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })

    this.ready = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Engine-Start Zeitueberschreitung')), 600000)
      const rl = readline.createInterface({ input: this.proc.stdout })
      rl.on('line', (line) => {
        if (line.startsWith('MAGELITE_READY ')) {
          clearTimeout(timer)
          this.info = JSON.parse(line.slice('MAGELITE_READY '.length))
          this.log(`Engine bereit auf Port ${this.info.port} (${this.info.bootMs} ms)`)
          resolve(this.info)
        }
      })
      this.proc.stderr.on('data', (d) => this.log(`[engine] ${String(d).trimEnd()}`))
      this.proc.on('error', (e) => {
        clearTimeout(timer)
        reject(e)
      })
      this.proc.on('exit', (code) => {
        clearTimeout(timer)
        this.log(`Engine beendet (Code ${code})`)
        const wasStopping = this.stopping
        this.proc = null
        if (!this.info) reject(new Error(`Engine beendet mit Code ${code}`))
        if (!wasStopping && this.onExit) this.onExit(code)
      })
    })
    return this.ready
  }

  stop() {
    if (!this.proc) return Promise.resolve()
    this.stopping = true
    const p = this.proc
    return new Promise((resolve) => {
      const kill = setTimeout(() => {
        try {
          p.kill('SIGKILL')
        } catch {
          /* egal */
        }
        resolve()
      }, 3000)
      p.once('exit', () => {
        clearTimeout(kill)
        resolve()
      })
      try {
        p.kill()
      } catch {
        resolve()
      }
    })
  }
}

module.exports = { Engine, resolvePaths }
