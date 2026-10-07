// In-Page-Pilot fuer Szenario-Screenshots (steps-necro/-attack-undo/-gemstone): behaelt die Starthand, spielt nur
// Laender, passt sonst. Was die steps selbst ueber die Oberflaeche bedienen, laesst er offen (window.__hold):
//   { attack: true }      Angriffs-Prompt und Verteidiger-Wahl nicht beantworten
//   { ability: true }     CHOOSE_ABILITY (Faehigkeiten-Picker) nicht beantworten
//   { openingHand: true } Starthand-Aktion (Gemstone Caverns) nicht beantworten
//   { necro: true }       im eigenen Hauptsegment Necropotence anklicken (einmal), danach Picker offen lassen
//   { convoke: true }     Szenario convoke: Blaze (X=2), dann Guardian of Vitu-Ghazi wirken; Mana-Prompt mit
//                         Einberufen offen lassen. Zusaetzlich (von den steps zurueckgesetzt):
//                         firstMain / blazeTarget / afterGuardian = an dieser Stelle warten
//   { blockers: true }    Blocker-Prompt nicht beantworten (Bots greifen an -> Screenshot "Blocker waehlen")
//   { stack: N }          bei Prioritaet mit >= N Stapelobjekten nicht passen (F10-Knopf, Stapel-Screenshot)
//   { cast: true }        wie autoplay.js auch Commander/Zauber wirken (aber nie angreifen) - fuer eigene Blocker
// Die steps duerfen window.__hold spaeter aendern (gleiches Objekt), z. B. window.__hold.stack = 0.
(() => {
  if (window.__pilot) return 'already'
  const G = window.__ml.game
  const hold = window.__hold || {}
  let necroClicked = false
  let blazeCast = false
  let guardianCast = false
  let castKey = ''
  let castCount = 0
  window.__pilot = setInterval(() => {
    const g = G.getState()
    const p = g.prompt
    const s = g.state
    if (!p || g.answeredPromptId === p.id || !s) return
    const ans = (a) => g.answer(a)
    const me = s.players.find((x) => x.me)
    switch (p.kind) {
      case 'ASK':
        if (p.mulligan) return ans({ bool: false })
        if (hold.openingHand && !s.step && /^Put .+ battlefield\?$/i.test(p.messageText || '')) return
        return ans({ bool: true })
      case 'SELECT': {
        if (p.mode === 'attackers') return hold.attack ? undefined : ans({ bool: true })
        if (p.mode === 'blockers') return hold.blockers ? undefined : ans({ bool: true })
        const acts = new Set(s.actions || [])
        if (hold.stack && p.mode === 'priority' && s.stack.length >= hold.stack) return
        if (hold.convoke && me && me.active && s.step === 'PRECOMBAT_MAIN' && s.stack.length === 0) {
          if (!blazeCast) {
            if (hold.firstMain) return
            const blaze = s.hand.find((c) => c.name === 'Blaze' && acts.has(c.id))
            if (blaze) {
              blazeCast = true
              return ans({ uuid: blaze.id })
            }
          } else if (!guardianCast) {
            const gu = s.hand.find((c) => c.name === 'Guardian of Vitu-Ghazi' && acts.has(c.id))
            if (gu) {
              guardianCast = true
              return ans({ uuid: gu.id })
            }
          } else if (hold.afterGuardian) return
        }
        if (hold.necro && !necroClicked && me && me.active && s.step === 'PRECOMBAT_MAIN' && s.stack.length === 0) {
          const necro = me.battlefield.find((c) => c.name === 'Necropotence' && acts.has(c.id))
          if (necro) {
            necroClicked = true
            return ans({ uuid: necro.id })
          }
        }
        const land = s.hand.find((c) => acts.has(c.id) && c.types && c.types.includes('LAND'))
        if (land) return ans({ uuid: land.id })
        if (hold.cast && me && me.active && s.stack.length === 0 && /MAIN/.test(s.step || '')) {
          const key = s.turn + s.step
          if (key !== castKey) {
            castKey = key
            castCount = 0
          }
          if (castCount < 4) {
            const cmd = me.command.find((c) => acts.has(c.id))
            const spell = cmd || s.hand.find((c) => acts.has(c.id))
            if (spell) {
              castCount++
              return ans({ uuid: spell.id })
            }
          }
        }
        return ans({ bool: false })
      }
      case 'PICK_TARGET': {
        if (hold.attack && p.defenderPick) return
        if (hold.blazeTarget && s.stack.length && s.stack[0].name === 'Blaze') return
        const open = (p.targets || []).filter((t) => !(p.chosen || []).includes(t))
        if (open.length && (p.required || !(p.chosen || []).length)) return ans({ uuid: open[0] })
        return ans({ bool: false })
      }
      case 'CHOOSE_ABILITY':
        if (hold.ability) return
        return p.choices && p.choices.length ? ans({ uuid: p.choices[0].id }) : ans({ bool: false })
      case 'PICK_ABILITY': case 'CHOOSE_MODE':
        return p.choices && p.choices.length ? ans({ uuid: p.choices[0].id }) : ans({ bool: false })
      case 'CHOOSE_CHOICE': if (p.choice.groups) return; return ans({ str: p.choice.keyed ? p.choice.items[0].key : p.choice.items[0].value })
      case 'AMOUNT': return ans({ int: hold.convoke ? Math.min(2, p.max || 2) : p.min || 0 })
      case 'MULTI_AMOUNT': return ans({ str: p.items.map((i) => i.value).join(' ') })
      case 'CHOOSE_PILE': return ans({ bool: true })
      case 'PLAY_MANA':
        if (hold.convoke && p.specialBtn) return
        return g.autoPay()
      case 'PLAY_X_MANA': return ans({ bool: false })
    }
  }, 300)
  return 'started'
})()
