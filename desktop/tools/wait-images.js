// Gemeinsamer Warte-Helfer fuer Screenshot-Steps ({"jsFile": "wait-images.js"}): wartet, bis
//  - alle sichtbaren <img> geladen sind (complete && naturalWidth > 0, ohne CardView-Ladeklasse opacity-0),
//  - die Schriften bereit sind (document.fonts.ready + Barlow Condensed / IBM Plex geladen),
// hoechstens window.__args.cap bzw. 25 s. Danach 400 ms Ruhe.
// Ergebnis "Bilder n/m, Schriften ok" (nie mit "Timeout" am Anfang - fehlende Scryfall-Bilder sind kein Fehler).
new Promise((resolve) => {
  const cap = (window.__args && window.__args.cap) || 25000
  const t0 = Date.now()
  const fontsWanted = ['600 16px "Barlow Condensed"', '400 14px "IBM Plex Sans"', '500 11px "IBM Plex Mono"']
  try {
    fontsWanted.forEach((f) => document.fonts.load(f).catch(() => {}))
  } catch {
    /* alte Engines */
  }
  const visible = (i) => {
    const r = i.getBoundingClientRect()
    return r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth
  }
  const done = (i) => i.complete && i.naturalWidth > 0 && !i.classList.contains('opacity-0')
  const fontsOk = () => document.fonts.status === 'loaded'
  const finish = (imgs, timedOut) => {
    // kein requestAnimationFrame: verdeckte Fenster liefern keine Frames
    setTimeout(() => {
      const n = imgs.filter(done).length
      resolve(`Bilder ${n}/${imgs.length}, Schriften ${fontsOk() ? 'ok' : document.fonts.status}${timedOut ? ' (nach ' + cap + ' ms abgebrochen)' : ''}`)
    }, 400)
  }
  const tick = () => {
    const imgs = [...document.images].filter((i) => i.getAttribute('src') && visible(i))
    if ((imgs.every(done) && fontsOk()) || Date.now() - t0 > cap) return finish(imgs, Date.now() - t0 > cap)
    setTimeout(tick, 250)
  }
  tick()
})
