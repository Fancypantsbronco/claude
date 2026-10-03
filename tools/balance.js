/* balance.js - simuliert viele Kaempfe ohne Grafik und prueft die Balance.
 *
 *   node tools/balance.js            # 500 Kaempfe
 *   node tools/balance.js 2000       # andere Anzahl
 *
 * Ziel fuer fruehe Kaempfe: K.O. sehr selten, viele Chaos-Token, etwas Dunkel.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { createFight, TOKEN_IDS } = require('../web/js/combat.js');

const state = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'data', 'state.json'), 'utf8'));
const runs = Number(process.argv[2]) || 500;

const sum = Object.fromEntries(TOKEN_IDS.map((t) => [t, 0]));
let kos = 0;
let fightsWithKo = 0;
let swings = 0;
let hits = 0;
let minHp = Infinity;
let hpLeft = 0;
let fighterCount = 0;

for (let seed = 1; seed <= runs; seed++) {
  const fight = createFight(state, seed);
  fight.runToEnd();
  let anyKo = false;
  for (const f of fight.summary()) {
    for (const t of TOKEN_IDS) sum[t] += f.tokens[t];
    if (f.ko) { kos += 1; anyKo = true; }
    swings += f.record.swings;
    hits += f.record.hits;
    minHp = Math.min(minHp, f.hp);
    hpLeft += f.hp / f.maxHp;
    fighterCount += 1;
  }
  if (anyKo) fightsWithKo += 1;
}

const pct = (v) => (100 * v).toFixed(1) + ' %';
console.log('Kaempfe:                 %d (je %d s)', runs, state.rules.duration_s);
console.log('Kaempfe mit K.O.:        %s', pct(fightsWithKo / runs));
console.log('K.O. pro Kaempfer:       %s', pct(kos / fighterCount));
console.log('Niedrigste Rest-HP:      %d', minHp);
console.log('Mittlere Rest-HP:        %s', pct(hpLeft / fighterCount));
console.log('Schlaege pro Kaempfer:   %s', (swings / fighterCount).toFixed(1));
console.log('Trefferquote insgesamt:  %s', pct(hits / swings));
console.log('Token pro Kaempfer und Kampf:');
for (const t of TOKEN_IDS) console.log('  %s %s', t.padEnd(8), (sum[t] / fighterCount).toFixed(2));
