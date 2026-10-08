import { api } from '../api/client'
import { pushToast } from '../store/ui'

/**
 * Oeffentliche (selbst registrierte) Konten duerfen keine Spiele auf dem Server rechnen lassen: "Allein ueben" laeuft in
 * der MageLite-App. In der App: zurueck zur lokalen Seite; im Browser: Setup laden (Link aus /api/download/info).
 */
export async function openLocalApp(): Promise<void> {
  if (window.mageliteDesktop) {
    window.mageliteDesktop.openLocal()
    return
  }
  try {
    const d = await api.get<{ available: boolean; url?: string }>('/api/download/info')
    if (d.available && d.url) {
      window.location.href = d.url
      return
    }
  } catch {
    /* unten */
  }
  pushToast({ kind: 'info', text: 'Die MageLite-App gibt es gerade nicht zum Download – bitte später nochmal.' })
}
