import { Button, Overlay } from '../../components/ui'

/**
 * Loeschen-Bestaetigung (440 breit, mittig in <main>, z 22/23). Overlay-Variante "confirm": ohne Linien wie im Prototyp.
 * scrim=false: liegt ueber dem Import-Dialog, dessen Scrim schon abdunkelt (dann nur unsichtbarer Klickfang).
 * Esc schliesst (liegt ein Overlay darunter, faengt dessen Esc-Handler die Taste - der Aufrufer leitet sie hierher).
 */
export function DeleteDeckDialog({ name, busy, scrim = true, onCancel, onConfirm }: { name: string; busy?: boolean; scrim?: boolean; onCancel: () => void; onConfirm: () => void }) {
  return (
    <Overlay
      variant="confirm"
      scrim={scrim}
      width={440}
      label="Deck löschen"
      labelTone="danger"
      title={`${name} löschen?`}
      onClose={onCancel}
      testId="deck-delete-dialog"
      footer={
        <>
          <Button variant="ghost" kbd="Esc" testId="modal-cancel" onClick={onCancel}>
            Abbrechen
          </Button>
          <Button variant="dangerConfirm" testId="deck-delete-confirm" disabled={busy} onClick={onConfirm}>
            Löschen
          </Button>
        </>
      }
    >
      <p className="m-0 text-[14px] leading-[1.5] text-fg-2">Das Deck wird aus der Sammlung entfernt. Vergangene Partien bleiben im Verlauf.</p>
    </Overlay>
  )
}
