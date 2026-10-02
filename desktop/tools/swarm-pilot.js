// In-Page-Pilot fuer steps-swarm.json: behaelt die Starthand, spielt nur Laender, passt sonst.
// Angriffs-Prompts beantwortet er NICHT - die steps markieren dort per Shift+Klick (Mehrfach-Angriff).
(() => {
  if (window.__swarm) return 'already'
  const G = window.__ml.game
  window.__swarm = setInterval(() => {
    const g = G.getState()
    const p = g.prompt
    const s = g.state
    if (!p || g.answeredPromptId === p.id || !s) return
    const ans = (a) => g.answer(a)
    switch (p.kind) {
      case 'ASK': return ans({ bool: !p.mulligan })
      case 'SELECT': {
        if (p.mode === 'attackers') return
        if (p.mode === 'blockers') return ans({ bool: true })
        const acts = new Set(s.actions || [])
        const land = s.hand.find((c) => acts.has(c.id) && c.types && c.types.includes('LAND'))
        if (land) return ans({ uuid: land.id })
        return ans({ bool: false })
      }
      case 'PICK_TARGET': {
        const open = (p.targets || []).filter((t) => !(p.chosen || []).includes(t))
        if (open.length && (p.required || !(p.chosen || []).length)) return ans({ uuid: open[0] })
        return ans({ bool: false })
      }
      case 'PICK_ABILITY': case 'CHOOSE_ABILITY': case 'CHOOSE_MODE':
        return p.choices && p.choices.length ? ans({ uuid: p.choices[0].id }) : ans({ bool: false })
      case 'CHOOSE_CHOICE': return ans({ str: p.choice.keyed ? p.choice.items[0].key : p.choice.items[0].value })
      case 'AMOUNT': return ans({ int: p.min || 0 })
      case 'MULTI_AMOUNT': return ans({ str: p.items.map((i) => i.value).join(' ') })
      case 'CHOOSE_PILE': return ans({ bool: true })
      case 'PLAY_MANA': return g.autoPay()
      case 'PLAY_X_MANA': return ans({ bool: false })
    }
  }, 300)
  return 'started'
})()
