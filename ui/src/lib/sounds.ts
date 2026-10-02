// Kleine Soundeffekte (WebAudio, synthetisch - keine Assets noetig). Lautstaerke/Stumm in localStorage.

type SoundName = 'prompt' | 'turn' | 'win' | 'lose' | 'click'

let ctx: AudioContext | null = null

function audio(): AudioContext | null {
  try {
    ctx ??= new AudioContext()
    return ctx
  } catch {
    return null
  }
}

function muted(): boolean {
  try {
    return localStorage.getItem('magelite.mute') === '1'
  } catch {
    return false
  }
}

function tone(freq: number, start: number, dur: number, type: OscillatorType = 'sine', gain = 0.05) {
  const a = audio()
  if (!a) return
  const o = a.createOscillator()
  const g = a.createGain()
  o.type = type
  o.frequency.value = freq
  g.gain.setValueAtTime(0, a.currentTime + start)
  g.gain.linearRampToValueAtTime(gain, a.currentTime + start + 0.01)
  g.gain.exponentialRampToValueAtTime(0.0001, a.currentTime + start + dur)
  o.connect(g).connect(a.destination)
  o.start(a.currentTime + start)
  o.stop(a.currentTime + start + dur + 0.05)
}

export const sounds = {
  play(name: SoundName) {
    if (muted()) return
    switch (name) {
      case 'prompt':
        tone(660, 0, 0.12, 'triangle', 0.04)
        break
      case 'turn':
        tone(440, 0, 0.12, 'sine', 0.05)
        tone(660, 0.1, 0.18, 'sine', 0.05)
        break
      case 'click':
        tone(880, 0, 0.05, 'square', 0.015)
        break
      case 'win':
        ;[523, 659, 784, 1046].forEach((f, i) => tone(f, i * 0.12, 0.3, 'triangle', 0.05))
        break
      case 'lose':
        ;[392, 330, 262].forEach((f, i) => tone(f, i * 0.16, 0.35, 'sine', 0.05))
        break
    }
  },
  isMuted: muted,
  setMuted(m: boolean) {
    try {
      localStorage.setItem('magelite.mute', m ? '1' : '0')
    } catch {
      /* egal */
    }
  },
}
