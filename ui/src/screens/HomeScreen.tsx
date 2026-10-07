import { useAuth } from '../store/auth'
import { HomeLocal } from './home/HomeLocal'
import { HomeServer } from './home/HomeServer'

/** Startseite (Nav "Start", Logo): duenner Umschalter nach Modus (lokal: M1, Server: O1). */
export function HomeScreen() {
  const mode = useAuth((s) => s.mode)
  return mode === 'server' ? <HomeServer /> : <HomeLocal />
}
