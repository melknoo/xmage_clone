import type { Tempo } from '../api/types'

/** Bot-Tempo: kurzer Text (desc, sichtbar) aus dem Prototyp, die ausfuehrliche Erklaerung als title (Tooltip). */
export interface TempoInfo {
  key: Tempo
  label: string
  desc: string
  title: string
}

export const TEMPOS: TempoInfo[] = [
  { key: 'BLITZ', label: 'Blitz', desc: 'Bots entscheiden sofort', title: 'Bots entscheiden in ~2 s, keine Pausen' },
  { key: 'NORMAL', label: 'Normal', desc: 'Kurze Bedenkzeit', title: 'Schnell, mit kurzen Pausen zum Mitlesen' },
  { key: 'BEDACHT', label: 'Bedacht', desc: 'Längere Bedenkzeit', title: 'Bots denken länger und reagieren in deinem Zug' },
  { key: 'MAX', label: 'Max', desc: 'Volle Rechenzeit', title: 'Stärkste Bots – deutlich langsamer' },
]

/** Anzeigename eines Tempos ('BLITZ' -> 'Blitz'); unbekannte Werte unveraendert. */
export function tempoLabel(t: Tempo | string | null | undefined): string {
  if (!t) return ''
  return TEMPOS.find((x) => x.key === t)?.label ?? t
}
