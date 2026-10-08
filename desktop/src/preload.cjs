// Bruecke Renderer <-> Hauptprozess.
// Lokale UI (127.0.0.1/localhost): Port/Token stehen in der URL (?port=&token=&server=), dazu "Online spielen".
// Fremde Seite (der Online-Server im selben Fenster): nur ein kleiner Hinweis, dass wir in der App sind, mit
// "Zurueck zur App" - nie Port/Token (sonst liefe die Server-UI gegen 127.0.0.1).
const { contextBridge, ipcRenderer } = require('electron')

const local = ['127.0.0.1', 'localhost'].includes(location.hostname)
const versionArg = process.argv.find((a) => a.startsWith('--ml-version='))
const version = versionArg ? versionArg.slice('--ml-version='.length) : 'dev'

if (local) {
  const params = new URLSearchParams(location.search)
  contextBridge.exposeInMainWorld('magelite', {
    port: Number(params.get('port') || 0) || undefined,
    token: params.get('token'),
    serverUrl: params.get('server') || undefined,
    fetchText: (url) => ipcRenderer.invoke('magelite:fetchText', url),
    openOnline: () => ipcRenderer.send('magelite:openOnline'),
  })
} else {
  contextBridge.exposeInMainWorld('mageliteDesktop', {
    version,
    openLocal: () => ipcRenderer.send('magelite:openLocal'),
  })
}
