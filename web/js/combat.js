/* combat.js - Kampf-Engine fuer Chaos Arena (Schritt 3).
 *
 * 5 gegen 5, feste Kampfdauer, feste Zeitschritte, Seed fuer wiederholbare Kaempfe.
 * Jeder Kaempfer bringt vier Kern-Attribute mit (Start: alle 1):
 *   Staerke      -> Schaden und Rueckstoss
 *   Ausdauer     -> Lebenspunkte und Kraftreserve (danach Erschoepfung)
 *   Bewusstsein  -> Sichtradius und Freund-Feind-Erkennung (bei 1: keine)
 *   Geschick     -> Trefferquote und Ausweichen
 *
 * Token (Regeln und Zahlen in data/state.json -> tokens / rules):
 *   Primaer:   damage, tank, support, chaos
 *   Gesinnung: dark, light, neutral
 *   Sekundaer: scrap, tech, mutation  (noch ohne Quelle: braucht Gegenstaende/Umwelt)
 *
 * Nach dem Kampf waechst jedes Attribut ein kleines Stueck durch das, was der
 * Kaempfer getan hat (growth(), "Learning by Doing").
 *
 * Karten (state.cards) aendern das Verhalten ueber params, siehe cardMods().
 *
 * Reine Logik ohne DOM/three.js: laeuft im Browser und unter Node (tools/balance.js).
 */
