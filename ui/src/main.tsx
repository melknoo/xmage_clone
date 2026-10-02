import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import { App } from './App'
import { useGame } from './store/game'
import { useNav } from './store/nav'

if (import.meta.env.DEV) {
  ;(window as unknown as Record<string, unknown>).__ml = { game: useGame, nav: useNav }
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
