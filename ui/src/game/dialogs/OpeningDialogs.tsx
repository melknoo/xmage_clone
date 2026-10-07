// Starthand: Mulligan-Frage und Starthand-Aktion (Gemstone Caverns, Leylines, Chancellors).
import type { ReactNode } from 'react'
import type { Card } from '../../api/types'
import { BoardModal } from '../../components/BoardModal'
import { CardView } from '../../components/CardView'
import { Button } from '../../components/ui'
import { useGame } from '../../store/game'
import { EngineTitle, type DialogProps } from './shared'

const EMPTY: Card[] = []

function HandRow({ hand, width, onHover, chosenName }: { hand: Card[]; width: number; onHover: (c: Card | null) => void; chosenName?: string }) {
  return (
    <div className="flex flex-wrap justify-center gap-2.5" style={{ padding: '22px 20px 10px' }}>
      {hand.map((c) => (
        <CardView
          key={c.id}
          card={c}
          width={width}
          onHover={onHover}
          highlight={chosenName && c.name === chosenName ? 'chosen' : 'none'}
          dim={!!chosenName && c.name !== chosenName}
        />
      ))}
    </div>
  )
}

function InfoRow({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-wrap gap-4 text-body-s text-fg-3" style={{ padding: '6px 20px 0' }}>
      {children}
    </div>
  )
}

function landSpell(hand: Card[]) {
  const lands = hand.filter((c) => c.types?.includes('LAND')).length
  const spells = hand.length - lands
  return `${lands} ${lands === 1 ? 'Land' : 'Länder'} · ${spells} Zauber`
}

/** Mulligan-Frage: Starthand gross, Mulligan / Behalten (Space). */
export function MulliganDialog({ p, onHover, layout }: DialogProps) {
  const answer = useGame((s) => s.answer)
  const hand = useGame((s) => s.state?.hand ?? EMPTY)
  const keep = () => answer({ bool: false })
  return (
    <BoardModal
      label="Starthand"
      title={<EngineTitle p={p} />}
      width={layout.modal.mulligan}
      minimizable
      closable={false}
      onSpace={keep}
      footer={
        <>
          <Button variant="secondary" testId="mulligan-take" onClick={() => answer({ bool: true })}>
            Mulligan
          </Button>
          <Button variant="primary" kbd="Space" testId="mulligan-keep" onClick={keep}>
            Behalten
          </Button>
        </>
      }
    >
      <HandRow hand={hand} width={layout.mullW} onHover={onHover} />
      <InfoRow>
        <span>{landSpell(hand)}</span>
        <span>Mulligan {p.mulligans ?? 0}</span>
        {p.freeMulligan && <span>Der erste Mulligan ist frei</span>}
      </InfoRow>
    </BoardModal>
  )
}

/**
 * Starthand-Aktion vor dem ersten Zug: XMage fragt nur per Ja/Nein - in der Aktionsleiste leicht zu uebersehen
 * oder mit Esc wegzudruecken. Darum ein eigener Dialog mit der Hand (nicht minimierbar).
 */
export function OpeningHandDialog({ p, onHover, layout }: DialogProps) {
  const answer = useGame((s) => s.answer)
  const hand = useGame((s) => s.state?.hand ?? EMPTY)
  const name = /^Put (.+?) (?:onto|on) the battlefield\?$/i.exec(p.messageText ?? '')?.[1]
  const put = () => answer({ bool: true })
  return (
    <BoardModal
      label="Starthand-Aktion"
      title={<EngineTitle p={p} />}
      width={layout.modal.mulligan}
      closable={false}
      onSpace={put}
      footer={
        <>
          <Button variant="secondary" onClick={() => answer({ bool: false })}>
            In der Hand behalten
          </Button>
          <Button variant="primary" kbd="Space" onClick={put}>
            Auf das Spielfeld legen
          </Button>
        </>
      }
    >
      <HandRow hand={hand} width={layout.mullW} onHover={onHover} chosenName={name} />
      <InfoRow>
        <span>Diese Karte darf schon vor dem ersten Zug ins Spiel kommen.</span>
      </InfoRow>
    </BoardModal>
  )
}