(function (root) {
  'use strict';

  const PRIMARY = ['damage', 'tank', 'support', 'chaos'];
  const ALIGNMENT = ['dark', 'light', 'neutral'];
  const SECONDARY = ['scrap', 'tech', 'mutation'];
  const TOKEN_IDS = [...PRIMARY, ...ALIGNMENT, ...SECONDARY];
  const ATTRS = ['strength', 'stamina', 'awareness', 'dexterity'];

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
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const round1 = (v) => Math.round(v * 10) / 10;

  function emptyRecord() {
    return {
      swings: 0, hits: 0, misses: 0, air: 0, dodged: 0, glances: 0, friendly_hits: 0,
      interrupted: 0, held_back: 0, intercepts: 0, targeted: 0, dodges: 0,
      damage_dealt: 0, damage_taken: 0, hits_taken: 0, distance: 0, kos: 0, exhausted_s: 0, healed: 0, reflected: 0,
    };
  }

  // Abgeleitete Kampfwerte aus den Attributen
  function derive(R, s) {
    return {
      maxHp: round1(R.hp_base + R.hp_per_stamina * s.stamina),
      staminaMax: round1(R.stamina_base + R.stamina_per_stamina * s.stamina),
      hit: clamp(R.hit_base + R.hit_per_dex * (s.dexterity - 1), 0.05, R.hit_max),
      dodge: clamp(R.dodge_per_dex * (s.dexterity - 1), 0, R.dodge_max),
      sight: R.sight_base_m + R.sight_per_awareness * (s.awareness - 1),
      recognize: clamp((s.awareness - 1) / (R.recognize_full_at - 1), 0, 1),
      knockback: R.knockback_base_m * s.strength,
    };
  }

  // Summe aller Karten-Effekte eines Kaempfers (stapelbare Karten zaehlen mehrfach)
  function cardMods(state, cardIds) {
    const m = {
      damage_taken_mult: 1, move_mult: 1, reflect: 0, friendly_damage_mult: 1, friendly_heal: 0,
      aoe_radius_m: 0, gust_radius_m: 0, gust_m: 0, recognize: null, hit_bonus: 0,
      bloodlust: false, max_hp_bonus: 0, attack_speed_mult: 1,
    };
    const byId = Object.fromEntries((state.cards || []).map((c) => [c.id, c]));
    for (const id of cardIds || []) {
      const p = (byId[id] || {}).params || {};
      for (const [k, v] of Object.entries(p)) {
        if (k.endsWith('_mult')) m[k] *= v;
        else if (k === 'recognize' || k === 'bloodlust') m[k] = k === 'recognize' && m.recognize === 0 ? 0 : v; // Berserker sticht Brille
        else if (k === 'aoe_radius_m' || k === 'gust_radius_m' || k === 'gust_m') m[k] = Math.max(m[k], v);
        else m[k] += v;
      }
    }
    return m;
  }

  // loadout: { [id]: { stats, cards } } aus der Saison; fehlt etwas, gelten Startwerte
  function createFight(state, seed, loadout) {
    const R = state.rules;
    const rng = mulberry32(seed);
    const rand = (a, b) => a + (b - a) * rng();
    const dt = 1 / R.tick_hz;
    const cone = (R.cone_deg * Math.PI) / 180 / 2;
    const noise = (R.heading_noise_deg * Math.PI) / 180;

    // Aufstellung: Teams in je einer Reihe, Gesicht zur Mitte
    const byTeam = {};
    for (const r of state.roster) (byTeam[r.team] = byTeam[r.team] || []).push(r);
    const fighters = [];
    Object.keys(byTeam).forEach((team, ti) => {
      const side = ti === 0 ? -1 : 1;
      byTeam[team].forEach((r, i, list) => {
        const lo = (loadout && loadout[r.id]) || {};
        const stats = { ...R.start_stats, ...(lo.stats || {}) };
        const cards = (lo.cards || []).slice();
        const mods = cardMods(state, cards);
        const d = derive(R, stats);
        d.maxHp = round1(d.maxHp + mods.max_hp_bonus);
        d.hit = clamp(d.hit + mods.hit_bonus, 0.05, 0.95);
        if (mods.recognize !== null) d.recognize = mods.recognize;
        const x = side * 2.4 + rand(-0.15, 0.15);
        const z = (i - (list.length - 1) / 2) * 1.6 + rand(-0.1, 0.1);
        fighters.push({
          id: r.id, name: r.name, team,
          x, z, px: x, pz: z,
          heading: side < 0 ? Math.PI / 2 : -Math.PI / 2,
          stats, d, cards, mods,
          hp: d.maxHp, maxHp: d.maxHp,
          stamina: d.staminaMax, exhausted: false,
          state: 'idle', stateT: 0, windup: R.windup_s / mods.attack_speed_mult, intended: null,
          think: rand(0.2, 1.0),
          walkPhase: rand(0, Math.PI * 2),
          lastHitT: -10, downT: -1,
          wanderM: 0, shieldT: 0,
          record: emptyRecord(),
          tokens: Object.fromEntries(TOKEN_IDS.map((t) => [t, 0])),
        });
      });
    });
    const byId = Object.fromEntries(fighters.map((f) => [f.id, f]));

    const fight = { seed, t: 0, duration: R.duration_s, dt, done: false, fighters, byId, events: [] };
    let nextNeutral = R.neutral_every_s;

    function emit(ev) {
      ev.t = Math.round(fight.t * 100) / 100;
      ev.tokens = ev.tokens || [];
      fight.events.push(ev);
      return ev;
    }
    function give(f, token, out, n = 1) {
      f.tokens[token] += n;
      out.push({ fighter: f.id, token, n });
    }
    const alive = (o) => o.state !== 'down';
    const dist = (a, b) => Math.hypot(a.x - b.x, a.z - b.z);

    // Wer steht im Sichtkegel in Reichweite? Blutdurst: niedrigste LP-Quote statt naechster.
    function frontTarget(f, enemiesOnly) {
      let best = null;
      let bestScore = Infinity;
      for (const o of fighters) {
        if (o === f || !alive(o) || (enemiesOnly && o.team === f.team)) continue;
        const dx = o.x - f.x;
        const dz = o.z - f.z;
        const d = Math.hypot(dx, dz);
        if (d > R.reach_m) continue;
        if (Math.abs(wrapAngle(angleTo(dx, dz) - f.heading)) > cone) continue;
        const score = f.mods.bloodlust ? o.hp / o.maxHp + d * 0.01 : d;
        if (score < bestScore) { best = o; bestScore = score; }
      }
      return best;
    }

    // Kurzsichtiger Berserker: alle im Umkreis, egal in welche Richtung
    function aoeTargets(f) {
      return fighters.filter((o) => o !== f && alive(o) && dist(f, o) <= f.mods.aoe_radius_m);
    }

    // Naechster sichtbarer Kaempfer in der vorderen Haelfte. Mit Erkennung: nur Feinde.
    function nearestAhead(f, enemiesOnly) {
      let best = null;
      let bestD = Infinity;
      for (const o of fighters) {
        if (o === f || !alive(o) || (enemiesOnly && o.team === f.team)) continue;
        const dx = o.x - f.x;
        const dz = o.z - f.z;
        const d = Math.hypot(dx, dz);
        if (d >= f.d.sight || Math.abs(wrapAngle(angleTo(dx, dz) - f.heading)) > Math.PI / 2) continue;
        const score = f.mods.bloodlust ? o.hp / o.maxHp : d;
        if (score >= bestD) continue;
        best = o;
        bestD = score;
      }
      return best;
    }

    function setState(f, s) {
      f.state = s;
      f.stateT = 0;
    }

    function spend(f, amount) {
      f.stamina -= amount;
      if (f.stamina <= 0) {
        f.stamina = 0;
        f.exhausted = true;
      }
    }

    function walk(f, heading) {
      f.heading = heading;
      setState(f, 'walk');
      f.think = rand(R.think_min_s, R.think_max_s);
    }

    function decide(f) {
      const recognizes = rng() < f.d.recognize;
      const enemyFront = recognizes ? frontTarget(f, true) : null;
      const front = enemyFront || (f.mods.aoe_radius_m ? aoeTargets(f)[0] : null) || frontTarget(f, false);
      if (front && front.team === f.team && recognizes && !f.mods.aoe_radius_m) {
        // Bewusstsein: erkennt den Teamkameraden und dreht ab
        f.record.held_back += 1;
        walk(f, f.heading + (rng() < 0.5 ? -1 : 1) * rand(1.2, 2.2));
        return;
      }
      if (front || rng() < R.flail_chance) {
        f.intended = front ? front.id : null;
        f.focus = recognizes; // erkannt beim Ausholen -> trifft beim Zuschlagen nur Feinde
        f.windup = (R.windup_s / f.mods.attack_speed_mult) * (f.exhausted ? R.exhausted_slow_factor : 1);
        setState(f, 'windup');
        f.record.swings += 1;
        spend(f, R.stamina_swing_cost);
        return;
      }
      const o = nearestAhead(f, recognizes);
      walk(f, o ? angleTo(o.x - f.x, o.z - f.z) + rand(-noise, noise) : f.heading + rand(-1.6, 1.6));
    }

    function knockback(attacker, victim, meters, spin) {
      const away = angleTo(victim.x - attacker.x, victim.z - attacker.z);
      victim.x += Math.sin(away) * meters;
      victim.z += Math.cos(away) * meters;
      if (spin) victim.heading = wrapAngle(victim.heading + rand(-2, 2));
    }

    // Tornado-Faust: Windstoss bei jedem Fehlschlag
    function gust(f, tokens) {
      if (!f.mods.gust_m) return null;
      const pushed = fighters.filter((o) => o !== f && alive(o) && dist(f, o) <= f.mods.gust_radius_m);
      for (const o of pushed) knockback(f, o, f.mods.gust_m, false);
      return pushed.length ? emit({ type: 'gust', attacker: f.id, count: pushed.length, tokens }) : null;
    }

    function damage(f, victim, friendly, base, tokens, extra) {
      let dmg = base * f.stats.strength * (friendly ? f.mods.friendly_damage_mult : 1) * victim.mods.damage_taken_mult;
      dmg = Math.max(0.1, round1(dmg));
      f.record.hits += 1;
      f.record.damage_dealt = round1(f.record.damage_dealt + dmg);
      if (friendly) f.record.friendly_hits += 1;
      give(f, friendly ? 'dark' : 'damage', tokens);
      victim.record.hits_taken += 1;
      victim.record.damage_taken = round1(victim.record.damage_taken + dmg);
      victim.hp = Math.max(0, round1(victim.hp - dmg));
      victim.lastHitT = fight.t;
      if (victim.state === 'windup') victim.record.interrupted += 1;
      // Verraeter: heilt sich am eigenen Team
      let healed = 0;
      if (friendly && f.mods.friendly_heal) {
        healed = round1(Math.min(f.maxHp - f.hp, dmg * f.mods.friendly_heal));
        f.hp = round1(f.hp + healed);
        f.record.healed = round1((f.record.healed || 0) + healed);
      }
      // Abgefangen: gezielt war ein anderer aus dem Team des Opfers
      const intended = f.intended && byId[f.intended];
      const intercepted = intended && intended !== victim && intended.team === victim.team && victim.hp > 0;
      if (intercepted) {
        victim.record.intercepts += 1;
        give(victim, 'light', tokens);
      }
      if (victim.hp > 0) give(victim, 'tank', tokens);
      // Schmerz-Echo: Angreifer bekommt einen Teil zurueck
      let reflected = 0;
      if (victim.mods.reflect && victim.hp > 0) {
        reflected = Math.max(0.1, round1(dmg * victim.mods.reflect));
        f.hp = Math.max(0, round1(f.hp - reflected));
        f.record.damage_taken = round1(f.record.damage_taken + reflected);
        victim.record.reflected = round1((victim.record.reflected || 0) + reflected);
      }
      knockback(f, victim, f.d.knockback, true);
      const ev = emit({ type: 'hit', attacker: f.id, victim: victim.id, friendly, dmg, hp: victim.hp, healed, reflected,
        intercepted: !!intercepted, intended: intercepted ? intended.id : null, tokens, ...extra });
      if (victim.hp <= 0) knockOut(victim, f);
      if (f.hp <= 0 && alive(f)) knockOut(f, victim);
      else if (victim.hp > 0) setState(victim, 'stagger');
      return ev;
    }

    function knockOut(victim, by) {
      setState(victim, 'down');
      victim.downT = fight.t;
      by.record.kos += 1;
      emit({ type: 'ko', attacker: by.id, victim: victim.id });
    }

    function strike(f) {
      const tokens = [];
      const victims = f.mods.aoe_radius_m ? aoeTargets(f) : [frontTarget(f, !!f.focus)].filter(Boolean);
      if (!victims.length) {
        f.record.air += 1;
        // erst wiederholtes Leerschlagen zaehlt: jeder n-te Schlag ins Leere gibt Chaos
        if (f.record.air % R.air_swings_per_chaos === 0) give(f, 'chaos', tokens);
        const ev = emit({ type: 'air', attacker: f.id, tokens });
        gust(f, []);
        return ev;
      }
      for (const v of victims) v.record.targeted += 1;
      const roll = Math.floor(rng() * 100) + 1;
      const threshold = Math.round(f.d.hit * 100);
      const aoe = victims.length > 1 || !!f.mods.aoe_radius_m;
      if (roll > threshold) {
        f.record.misses += 1;
        const ev = emit({ type: 'miss', attacker: f.id, victim: victims[0].id, aoe, roll, threshold, tokens });
        gust(f, []);
        return ev;
      }
      let last = null;
      for (const victim of victims) {
        const vt = [];
        if (rng() < victim.d.dodge) {
          f.record.dodged += 1;
          victim.record.dodges += 1;
          last = emit({ type: 'dodge', attacker: f.id, victim: victim.id, roll, threshold, tokens: vt });
          continue;
        }
        const friendly = victim.team === f.team;
        if (friendly && !f.mods.friendly_heal && rng() < R.friendly_glance_chance) {
          // Streifschlag ohne Schaden am eigenen Team -> Support
          f.record.glances += 1;
          give(f, 'support', vt);
          victim.lastHitT = fight.t;
          last = emit({ type: 'glance', attacker: f.id, victim: victim.id, roll, threshold, tokens: vt });
          continue;
        }
        const base = R.damage_min + Math.floor(rng() * (R.damage_max - R.damage_min + 1));
        last = damage(f, victim, friendly, base, vt, { roll, threshold, aoe });
        if (!alive(f)) break;
      }
      return last;
    }

    function stepFighter(f) {
      f.px = f.x;
      f.pz = f.z;
      if (!alive(f)) return;
      f.stateT += dt;
      if (f.exhausted) {
        f.record.exhausted_s += dt;
        if (f.stamina >= f.d.staminaMax * R.exhausted_recover_frac) f.exhausted = false;
      }
      if (f.state !== 'walk' && f.state !== 'windup') f.stamina = Math.min(f.d.staminaMax, f.stamina + R.stamina_regen_per_s * dt);
      switch (f.state) {
        case 'stagger':
          if (f.stateT >= R.stagger_s) { setState(f, 'idle'); f.think = rand(0.1, 0.5); }
          break;
        case 'windup':
          if (f.stateT >= f.windup) { strike(f); if (f.state === 'windup') setState(f, 'recover'); }
          break;
        case 'recover':
          if (f.stateT >= R.recover_s / f.mods.attack_speed_mult) { setState(f, 'idle'); f.think = rand(R.think_min_s, R.think_max_s); }
          break;
        case 'walk': {
          const v = R.move_speed_mps * f.mods.move_mult * (f.exhausted ? 1 / R.exhausted_slow_factor : 1) * dt;
          f.x += Math.sin(f.heading) * v;
          f.z += Math.cos(f.heading) * v;
          f.walkPhase += v * 5.5;
          f.record.distance += v;
          spend(f, R.stamina_walk_cost_per_m * v);
          if (!nearestAhead(f, false)) {
            // Niemand in Sicht: zielloses Herumlaufen
            f.wanderM += v;
            if (f.wanderM >= R.wander_m_per_chaos) {
              f.wanderM -= R.wander_m_per_chaos;
              const tokens = [];
              give(f, 'chaos', tokens);
              emit({ type: 'wander', attacker: f.id, tokens });
            }
          }
          f.think -= dt;
          if (f.think <= 0) decide(f);
          break;
        }
        default:
          f.think -= dt;
          if (f.think <= 0) decide(f);
      }
    }

    // Schulterschluss: zwei aus einem Team stehen eng beieinander, ein Feind ist nah
    function shieldWall() {
      for (const f of fighters) {
        if (!alive(f)) continue;
        const mate = fighters.some((o) => o !== f && alive(o) && o.team === f.team && dist(f, o) <= R.shield_mate_m);
        const foe = mate && fighters.some((o) => alive(o) && o.team !== f.team && dist(f, o) <= R.shield_enemy_m);
        if (!foe) { f.shieldT = Math.max(0, f.shieldT - dt); continue; }
        f.shieldT += dt;
        if (f.shieldT >= R.shield_s) {
          f.shieldT -= R.shield_s;
          const tokens = [];
          give(f, 'support', tokens);
          emit({ type: 'shield', attacker: f.id, tokens });
        }
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
          const push = minD - d;
          const ax = alive(a) ? 1 : 0;
          const bx = alive(b) ? 1 : 0;
          const share = ax + bx || 1;
          a.x -= (dx / d) * push * (ax / share);
          a.z -= (dz / d) * push * (ax / share);
          b.x += (dx / d) * push * (bx / share);
          b.z += (dz / d) * push * (bx / share);
        }
      }
      const lim = R.arena_half_m;
      for (const f of fighters) {
        if (Math.abs(f.x) > lim || Math.abs(f.z) > lim) {
          f.x = clamp(f.x, -lim, lim);
          f.z = clamp(f.z, -lim, lim);
          if (f.state === 'walk') f.heading = angleTo(-f.x, -f.z) + rand(-noise, noise);
        }
      }
    }

    function endOfFight() {
      for (const f of fighters) {
        const tokens = [];
        const r = f.record;
        if (r.swings >= R.bad_aim_min_swings && r.hits + r.glances <= r.swings * R.bad_aim_max_rate) {
          give(f, 'chaos', tokens, R.bad_aim_tokens);
          emit({ type: 'bad-aim', attacker: f.id, rate: Math.round((100 * (r.hits + r.glances)) / r.swings), tokens });
        }
      }
      for (const f of fighters) {
        if (alive(f) && f.record.targeted === 0) {
          const tokens = [];
          give(f, 'light', tokens);
          emit({ type: 'untouched', attacker: f.id, tokens });
        }
      }
      emit({ type: 'end' });
    }

    fight.step = function () {
      if (fight.done) return [];
      const before = fight.events.length;
      fight.t += dt;
      for (const f of fighters) stepFighter(f);
      separate();
      shieldWall();
      if (fight.t >= nextNeutral - 1e-9) {
        nextNeutral += R.neutral_every_s;
        const tokens = [];
        for (const f of fighters) if (alive(f)) give(f, 'neutral', tokens);
        emit({ type: 'neutral', tokens });
      }
      if (fight.t >= fight.duration - 1e-9) {
        fight.done = true;
        endOfFight();
      }
      return fight.events.slice(before);
    };

    fight.runToEnd = function () {
      while (!fight.done) fight.step();
    };

    fight.summary = function () {
      return fighters.map((f) => {
        const record = { ...f.record, distance: round1(f.record.distance), exhausted_s: round1(f.record.exhausted_s) };
        const ko = f.state === 'down';
        return {
          id: f.id, name: f.name, team: f.team, hp: f.hp, maxHp: f.maxHp, ko,
          stats: { ...f.stats }, cards: f.cards.slice(),
          record,
          tokens: { ...f.tokens },
          imprint: topStack(f.tokens),
          alignment: alignmentOf(f.tokens),
          growth: growth(R, f.stats, record, ko),
        };
      });
    };

    return fight;
  }

  // Groesster Primaer-Stapel = Praegung
  function topStack(tokens) {
    let best = null;
    for (const id of PRIMARY) if (tokens[id] > 0 && (!best || tokens[id] > tokens[best])) best = id;
    return best;
  }

  // Gesinnung: Dunkel gegen Hell, Gleichstand = Neutral
  function alignmentOf(tokens) {
    if (tokens.dark > tokens.light) return 'dark';
    if (tokens.light > tokens.dark) return 'light';
    return 'neutral';
  }

  // Learning by Doing: Zuwachs pro Attribut aus dem Kampfprotokoll
  function growth(R, stats, record, ko) {
    const g = R.growth;
    const delta = {
      strength: record.swings * g.strength_per_swing,
      stamina: record.hits_taken * g.stamina_per_hit_taken + record.distance * g.stamina_per_m,
      awareness: ko ? 0 : g.awareness_per_survival,
      dexterity: (record.misses + record.air + record.dodged) * g.dexterity_per_miss,
    };
    const after = {};
    for (const a of ATTRS) {
      delta[a] = Math.round(delta[a] * 1000) / 1000;
      after[a] = Math.round((stats[a] + delta[a]) * 1000) / 1000;
    }
    return { delta, after };
  }

  const api = { createFight, cardMods, topStack, alignmentOf, growth, derive, mulberry32, TOKEN_IDS, PRIMARY, ALIGNMENT, SECONDARY, ATTRS };
  root.ChaosCombat = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
