// Bruecke Renderer <-> Hauptprozess.
// Lokale UI (127.0.0.1/localhost): Port/Token stehen in der URL (?port=&token=&server=), dazu Online an-/abmelden.
// Fremde Seite (der Online-Server im selben Fenster): nur ein kleiner Hinweis, dass wir in der App sind, mit
// "Zurueck zur App" - nie Port/Token (sonst liefe die Server-UI gegen 127.0.0.1).
// "Lokal" per Origin: ein Test-Server auf 127.0.0.1 (MAGELITE_SERVER_URL) ist trotzdem die Server-Seite.
const { contextBridge, ipcRenderer } = require('electron')

const arg = (name) => {
  const a = process.argv.find((x) => x.startsWith(`--${name}=`))
  return a ? a.slice(name.length + 3) : null
}
const version = arg('ml-version') || 'dev'
const serverOrigin = arg('ml-server')
const local = ['127.0.0.1', 'localhost'].includes(location.hostname) && location.origin !== serverOrigin

if (local) {
  const params = new URLSearchParams(location.search)
  contextBridge.exposeInMainWorld('magelite', {
    port: Number(params.get('port') || 0) || undefined,
    token: params.get('token'),
    serverUrl: params.get('server') || undefined,
    fetchText: (url) => ipcRenderer.invoke('magelite:fetchText', url),
    /** Server-Seite laden: ohne Argument die Lobby, {hash:'signup'|'forgot'} eine Login-Unterseite */
    openOnline: (opts) => ipcRenderer.send('magelite:openOnline', opts && typeof opts.hash === 'string' ? { hash: opts.hash } : undefined),
    /** Login aus dem Gate: {code} oder {email, password} -> {ok} | {ok:false, status, error, unverified} */
    loginOnline: (creds) =>
      ipcRenderer.invoke(
        'magelite:loginOnline',
        creds && creds.code ? { code: String(creds.code) } : { email: String(creds?.email ?? ''), password: String(creds?.password ?? '') },
      ),
    logoutOnline: () => ipcRenderer.invoke('magelite:logoutOnline'),
    goOffline: () => ipcRenderer.invoke('magelite:goOffline'),
    /** aus "Offline" wieder anmelden (gemerkte Session pruefen) */
    goOnline: () => ipcRenderer.invoke('magelite:goOnline'),
  })
} else {
  contextBridge.exposeInMainWorld('mageliteDesktop', {
    version,
    /** zurueck zur App; {offline:true} = "Offline spielen" */
    openLocal: (opts) => ipcRenderer.send('magelite:openLocal', { offline: !!(opts && opts.offline) }),
  })
}
