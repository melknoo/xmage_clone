// steps-account.json: Electron-Bruecke (window.magelite) im Vite-Browser nachbilden. Login/Abmelden/Offline gehen
// direkt an die lokale Engine (/api/account/sync) - das macht in der App der Electron-Hauptprozess mit dem Cookie.
// args.seed = engine/run/account-host/seed.json ({server, session}) aus desktop/tools/account-seed.mjs
;(async () => {
  const seed = window.__args.seed
  const Y = 'http://127.0.0.1:7432'
  const sync = (body) => fetch(`${Y}/api/account/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  window.magelite = {
    port: 7432,
    token: null,
    serverUrl: seed.server,
    openOnline: (opts) => {
      window.__opened = opts ?? true
    },
    loginOnline: async () => {
      await sync({ server: seed.server, session: seed.session })
      return { ok: true }
    },
    logoutOnline: async () => {
      await sync({ server: seed.server, session: null })
    },
    goOffline: async () => {
      await sync({ server: seed.server, session: seed.session, offline: true })
    },
  }
  window.__seed = seed
  await window.__ml.auth.getState().refreshAccount()
  return 'stub ' + window.__ml.auth.getState().account?.state
})()
