// Prototyp-Aufnahmen ({"jsFile": "proto-ready.js"}): wartet, bis der Design-Prototyp fertig gerendert ist
// (#dc-root da, kein .sc-dc-streaming, lucide geladen, Schriften geladen, Buehne 1680/1280 gefunden, alle Bilder der
// Buehne geladen; hoechstens 30 s), markiert die Buehne mit data-proto-stage und liefert ihr Rechteck.
// Danach {"shot": ..., "clip": "[data-proto-stage]"}.
new Promise((resolve) => {
  const cap = (window.__args && window.__args.cap) || 30000
  const t0 = Date.now()
  const want = window.__args && window.__args.size ? String(window.__args.size) : null
  const stageOf = () =>
    [...document.querySelectorAll('div')].find(
      (d) => /^(1680|1280)px$/.test(d.style.width) && /scale\(/.test(d.style.transform) && (!want || d.style.width === want + 'px'),
    )
  const imgOk = (i) => i.getAttribute('src') && i.complete && i.naturalWidth > 0
  const tick = () => {
    const root = document.querySelector('#dc-root')
    const streaming = document.documentElement.classList.contains('sc-dc-streaming')
    const stage = stageOf()
    const imgs = stage ? [...stage.querySelectorAll('img')] : []
    const pending = imgs.filter((i) => !imgOk(i))
    const ready = root && !streaming && window.lucide && document.fonts.status === 'loaded' && stage && pending.length === 0
    const late = Date.now() - t0 > cap
    if (ready || late) {
      document.querySelectorAll('[data-proto-stage]').forEach((e) => e.removeAttribute('data-proto-stage'))
      if (!stage) return resolve('kein Prototyp-Buehne gefunden')
      stage.setAttribute('data-proto-stage', '1')
      return setTimeout(() => {
          const r = stage.getBoundingClientRect()
          resolve(
            `${late ? 'teilweise' : 'bereit'} ${stage.style.width} @${r.x},${r.y} ${r.width}x${r.height} Bilder ${imgs.length - pending.length}/${imgs.length}` +
              (late ? ` (fehlend: ${pending.length}, streaming=${streaming}, lucide=${!!window.lucide}, fonts=${document.fonts.status})` : ''),
          )
        }, 400)
    }
    setTimeout(tick, 250)
  }
  tick()
})
