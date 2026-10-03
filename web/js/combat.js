/* combat.js - Kampf-Engine fuer Chaos Arena (Schritt 2).
 *
 * 5 gegen 5, feste Kampfdauer, feste Zeitschritte, Seed fuer wiederholbare Kaempfe.
 * Keine Freund-Feind-Erkennung: Wer im Moment des Schlags im Sichtkegel vor dem
 * Angreifer steht, wird getroffen - egal welches Team.
 *
 * Token pro Schlag:
 *   Treffer auf Gegner    -> Angreifer +1 damage, Opfer +1 tank
 *   Treffer auf Teamkamerad -> Angreifer +1 dark,  Opfer +1 tank
 *   Wurf daneben          -> Angreifer +1 chaos
 *   niemand vor einem     -> Angreifer +1 chaos (Schlag ins Leere)
 *
 * Reine Logik ohne DOM/three.js: laeuft im Browser und unter Node (tools/balance.js).
 */
(function (root) {
  'use strict';

  const TOKEN_IDS = ['damage', 'tank', 'chaos', 'dark'];

  function mulberry32(seed) {
    let a = seed >>> 0;
    return function () {
      a = (a + 0x6d2b79f5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  const wrapAngle = (a) => Math.atan2(Math.sin(a), Math.cos(a));
  // Blickrichtung: heading 0 = +Z, wie rotation.y in three.js -> Richtung (sin, cos)
  const angleTo = (dx, dz) => Math.atan2(dx, dz);

  function emptyStats() {
    return {
      swings: 0, hits: 0, misses: 0, air: 0, friendly_hits: 0, interrupted: 0,
      damage_dealt: 0, damage_taken: 0, hits_taken: 0, distance: 0, kos: 0,
    };
  }

  function createFight(state, seed) {
    const R = state.rules;
    const rng = mulberry32(seed);
    const rand = (a, b) => a + (b - a) * rng();
    const dt = 1 / R.tick_hz;
    const cone = (R.cone_deg * Math.PI) / 180 / 2;
    const noise = (R.heading_noise_deg * Math.PI) / 180;

    // Aufstellung: Teams in je einer Reihe, Gesicht zur Mitte, 1,3 m Abstand
    const byTeam = {};
    for (const r of state.roster) (byTeam[r.team] = byTeam[r.team] || []).push(r);
    const teamIds = Object.keys(byTeam);
    const fighters = [];
    teamIds.forEach((team, ti) => {
      const side = ti === 0 ? -1 : 1;
      byTeam[team].forEach((r, i, list) => {
        const s = R.base_stats;
        const maxHp = R.hp_base + R.hp_per_toughness * s.toughness;
        const x = side * 2.4 + rand(-0.15, 0.15);
        const z = (i - (list.length - 1) / 2) * 1.6 + rand(-0.1, 0.1);
        fighters.push({
          id: r.id, name: r.name, team,
          x, z, px: x, pz: z,
          heading: side < 0 ? Math.PI / 2 : -Math.PI / 2,
          stats: { ...s },
          hp: maxHp, maxHp,
          state: 'idle', stateT: 0,
          think: rand(0.2, 1.0),
          walkPhase: rand(0, Math.PI * 2),
          lastHitT: -10, downT: -1,
          record: emptyStats(),
          tokens: Object.fromEntries(TOKEN_IDS.map((t) => [t, 0])),
        });
      });
    });
    const byId = Object.fromEntries(fighters.map((f) => [f.id, f]));

    const fight = { seed, t: 0, duration: R.duration_s, dt, done: false, fighters, byId, events: [] };

    function emit(ev) {
      ev.t = Math.round(fight.t * 100) / 100;
      fight.events.push(ev);
      return ev;
    }

    function frontTarget(f) {
      let best = null;
      let bestD = Infinity;
      for (const o of fighters) {
        if (o === f || o.state === 'down') continue;
        const dx = o.x - f.x;
        const dz = o.z - f.z;
        const d = Math.hypot(dx, dz);
        if (d > R.reach_m || d >= bestD) continue;
        if (Math.abs(wrapAngle(angleTo(dx, dz) - f.heading)) > cone) continue;
        best = o;
        bestD = d;
      }
      return best;
    }

    // Naechster Kaempfer in der vorderen Haelfte: sie tappen vorwaerts dem Laerm nach.
    function nearestAhead(f) {
      let best = null;
      let bestD = R.sight_m;
      for (const o of fighters) {
        if (o === f || o.state === 'down') continue;
        const dx = o.x - f.x;
        const dz = o.z - f.z;
        const d = Math.hypot(dx, dz);
        if (d >= bestD || Math.abs(wrapAngle(angleTo(dx, dz) - f.heading)) > Math.PI / 2) continue;
        best = o;
        bestD = d;
      }
      return best;
    }

    function setState(f, s) {
      f.state = s;
      f.stateT = 0;
    }

    function give(f, token, out) {
      f.tokens[token] += 1;
      out.push({ fighter: f.id, token });
    }

    function decide(f) {
      if (frontTarget(f) || rng() < R.flail_chance) {
        setState(f, 'windup');
        f.record.swings += 1;
        return;
      }
            const o = nearestAhead(f);
      f.heading = o ? angleTo(o.x - f.x, o.z - f.z) + rand(-noise, noise) : f.heading + rand(-1.6, 1.6);
      setState(f, 'walk');
      f.think = rand(R.think_min_s, R.think_max_s);
    }

    function strike(f) {
      const victim = frontTarget(f);
      const tokens = [];
      if (!victim) {
        f.record.air += 1;
        give(f, 'chaos', tokens);
        return emit({ type: 'air', attacker: f.id, tokens });
      }
      const roll = Math.floor(rng() * 100) + 1;
      const threshold = Math.round(R.hit_chance * 100);
      if (roll > threshold) {
        f.record.misses += 1;
        give(f, 'chaos', tokens);
        return emit({ type: 'miss', attacker: f.id, victim: victim.id, roll, threshold, tokens });
      }
      const dmg = (R.damage_min + Math.floor(rng() * (R.damage_max - R.damage_min + 1))) * f.stats.strength;
      const friendly = victim.team === f.team;
      f.record.hits += 1;
      f.record.damage_dealt += dmg;
      if (friendly) f.record.friendly_hits += 1;
      give(f, friendly ? 'dark' : 'damage', tokens);
      victim.record.hits_taken += 1;
      victim.record.damage_taken += dmg;
      give(victim, 'tank', tokens);
      victim.hp = Math.max(0, victim.hp - dmg);
      // Rueckstoss: das Opfer taumelt weg und dreht sich blind um
      const away = angleTo(victim.x - f.x, victim.z - f.z);
      victim.x += Math.sin(away) * R.knockback_m;
      victim.z += Math.cos(away) * R.knockback_m;
      victim.heading = wrapAngle(victim.heading + rand(-2, 2));
      victim.lastHitT = fight.t;
      if (victim.state === 'windup') victim.record.interrupted += 1;
      const ev = emit({ type: 'hit', attacker: f.id, victim: victim.id, friendly, dmg, roll, threshold, hp: victim.hp, tokens });
      if (victim.hp <= 0) {
        setState(victim, 'down');
        victim.downT = fight.t;
        f.record.kos += 1;
        emit({ type: 'ko', attacker: f.id, victim: victim.id, tokens: [] });
      } else {
        setState(victim, 'stagger');
      }
      return ev;
    }

    function stepFighter(f) {
      f.px = f.x;
      f.pz = f.z;
      if (f.state === 'down') return;
      f.stateT += dt;
      switch (f.state) {
        case 'stagger':
          if (f.stateT >= R.stagger_s) { setState(f, 'idle'); f.think = rand(0.1, 0.5); }
          break;
        case 'windup':
          if (f.stateT >= R.windup_s) { strike(f); if (f.state === 'windup') setState(f, 'recover'); }
          break;
        case 'recover':
          if (f.stateT >= R.recover_s) { setState(f, 'idle'); f.think = rand(R.think_min_s, R.think_max_s); }
          break;
        case 'walk': {
          const v = R.move_speed_mps * (0.8 + 0.2 * f.stats.agility) * dt;
          f.x += Math.sin(f.heading) * v;
          f.z += Math.cos(f.heading) * v;
          f.walkPhase += v * 5.5;
          f.record.distance += v;
          f.think -= dt;
          if (f.think <= 0) decide(f);
          break;
        }
        default: // idle
          f.think -= dt;
          if (f.think <= 0) decide(f);
      }
    }

    function separate() {
      const minD = R.body_radius_m * 2;
      for (let i = 0; i < fighters.length; i++) {
        for (let j = i + 1; j < fighters.length; j++) {
          const a = fighters[i];
          const b = fighters[j];
          const dx = b.x - a.x;
          const dz = b.z - a.z;
          const d = Math.hypot(dx, dz) || 0.001;
          if (d >= minD) continue;
          const push = (minD - d) / 2;
          const ax = a.state === 'down' ? 0 : 1;
          const bx = b.state === 'down' ? 0 : 1;
          const share = ax + bx || 1;
          a.x -= (dx / d) * push * 2 * (ax / share);
          a.z -= (dz / d) * push * 2 * (ax / share);
          b.x += (dx / d) * push * 2 * (bx / share);
          b.z += (dz / d) * push * 2 * (bx / share);
        }
      }
      const lim = R.arena_half_m;
      for (const f of fighters) {
        if (Math.abs(f.x) > lim || Math.abs(f.z) > lim) {
          f.x = Math.max(-lim, Math.min(lim, f.x));
          f.z = Math.max(-lim, Math.min(lim, f.z));
          if (f.state === 'walk') f.heading = angleTo(-f.x, -f.z) + rand(-noise, noise);
        }
      }
    }

    fight.step = function () {
      if (fight.done) return [];
      const before = fight.events.length;
      fight.t += dt;
      for (const f of fighters) stepFighter(f);
      separate();
      if (fight.t >= fight.duration - 1e-9) {
        fight.done = true;
        emit({ type: 'end', tokens: [] });
      }
      return fight.events.slice(before);
    };

    fight.runToEnd = function () {
      while (!fight.done) fight.step();
    };

    fight.summary = function () {
      return fighters.map((f) => ({
        id: f.id, name: f.name, team: f.team, hp: f.hp, maxHp: f.maxHp,
        ko: f.state === 'down',
        record: { ...f.record, distance: Math.round(f.record.distance * 10) / 10 },
        tokens: { ...f.tokens },
        top: topStack(f.tokens),
      }));
    };

    return fight;
  }

  function topStack(tokens) {
    let best = null;
    for (const id of TOKEN_IDS) if (tokens[id] > 0 && (!best || tokens[id] > tokens[best])) best = id;
    return best;
  }

  const api = { createFight, topStack, mulberry32, TOKEN_IDS };
  root.ChaosCombat = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
