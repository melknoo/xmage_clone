// Bruecke Renderer <-> Hauptprozess. Port/Token stehen in der URL (?port=&token=).
const { contextBridge, ipcRenderer } = require('electron')

const params = new URLSearchParams(location.search)
contextBridge.exposeInMainWorld('magelite', {
  port: Number(params.get('port') || 0) || undefined,
  token: params.get('token'),
  fetchText: (url) => ipcRenderer.invoke('magelite:fetchText', url),
})
