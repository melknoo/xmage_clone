import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Dev-Proxy: ohne ?port= spricht die UI dieselbe Origin an (wie im Server-Modus auf fly), Cookies funktionieren.
// Mit ?port=7317 geht sie weiterhin direkt auf die Engine (CORS, Token).
const engine = 'http://127.0.0.1:7317'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: './',
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': engine,
      '/img': engine,
      '/ws': { target: engine.replace(/^http/, 'ws'), ws: true },
    },
  },
  build: { outDir: 'dist', chunkSizeWarningLimit: 2000 },
})
