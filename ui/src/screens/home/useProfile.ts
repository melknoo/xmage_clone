import { useCallback, useEffect } from 'react'
import { create } from 'zustand'
import { profileApi, type Profile } from '../../api/profile'
import { accountKeyOf, useAuth } from '../../store/auth'

export interface ProfileHook {
  /** null = noch nicht geladen oder Fehler */
  profile: Profile | null
  loading: boolean
  /** Held umbenennen (leer -> "Planeswalker"); wirft bei Fehlern */
  rename: (name: string) => Promise<void>
  reload: () => void
}

/**
 * Geteilter Cache: HeroHeader und QuickStartCard lesen dasselbe Profil (eine Anfrage), und beim
 * Zurueckkehren auf den Held steht sofort der letzte Stand da. `owner` = Nutzer-ID plus Online-Konto der Desktop-App,
 * damit nach einem Kontowechsel (Server-Modus, Desktop an/ab/offline) nie das Profil des vorigen Kontos erscheint.
 */
interface ProfileCache {
  owner: string | null
  profile: Profile | null
  loading: boolean
}

const useProfileCache = create<ProfileCache>(() => ({ owner: null, profile: null, loading: false }))

let inflight: Promise<void> | null = null
let inflightOwner: string | null = null

function load(owner: string): Promise<void> {
  if (inflight && inflightOwner === owner) return inflight
  inflightOwner = owner
  useProfileCache.setState((s) => (s.owner === owner ? { loading: true } : { owner, profile: null, loading: true }))
  const p = profileApi
    .get()
    .then((profile) => {
      if (useProfileCache.getState().owner === owner) useProfileCache.setState({ profile })
    })
    .catch(() => {
      // Profil bleibt beim letzten Stand (oder null); der Held zeigt dann Grundwerte
    })
    .finally(() => {
      if (inflight === p) inflight = null
      if (useProfileCache.getState().owner === owner) useProfileCache.setState({ loading: false })
    })
  inflight = p
  return p
}

/** Held laden (GET /api/profile) und umbenennen (PUT /api/profile). Laedt beim Mounten neu (Cache sofort). */
export function useProfile(): ProfileHook {
  // lokal derselbe Nutzer (Desktop-App: lokal oder Online-Konto); Server-Modus: angemeldeter Nutzer
  const owner = useAuth((s) => `${s.me?.id ?? 0}|${accountKeyOf(s.account)}`)
  const cacheOwner = useProfileCache((s) => s.owner)
  const cached = useProfileCache((s) => s.profile)
  const loading = useProfileCache((s) => s.loading)

  const reload = useCallback(() => {
    void load(owner)
  }, [owner])
  useEffect(() => {
    reload()
  }, [reload])

  const rename = useCallback(
    async (name: string) => {
      const profile = await profileApi.rename(name.trim() || 'Planeswalker')
      useProfileCache.setState({ owner, profile })
    },
    [owner],
  )

  const mine = cacheOwner === owner
  return { profile: mine ? cached : null, loading: mine ? loading : true, rename, reload }
}
