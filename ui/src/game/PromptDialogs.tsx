import type { Card, Prompt } from '../api/types'
import { useGame } from '../store/game'
import { AbilityDialog } from './dialogs/AbilityDialog'
import { MulliganDialog, OpeningHandDialog } from './dialogs/OpeningDialogs'
import { AmountDialog, CardPickDialog, ChoiceDialog, MultiAmountDialog, PileDialog } from './dialogs/OtherDialogs'
import { ReplacementDialog } from './dialogs/ReplacementDialog'
import type { Interaction } from './interaction'
import type { BoardLayoutState } from './layout'
import { isOpeningHandAsk } from './promptActions'

export interface PromptDialogsProps {
  inter: Interaction
  onHover: (c: Card | null) => void
  /** modal.* (Dialogbreiten), mullW, graveW */
  layout: BoardLayoutState
}

/** Brett-Dialoge zur offenen Engine-Frage (BoardModal ueber der linken Brettspalte). */
export function PromptDialogs({ inter, onHover, layout }: PromptDialogsProps) {
  const p = inter.prompt
  if (!p) return null
  // key: jeder neue Prompt startet wieder aufgeklappt und ohne Auswahl
  return <PromptDialog key={p.id} p={p} inter={inter} onHover={onHover} layout={layout} />
}

function PromptDialog({ p, inter, onHover, layout }: { p: Prompt } & PromptDialogsProps) {
  const step = useGame((s) => s.state?.step)
  const props = { p, onHover, layout }
  switch (p.kind) {
    case 'CHOOSE_ABILITY':
    case 'CHOOSE_MODE':
    case 'PICK_ABILITY':
      return <AbilityDialog {...props} />
    case 'CHOOSE_CHOICE':
      return p.choice?.groups ? <ReplacementDialog {...props} /> : <ChoiceDialog {...props} />
    case 'AMOUNT':
      return <AmountDialog {...props} />
    case 'MULTI_AMOUNT':
      return <MultiAmountDialog {...props} />
    case 'CHOOSE_PILE':
      return <PileDialog {...props} />
    case 'ASK':
      if (p.mulligan) return <MulliganDialog {...props} />
      if (isOpeningHandAsk(p, step)) return <OpeningHandDialog {...props} />
      return null
    case 'PICK_TARGET':
      return inter.needsCardModal ? <CardPickDialog {...props} inter={inter} /> : null
    default:
      return null
  }
}
