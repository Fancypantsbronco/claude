/* balance.js - simuliert ganze Saisons ohne Grafik und prueft die Balance.
 * Inklusive Evolutions-Phase: Praegungen, 2 Kaempfer pro Team, zufaellige Kartenwahl.
 *
 *   node tools/balance.js              # 100 Saisons a 20 Kaempfe
 *   node tools/balance.js 200 30       # 200 Saisons a 30 Kaempfe
 *
 * Nach jedem Kampf wachsen die Attribute (Learning by Doing) und gehen in den
 * naechsten Kampf mit. Ausgabe fuer ausgewaehlte Kampfnummern:
 * K.O.-Quote, Verteilung der Praegung (groesster Primaer-Stapel), Gesinnung,
 * Token pro Kaempfer und mittlere Attribute.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const C = require('../web/js/combat.js');
const E = require('../web/js/evolution.js');

const state = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'data', 'state.json'), 'utf8'));
const seasons = Number(process.argv[2]) || 100;
const fightsPerSeason = Number(process.argv[3]) || 20;
const checkpoints = [1, 5, 10, 20, 30, 50].filter((n) => n <= fightsPerSeason);

const agg = {};
for (const n of checkpoints) {
  agg[n] = { fights: 0, fightsWithKo: 0, fighters: 0, kos: 0, imprint: {}, alignment: {},
    tokens: Object.fromEntries(C.TOKEN_IDS.map((t) => [t, 0])), stats: Object.fromEntries(C.ATTRS.map((a) => [a, 0])), hit: 0,
    cards: 0, cardCount: {} };
}

let seed = 1;
for (let s = 0; s < seasons; s++) {
  const season = E.newSeason(state);
  for (let n = 1; n <= fightsPerSeason; n++) {
    const fightSeed = seed++;
    const fight = C.createFight(state, fightSeed, E.loadout(season));
    fight.runToEnd();
    const rows = fight.summary();
    const a = agg[n];
    if (a) {
      a.fights += 1;
      if (rows.some((r) => r.ko)) a.fightsWithKo += 1;
    }
    for (const r of rows) {
      if (a) {
        a.fighters += 1;
        if (r.ko) a.kos += 1;
        const imp = r.imprint || 'keine';
        a.imprint[imp] = (a.imprint[imp] || 0) + 1;
        a.alignment[r.alignment] = (a.alignment[r.alignment] || 0) + 1;
        for (const t of C.TOKEN_IDS) a.tokens[t] += r.tokens[t];
        for (const at of C.ATTRS) a.stats[at] += r.stats[at];
        a.hit += C.derive(state.rules, r.stats).hit;
        a.cards += r.cards.length;
        for (const c of r.cards) a.cardCount[c] = (a.cardCount[c] || 0) + 1;
      }
      season.stats[r.id] = r.growth.after;
    }
    // Evolutions-Phase: zufaellige Wahl unter den gezogenen Karten
    const phase = E.runPhase(state, rows, season, fightSeed);
    const pick = C.mulberry32(fightSeed * 7 + 1);
    for (const ev of phase.evolvers) {
      if (ev.offers.length) E.choose(state, season, ev, ev.offers[Math.floor(pick() * ev.offers.length)], n);
    }
  }
}

const pct = (v) => (100 * v).toFixed(1).padStart(5) + ' %';
const dist = (obj, total) => Object.entries(obj).sort((x, y) => y[1] - x[1]).map(([k, v]) => k + ' ' + pct(v / total).trim()).join(' · ');
console.log('%d Saisons a %d Kaempfe (je %d s)\n', seasons, fightsPerSeason, state.rules.duration_s);
for (const n of checkpoints) {
  const a = agg[n];
  console.log('Kampf %d', n);
  console.log('  Kaempfe mit K.O.  %s   K.O. pro Kaempfer %s', pct(a.fightsWithKo / a.fights), pct(a.kos / a.fighters));
  console.log('  Attribute (Mittel) %s   Trefferchance %s', C.ATTRS.map((at) => at.slice(0, 3).toUpperCase() + ' ' + (a.stats[at] / a.fighters).toFixed(2)).join('  '), pct(a.hit / a.fighters).trim());
  console.log('  Praegung           %s', dist(a.imprint, a.fighters));
  console.log('  Gesinnung          %s', dist(a.alignment, a.fighters));
  console.log('  Karten/Kaempfer    %s   haeufigste: %s', (a.cards / a.fighters).toFixed(2), dist(a.cardCount, a.cards || 1));
  console.log('  Token/Kaempfer     %s', C.TOKEN_IDS.filter((t) => a.tokens[t]).map((t) => t + ' ' + (a.tokens[t] / a.fighters).toFixed(1)).join('  '));
}
