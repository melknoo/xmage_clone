// In-Page-Autopilot fuer UI-Tests: spielt Laender/Zauber, greift an, beantwortet Dialoge.
(() => {
  if (window.__auto) return 'already'
  const G = window.__ml.game
  let attacked = new Set()
  let turnSeen = -1
  let actionsThisStep = 0
  let stepKey = ''
  window.__auto = setInterval(() => {
    const g = G.getState()
    const p = g.prompt
    const s = g.state
    if (!p || g.answeredPromptId === p.id || !s) return
    if (s.turn !== turnSeen) { turnSeen = s.turn; attacked = new Set() }
    const key = s.turn + s.step
    if (key !== stepKey) { stepKey = key; actionsThisStep = 0 }
    const ans = (a) => g.answer(a)
    switch (p.kind) {
      case 'ASK': return ans({ bool: !p.mulligan })
      case 'SELECT': {
        if (p.mode === 'attackers') {
          const next = (p.possibleAttackers || []).find((a) => !attacked.has(a))
          if (next) { attacked.add(next); return ans({ uuid: next }) }
          return ans({ bool: true })
        }
        if (p.mode === 'blockers') return ans({ bool: true })
        const acts = new Set(s.actions || [])
        if (actionsThisStep < 5) {
          const land = s.hand.find((c) => acts.has(c.id) && c.types && c.types.includes('LAND'))
          if (land) { actionsThisStep++; return ans({ uuid: land.id }) }
          const me = s.players.find((x) => x.me)
          const cmd = me && me.command.find((c) => acts.has(c.id))
          if (cmd) { actionsThisStep++; return ans({ uuid: cmd.id }) }
          const spell = s.hand.find((c) => acts.has(c.id))
          if (spell) { actionsThisStep++; return ans({ uuid: spell.id }) }
        }
        return ans({ bool: false })
      }
      case 'PICK_TARGET': {
        const open = (p.targets || []).filter((t) => !(p.chosen || []).includes(t))
        if (open.length && (p.required || !(p.chosen || []).length)) return ans({ uuid: open[Math.floor(Math.random() * open.length)] })
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
  }, 350)
  return 'started'
})()
