/* evolution.js - Evolutions-Phase nach dem Kampf (Schritt 4).
 *
 * 1. Token-Abrechnung: Der groesste Stapel aus state.evolution.imprint_tokens
 *    (Damage, Tank, Chaos, Dunkel) wird zu 1 permanenten Praegung. Gleichstand
 *    entscheidet der Zufall. Jeder Teilnehmer bekommt zusaetzlich 1 Neutral-Praegung.
 * 2. Charakter-Auswahl: pro Team exakt 2 zufaellige Kaempfer.
 * 3. Kartenziehung: pro gewaehltem Kaempfer 3 verschiedene Karten aus dem Pool,
 *    die er mit seinen Praegungen bezahlen kann. Bezahlt wird erst beim Waehlen.
 *
 * Saison-Zustand (vom Aufrufer gehalten):
 *   season = { stats: {id: {...}}, cards: {id: [cardId]}, wallets: {id: {...}}, history: [] }
 *
 * Reine Logik ohne DOM: laeuft im Browser und unter Node (tools/balance.js).
 */
(function (root) {
  'use strict';

  const combat = root.ChaosCombat || (typeof require !== 'undefined' ? require('./combat.js') : null);

  function walletIds(state) {
    return [...state.evolution.imprint_tokens, 'neutral'];
  }

  function emptyWallet(state) {
    return Object.fromEntries(walletIds(state).map((t) => [t, 0]));
  }

  function newSeason(state) {
    const season = { stats: {}, cards: {}, wallets: {}, history: [], fights: 0 };
    for (const r of state.roster) {
      season.cards[r.id] = [];
      season.wallets[r.id] = emptyWallet(state);
    }
    return season;
  }

  function loadout(season) {
    const out = {};
    for (const id of Object.keys(season.wallets)) out[id] = { stats: season.stats[id], cards: season.cards[id] };
    return out;
  }

  function shuffle(list, rng) {
    const a = list.slice();
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(rng() * (i + 1));
      [a[i], a[j]] = [a[j], a[i]];
    }
    return a;
  }

  // 1. Token -> Praegung
  function settle(state, rows, season, rng) {
    const E = state.evolution;
    const out = {};
    for (const row of rows) {
      const counts = Object.fromEntries(E.imprint_tokens.map((t) => [t, row.tokens[t] || 0]));
      const max = Math.max(...Object.values(counts));
      const tied = E.imprint_tokens.filter((t) => counts[t] === max && max > 0);
      const imprint = tied.length ? tied[Math.floor(rng() * tied.length)] : null;
      const gained = { neutral: E.neutral_per_fight };
      if (imprint) gained[imprint] = (gained[imprint] || 0) + 1;
      const wallet = season.wallets[row.id] || (season.wallets[row.id] = emptyWallet(state));
      for (const [t, n] of Object.entries(gained)) wallet[t] += n;
      out[row.id] = { counts, imprint, tied: tied.length > 1, gained, wallet: { ...wallet } };
    }
    return out;
  }

  // 2. Pro Team n zufaellige Kaempfer
  function pickEvolvers(state, rng) {
    const byTeam = {};
    for (const r of state.roster) (byTeam[r.team] = byTeam[r.team] || []).push(r.id);
    return Object.values(byTeam).flatMap((ids) => shuffle(ids, rng).slice(0, state.evolution.evolvers_per_team));
  }

  // Erste bezahlbare Kostenvariante (Karten koennen Alternativen haben, z. B. Neutral ODER Chaos)
  function payable(card, wallet) {
    return card.costs.find((cost) => Object.entries(cost).every(([t, n]) => (wallet[t] || 0) >= n)) || null;
  }

  function allowed(state, card, owned) {
    if (!card.stackable && owned.includes(card.id)) return false;
    const byId = Object.fromEntries(state.cards.map((c) => [c.id, c]));
    if ((card.excludes || []).some((x) => owned.includes(x))) return false;
    return !owned.some((o) => ((byId[o] || {}).excludes || []).includes(card.id));
  }

  // 3. Bis zu 3 verschiedene bezahlbare Karten
  function drawOffers(state, wallet, owned, rng) {
    const pool = state.cards.filter((c) => payable(c, wallet) && allowed(state, c, owned));
    return shuffle(pool, rng).slice(0, state.evolution.cards_drawn).map((c) => c.id);
  }

  // Ganze Phase: Abrechnung, Auswahl, Ziehung. Seed = Kampf-Seed, damit wiederholbar.
  function runPhase(state, rows, season, seed) {
    const rng = combat.mulberry32((seed ^ 0x9e3779b9) >>> 0);
    const settlement = settle(state, rows, season, rng);
    const evolvers = pickEvolvers(state, rng).map((id) => ({
      id, offers: drawOffers(state, season.wallets[id], season.cards[id], rng), chosen: null, paid: null,
    }));
    return { settlement, evolvers };
  }

  // Karte waehlen: bezahlen und dem Kaempfer geben. Gibt die bezahlte Variante zurueck.
  function choose(state, season, evolver, cardId, fightNo) {
    if (evolver.chosen) throw new Error('Evolution schon entschieden');
    const card = state.cards.find((c) => c.id === cardId);
    const wallet = season.wallets[evolver.id];
    const cost = card && evolver.offers.includes(cardId) ? payable(card, wallet) : null;
    if (!cost) throw new Error('Karte nicht bezahlbar: ' + cardId);
    for (const [t, n] of Object.entries(cost)) wallet[t] -= n;
    season.cards[evolver.id].push(cardId);
    evolver.chosen = cardId;
    evolver.paid = cost;
    season.history.push({ fight: fightNo, id: evolver.id, card: cardId, paid: cost });
    return cost;
  }

  function skip(evolver) {
    if (!evolver.chosen) evolver.chosen = 'skip';
  }

  const api = { newSeason, loadout, settle, pickEvolvers, drawOffers, payable, runPhase, choose, skip, emptyWallet, walletIds };
  root.ChaosEvolution = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
