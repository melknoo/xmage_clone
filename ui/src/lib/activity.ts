/** Ohne Maus/Tastatur so lange -> "abwesend" (wie AWAY_MS in store/social.ts) */
const AWAY_MS = 15 * 60_000

let lastInput = Date.now()
let listening = false

function listen() {
  if (listening) return
  listening = true
  const onInput = () => {
    lastInput = Date.now()
  }
  for (const ev of ['pointerdown', 'keydown', 'mousemove', 'wheel']) window.addEventListener(ev, onInput, { passive: true })
}

/**
 * Sollen Hintergrund-Polls ruhen? Bei verstecktem Fenster oder nach 15 min ohne Eingabe. Fuer Polls der lokalen App,
 * die indirekt den Online-Server fragen (Host-Link-Stand); `pollPaused()` in store/social.ts zaehlt Eingaben nur,
 * solange das Social-Polling im Server-Modus laeuft. Der erste Aufruf haengt die Eingabe-Listener an.
 */
export function idlePaused(): boolean {
  listen()
  return document.hidden || Date.now() - lastInput > AWAY_MS
}
