import { MotionConfig } from 'motion/react'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import { App } from './App'
import { api } from './api/client'
import { useAuth } from './store/auth'
import { useGame } from './store/game'
import { useNav, type LastSetup } from './store/nav'
import { useSocial } from './store/social'
import { useTable } from './store/table'
import { useConn } from './store/conn'
import { useUi } from './store/ui'

if (import.meta.env.DEV) {
  // In-Page-Autopilot fuer Screenshot-/Szenario-Skripte (desktop/tools/shot.cjs); nur im Vite-Dev-Modus
  ;(window as unknown as Record<string, unknown>).__ml = {
    game: useGame,
    nav: useNav,
    social: useSocial,
    auth: useAuth,
    table: useTable,
    ui: useUi,
    conn: useConn,
    api: {
      /** Spiel starten (POST /api/games), verbinden und zum Brett wechseln; liefert die gameId */
      start: async (spec: LastSetup) => {
        const res = await api.post<{ gameId: string }>('/api/games', spec)
        useGame.getState().connect(res.gameId)
        useNav.getState().go('game')
        return res.gameId
      },
    },
  }
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MotionConfig reducedMotion="user">
      <App />
    </MotionConfig>
  </StrictMode>,
)
