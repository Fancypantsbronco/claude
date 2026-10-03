/* tokens.js - Prototyp des Token-Systems (Schritt 1).
 * Ein Wurf = ein unsichtbarer Schlag. Treffer -> on_hit-Token, Daneben -> on_miss-Token.
 * Reine Logik ohne DOM, damit sie auch unter Node getestet werden kann.
 */
(function (root) {
  'use strict';

  function fill(template, values) {
    return template.replace(/\{(\w+)\}/g, (m, key) => (key in values ? values[key] : m));
  }

  function createSession(state, rng) {
    const proto = state.prototype;
    const random = rng || Math.random;
    const tokenIds = state.tokens.map((t) => t.id);
    const tokenLabel = Object.fromEntries(state.tokens.map((t) => [t.id, t.label]));
    const zero = () => Object.fromEntries(tokenIds.map((id) => [id, 0]));

    let round, turn, totals, perFighter, history;

    function reset() {
      round = 0;
      turn = 0;
      totals = zero();
      perFighter = Object.fromEntries(proto.turn_order.map((id) => [id, zero()]));
      history = [];
    }

    function roll() {
      const order = proto.turn_order;
      const attacker = state.teams[order[turn % order.length]];
      turn += 1;
      round += 1;
      const value = random();
      const hit = value < proto.hit_chance;
      const token = hit ? proto.on_hit : proto.on_miss;
      totals[token] += 1;
      perFighter[attacker.id][token] += 1;
      const event = {
        round,
        attacker: attacker.id,
        hit,
        token,
        roll: Math.floor(value * 100) + 1,           // W100: 1..100
        threshold: Math.round(proto.hit_chance * 100), // Treffer bei Wurf <= Schwelle
        swingText: fill(proto.log_templates.swing, { fighter: attacker.fighter }),
        resultText: fill(proto.log_templates[hit ? 'hit' : 'miss'], { token: tokenLabel[token] }),
      };
      history.push(event);
      return event;
    }

    reset();
    return {
      roll,
      reset,
      get round() { return round; },
      get totals() { return { ...totals }; },
      get perFighter() { return JSON.parse(JSON.stringify(perFighter)); },
      get history() { return history.slice(); },
      get total() { return Object.values(totals).reduce((a, b) => a + b, 0); },
    };
  }

  const api = { createSession, fill };
  root.ChaosTokens = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
