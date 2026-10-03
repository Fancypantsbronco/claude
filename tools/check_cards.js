/* check_cards.js - prueft, dass jede Karte im Kampf tatsaechlich wirkt.
 *
 *   node tools/check_cards.js
 *
 * Fuer jede Karte bekommt Kaempfer A1 (und fuer den Berserker auch erhoehte
 * Staerke) die Karte; ueber viele Seeds wird gemessen, ob der erwartete Effekt
 * im Kampfprotokoll auftaucht.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const C = require('../web/js/combat.js');

const state = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'data', 'state.json'), 'utf8'));
const SEEDS = 60;

function run(cards, measure) {
  let total = 0;
  for (let seed = 1; seed <= SEEDS; seed++) {
    const fight = C.createFight(state, seed, { A1: { cards } });
    fight.runToEnd();
    total += measure(fight, fight.byId.A1);
  }
  return total / SEEDS;
}

const base = (fn) => run([], fn);
const checks = [
  ['hornhaut', 'Schaden pro erlittenem Treffer', (f, a) => (a.record.hits_taken ? a.record.damage_taken / a.record.hits_taken : 0), (c, b) => c < b * 0.9],
  ['hornhaut', 'Strecke (m)', (f, a) => a.record.distance, (c, b) => c < b],
  ['schmerz-echo', 'zurückgegebener Schaden', (f, a) => a.record.reflected, (c) => c > 0],
  ['verraeter', 'Selbstheilung', (f, a) => a.record.healed, (c) => c > 0],
  ['berserker', 'Treffer pro Schlag', (f, a) => (a.record.swings ? a.record.hits / a.record.swings : 0), (c, b) => c > b],
  ['tornado-faust', 'Windstöße', (f) => f.events.filter((e) => e.type === 'gust' && e.attacker === 'A1').length, (c) => c > 0],
  ['brillen-traeger', 'Friendly Fire', (f, a) => a.record.friendly_hits + a.record.glances, (c, b) => c < b * 0.5],
  ['brillen-traeger', 'Trefferquote', (f, a) => (a.record.swings ? a.record.hits / a.record.swings : 0), (c, b) => c > b],
  ['blutdurst', 'Treffer auf angeschlagene Ziele (LP < 70 %)', (f) => f.events.filter((e) => e.type === 'hit' && e.attacker === 'A1' && e.hp / f.byId[e.victim].maxHp < 0.7).length, (c, b) => c >= b],
  ['ochsenlunge', 'max. Lebenspunkte', (f, a) => a.maxHp, (c, b) => c === b + 10],
  ['adrenalin', 'Schläge', (f, a) => a.record.swings, (c, b) => c > b],
];

let failed = 0;
for (const [card, label, fn, ok] of checks) {
  const b = base(fn);
  const c = run([card], fn);
  const pass = ok(c, b);
  if (!pass) failed += 1;
  console.log('%s %s · %s: ohne %s, mit %s', pass ? 'OK  ' : 'FAIL', card.padEnd(16), label, b.toFixed(2), c.toFixed(2));
}
process.exitCode = failed ? 1 : 0;
