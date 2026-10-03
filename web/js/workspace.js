/* workspace.js - Editor-Oberflaeche von Chaos Arena.
 * Baut Projektbaum, Szene, Inspector, Konsole und alle Dokumente aus data/state.json
 * und steuert den Kampf (combat.js). Die 3D-Darstellung kommt aus arena3d.js und liest
 * den Kampfzustand ueber window.ChaosWorkspace.
 */
(function () {
  'use strict';

  const state = JSON.parse(document.getElementById('state-data').textContent);
  const { createFight, topStack, alignmentOf, derive, TOKEN_IDS, ATTRS } = window.ChaosCombat;
  const tokenById = Object.fromEntries(state.tokens.map((t) => [t.id, t]));
  const activeTokens = state.tokens.filter((t) => t.active);
  const primaryTokens = state.tokens.filter((t) => t.group === 'primary');
  const groups = state.token_groups.map((g) => ({ ...g, tokens: state.tokens.filter((t) => t.group === g.id) }));
  const attrById = Object.fromEntries(state.attributes.map((a) => [a.id, a]));
  const ALIGN = { dark: tokenById.dark, light: tokenById.light, neutral: tokenById.neutral };
  const EVO = window.ChaosEvolution;
  const cardById = Object.fromEntries(state.cards.map((c) => [c.id, c]));
  const catById = Object.fromEntries(state.card_categories.map((c) => [c.id, c]));
  const WALLET_IDS = EVO.walletIds(state);
  const rosterById = Object.fromEntries(state.roster.map((r) => [r.id, r]));
  const teamOf = (id) => state.teams[rosterById[id].team];

  const CW = {
    state, fight: null, alpha: 0, speed: 1, running: false, selected: null,
    portraits: {}, fightNo: 0, lastSummary: null, ledger: {}, fightsDone: 0, view: 'live',
    season: EVO.newSeason(state), // Attribute, Karten, Praegungen pro Kaempfer (nur in dieser Sitzung)
    after: null,                  // Daten des Fensters "Nach dem Kampf"

  };
  window.ChaosWorkspace = CW;

  // ---------------------------------------------------------------- Helfer

  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs || {})) {
      if (value === false || value == null) continue;
      if (key === 'class') el.className = value;
      else if (key === 'style') el.style.cssText = value;
      else if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
      else el.setAttribute(key, value === true ? '' : value);
    }
    for (const child of children.flat(Infinity)) {
      if (child == null || child === false) continue;
      el.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return el;
  }
  const $ = (id) => document.getElementById(id);
  const kb = (bytes) => (bytes / 1024).toFixed(1).replace('.', ',') + ' KB';
  const num = (v, d = 1) => v.toFixed(d).replace('.', ',');
  const clock = (raw) => {
    const t = Math.round(raw * 10) / 10;   // 59,99 -> 60,0 -> 01:00,0
    const m = Math.floor(t / 60);
    const s = t - m * 60;
    return String(m).padStart(2, '0') + ':' + (s < 10 ? '0' : '') + num(s, 1);
  };
  const emit = (name, detail) => window.dispatchEvent(new CustomEvent(name, { detail }));
  const randomSeed = () => 1 + Math.floor(Math.random() * 99999);
  const fmt2 = (v) => v.toFixed(2).replace('.', ',');
  const fmtDelta = (v) => (v > 0 ? '+' : v < 0 ? '−' : '±') + Math.abs(v).toFixed(2).replace('.', ',');
  const fmtHp = (v) => (Math.round(v * 10) / 10).toString().replace('.', ',');
  const pct = (v) => Math.round(v * 100) + ' %';
  const seasonStats = (id) => ({ ...state.rules.start_stats, ...(CW.season.stats[id] || {}) });
  const chip = (t, text) => h('span', { class: 'top-chip', style: '--tok:' + t.color }, text || t.label);
  const costText = (cost) => Object.entries(cost).map(([t, n]) => n + ' ' + tokenById[t].label).join(' + ');
  function walletChips(wallet, empty) {
    const list = WALLET_IDS.filter((t) => wallet[t] > 0);
    if (!list.length) return h('span', { class: 'insp-empty' }, empty || 'keine Prägungen');
    return h('span', { class: 'chips' }, list.map((t) => chip(tokenById[t], tokenById[t].label + ' × ' + wallet[t])));
  }
  function cardList(ids) {
    if (!ids.length) return h('span', { class: 'insp-empty' }, 'noch keine Karten');
    const count = {};
    for (const id of ids) count[id] = (count[id] || 0) + 1;
    return h('span', { class: 'chips' }, Object.entries(count).map(([id, n]) =>
      h('span', { class: 'card-chip', style: '--cat:' + catById[cardById[id].category].color, title: cardById[id].effect },
        cardById[id].name + (n > 1 ? ' × ' + n : ''))));
  }

  const STATE_LABEL = { idle: 'steht', walk: 'läuft', windup: 'holt aus', recover: 'schlägt', stagger: 'taumelt', down: 'K.O.' };
  const LOOK_LABEL = {
    hair: { glatze: 'Glatze', stoppeln: 'Stoppeln', topf: 'Topfschnitt', irokese: 'Irokese', lang: 'Lang', zopf: 'Zopf', seiten: 'Haarkranz' },
    beard: { keiner: 'keiner', stoppel: 'Stoppeln', voll: 'Vollbart', schnauzer: 'Schnauzer', kinn: 'Kinnbart' },
    nose: { klein: 'klein', knolle: 'Knolle', lang: 'lang' },
    brows: { duenn: 'dünn', wulst: 'Wulst' },
  };

  function portrait(id, cls) {
    const img = h('img', { class: 'portrait' + (cls ? ' ' + cls : ''), alt: '', 'data-portrait': id, style: '--team:' + teamOf(id).color });
    if (CW.portraits[id]) img.src = CW.portraits[id];
    return img;
  }
  window.addEventListener('chaos:portraits', () => {
    for (const img of document.querySelectorAll('img[data-portrait]')) {
      const src = CW.portraits[img.dataset.portrait];
      if (src && img.src !== src) img.src = src;
    }
  });

  function hpbar(hp, max) {
    const pct = Math.max(0, Math.min(100, (100 * hp) / max));
    return h('span', { class: 'hpbar' + (pct <= 35 ? ' low' : '') }, h('i', { style: 'width:' + pct + '%' }));
  }
  function setHp(bar, hp, max) {
    const pct = Math.max(0, Math.min(100, (100 * hp) / max));
    bar.classList.toggle('low', pct <= 35);
    bar.firstChild.style.width = pct + '%';
  }

  // ---------------------------------------------------------------- Kampfsteuerung

  let acc = 0;
  let last = performance.now();

  function newFight(seed) {
    if (CW.after) {
      // offene Evolutionen verfallen, Praegungen bleiben
      for (const ev of CW.after.phase.evolvers) EVO.skip(ev);
      CW.after = null;
      if (docs.evolutionen) rerenderDocs(['evolutionen']);
    }
    CW.fight = createFight(state, seed, EVO.loadout(CW.season));
    CW.fightNo += 1;
    CW.running = false;
    CW.lastSummary = null;
    acc = 0;
    CW.alpha = 0;
    $('seed-input').value = seed;
    consoleLog.replaceChildren();
    $('console-empty').hidden = false;
    $('console-empty').textContent = 'Kampf #' + CW.fightNo + ' bereit · Seed ' + seed + '. ▶ Kampf starten.';
    closeAfter();
    emit('chaos:fight-new', { seed });
    refreshLive(true);
  }

  function play() {
    const f = CW.fight;
    if (f.done) { newFight(randomSeed()); }
    CW.running = !CW.running;
    if (CW.running && CW.fight.t === 0) {
      appendLog([{ type: 'start', t: 0, tokens: [] }]);
      closeAfter();
    }
    refreshTransport();
  }

  function finish() {
    CW.running = false;
    const summary = CW.fight.summary();
    CW.lastSummary = { no: CW.fightNo, seed: CW.fight.seed, rows: summary, events: CW.fight.events.slice() };
    CW.fightsDone += 1;
    for (const row of summary) {
      const l = CW.ledger[row.id] || (CW.ledger[row.id] = { fights: 0, kos: 0, tokens: Object.fromEntries(TOKEN_IDS.map((t) => [t, 0])) });
      l.fights += 1;
      if (row.ko) l.kos += 1;
      for (const t of TOKEN_IDS) l.tokens[t] += row.tokens[t];
      CW.season.stats[row.id] = row.growth.after; // Learning by Doing: gilt ab dem naechsten Kampf
    }
    CW.season.fights += 1;
    // Evolutions-Phase: Token -> Praegungen, 2 Kaempfer pro Team, je 3 Karten
    const phase = EVO.runPhase(state, summary, CW.season, CW.fight.seed);
    CW.after = { no: CW.fightNo, seed: CW.fight.seed, rows: summary, phase };
    openAfter(0);
    rerenderDocs(['kampf-log', 'token-abrechnung', 'roster', 'evolutionen']);
    refreshLive(true);
  }

  function skipToEnd() {
    const f = CW.fight;
    if (f.done) return;
    const before = f.events.length;
    if (f.t === 0) appendLog([{ type: 'start', t: 0, tokens: [] }]);
    f.runToEnd();
    CW.alpha = 0;
    appendLog(f.events.slice(before));
    finish();
  }

  let uiTimer = 0;
  function loop(now) {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    const f = CW.fight;
    if (f && CW.running && !f.done) {
      acc += dt * CW.speed;
      const fresh = [];
      while (acc >= f.dt && !f.done) {
        fresh.push(...f.step());
        acc -= f.dt;
      }
      CW.alpha = f.done ? 0 : acc / f.dt;
      if (fresh.length) {
        appendLog(fresh);
        emit('chaos:events', fresh);
      }
      if (f.done) finish();
    }
    uiTimer += dt;
    if (uiTimer > 0.12) { uiTimer = 0; refreshLive(false); }
    requestAnimationFrame(loop);
  }

  // ---------------------------------------------------------------- Menueleiste

  const speedSeg = $('speed');
  for (const s of [1, 2, 4]) {
    speedSeg.append(h('button', {
      type: 'button', 'aria-pressed': String(s === CW.speed), 'data-speed': s,
      onclick: () => {
        CW.speed = s;
        for (const b of speedSeg.children) b.setAttribute('aria-pressed', String(Number(b.dataset.speed) === s));
      },
    }, s + '×'));
  }
  $('btn-play').addEventListener('click', play);
  $('btn-restart').addEventListener('click', () => newFight(CW.fight.seed));
  $('btn-skip').addEventListener('click', skipToEnd);
  $('btn-dice').addEventListener('click', () => newFight(randomSeed()));
  $('seed-input').addEventListener('change', (e) => {
    const v = Math.max(1, Math.min(999999, Math.floor(Number(e.target.value)) || 1));
    newFight(v);
  });

  function refreshTransport() {
    const f = CW.fight;
    const btn = $('btn-play');
    btn.textContent = f.done ? '▶ Neuer Kampf' : CW.running ? '❚❚ Pause' : f.t > 0 ? '▶ Weiter' : '▶ Kampf starten';
    $('btn-skip').disabled = f.done;
    $('clock-text').textContent = clock(Math.min(f.t, f.duration)) + ' / ' + clock(f.duration).replace(',0', '');
    $('clock-bar').style.width = (100 * Math.min(1, f.t / f.duration)) + '%';
  }

  // ---------------------------------------------------------------- Projektbaum + Routing

  const nav = $('nav');
  const links = {};
  const groupOf = {};
  const itemById = {};
  for (const group of state.nav) {
    nav.append(h('li', { class: 'tree-group' },
      h('span', null, group.group),
      h('ul', null, group.items.map((item) => {
        groupOf[item.id] = group.group;
        itemById[item.id] = item;
        const planned = item.view === 'placeholder';
        const a = h('a', { href: '#' + item.id, class: planned ? 'is-planned' : '' },
          h('span', null, item.label), planned ? h('span', { class: 'badge' }, 'geplant') : null);
        links[item.id] = a;
        return h('li', null, a);
      }))));
  }

  const docsRoot = $('docs');
  const docs = {};

  function showView() {
    const id = location.hash.slice(1);
    const current = itemById[id] ? id : 'live';
    CW.view = current;
    for (const [key, a] of Object.entries(links)) {
      if (key === current) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    }
    if (!docs[current]) {
      docs[current] = renderDoc(current);
      docsRoot.append(docs[current]);
    }
    for (const [key, el] of Object.entries(docs)) el.hidden = key !== current;
    $('editor').dataset.mode = current === 'live' ? 'live' : 'doc';
    const sec = state.sections[current] || {};
    $('crumb').replaceChildren(groupOf[current] + ' / ', h('b', null, itemById[current].label));
    $('doc-tab').replaceChildren(...[sec.title || itemById[current].label, current === 'live' ? h('small', null, 'Viewport') : null].filter(Boolean));
    docsRoot.scrollTop = 0;
    emit('chaos:view', { id: current });
  }
  window.addEventListener('hashchange', showView);

  function rerenderDocs(ids) {
    for (const id of ids) {
      if (!docs[id]) continue;
      const fresh = renderDoc(id);
      fresh.hidden = docs[id].hidden;
      docs[id].replaceWith(fresh);
      docs[id] = fresh;
    }
    emit('chaos:docs-updated');
  }

  // ---------------------------------------------------------------- Szene (links)

  const sceneList = $('scene-list');
  const sceneRows = {};
  for (const r of state.roster) {
    const bar = hpbar(1, 1);
    const st = h('span', { class: 'scene-state' }, '');
    const row = h('li', { class: 'scene-row', role: 'option', 'aria-selected': 'false', tabindex: '0', onclick: () => select(r.id),
      onkeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); select(r.id); } } },
      portrait(r.id),
      h('span', { class: 'scene-hp' },
        h('span', { class: 'scene-name' }, h('i', { class: 'team-dot', style: '--team:' + teamOf(r.id).color }), h('span', null, r.name)),
        bar),
      st);
    sceneRows[r.id] = { row, bar, st };
    sceneList.append(row);
  }
  $('scene-count').textContent = state.roster.length + ' Kämpfer';

  function select(id) {
    CW.selected = CW.selected === id ? null : id;
    $('editor').dataset.insp = CW.selected ? 'on' : 'off';
    for (const [rid, r] of Object.entries(sceneRows)) r.row.setAttribute('aria-selected', String(rid === CW.selected));
    renderInspector();
    emit('chaos:select', { id: CW.selected });
  }
  CW.select = select;

  // ---------------------------------------------------------------- Inspector (rechts)

  const insp = $('inspector');
  let inspRefs = null;

  function renderInspector() {
    const f = CW.fight;
    insp.replaceChildren();
    inspRefs = null;
    if (!CW.selected) {
      const R = state.rules;
      const teamTotals = teamTokenTotals();
      const d = derive(R, R.start_stats);
      insp.append(
        h('div', { class: 'insp-block' },
          h('h3', null, 'Kampf #' + CW.fightNo + ' · Seed ' + f.seed + ' · Saison-Kampf ' + (CW.fightsDone + 1)),
          h('p', { class: 'insp-empty' }, 'Klick auf einen Kämpfer im Viewport oder in der Szene, um ihn zu inspizieren.')),
        h('div', { class: 'insp-block' },
          h('h3', null, 'Token je Team'),
          h('dl', { class: 'kv' },
            h('dt', null, ''), h('dd', null, Object.values(state.teams).map((t) => h('span', { style: 'color:' + t.color + ';margin-left:10px' }, t.short))),
            activeTokens.map((t) => [h('dt', { style: 'color:' + t.color }, t.label),
              h('dd', null, Object.keys(state.teams).map((tid) => h('span', { style: 'display:inline-block;min-width:34px' }, teamTotals[tid][t.id])))]))),
        h('div', { class: 'insp-block' },
          h('h3', null, 'Startwerte (alle Attribute 1)'),
          h('dl', { class: 'kv' },
            h('dt', null, 'Kampfdauer'), h('dd', null, R.duration_s + ' s'),
            h('dt', null, 'Lebenspunkte'), h('dd', null, d.maxHp),
            h('dt', null, 'Trefferchance'), h('dd', null, pct(d.hit)),
            h('dt', null, 'Sichtradius'), h('dd', null, num(d.sight, 1) + ' m'),
            h('dt', null, 'Freund-Feind-Erkennung'), h('dd', null, pct(d.recognize)),
            h('dt', null, 'Schaden'), h('dd', null, R.damage_min + '–' + R.damage_max))));
      return;
    }
    const r = rosterById[CW.selected];
    const ff = f.byId[r.id];
    const team = teamOf(r.id);
    const stackRefs = {};
    const statRefs = {};
    const hpBar = hpbar(ff.hp, ff.maxHp);
    const hpText = h('span', null, '');
    const stBar = hpbar(ff.stamina, ff.d.staminaMax);
    stBar.classList.add('stamina');
    const stText = h('span', null, '');
    const stateText = h('span', null, '');
    insp.append(
      h('div', { class: 'insp-head' },
        portrait(r.id),
        h('div', null,
          h('h2', null, r.name),
          h('p', { class: 'insp-sub' }, h('i', { class: 'team-dot', style: '--team:' + team.color }), team.name, ' · ', r.id, ' · ', stateText))),
      h('div', { class: 'hp-big' }, hpBar, hpText, stBar, stText),
      h('div', { class: 'insp-block' },
        h('h3', null, 'Attribute'),
        h('div', { class: 'statgrid statgrid-4' },
          state.attributes.map((a) => h('div', { class: 'stat', title: a.effect },
            h('span', null, a.label), h('b', null, fmt2(ff.stats[a.id]))))),
        h('dl', { class: 'kv' },
          h('dt', null, 'Trefferchance · Ausweichen'), h('dd', null, pct(ff.d.hit) + ' · ' + pct(ff.d.dodge)),
          h('dt', null, 'Sichtradius'), h('dd', null, num(ff.d.sight, 1) + ' m'),
          h('dt', null, 'Freund-Feind-Erkennung'), h('dd', null, pct(ff.d.recognize)),
          h('dt', null, 'Rückstoß'), h('dd', null, num(ff.d.knockback, 2) + ' m'))),
      ...groups.map((g) => h('div', { class: 'insp-block' },
        h('h3', null, g.label + (g.id === 'primary' ? ' · größter Stapel = Prägung' : g.id === 'secondary' ? ' · geplant' : '')),
        h('div', { class: 'stacks' }, g.tokens.map((t) => {
          const bar = h('i');
          const val = h('b', null, '0');
          const row = h('div', { class: 'stack' + (t.active ? '' : ' is-off'), style: '--tok:' + t.color, title: t.source }, h('span', null, t.label), h('span', { class: 'stack-bar' }, bar), val);
          stackRefs[t.id] = { row, bar, val };
          return row;
        })))),
      h('div', { class: 'insp-block' },
        h('h3', null, 'Prägungen (Währung für Karten)'),
        walletChips(CW.season.wallets[r.id]),
        h('h3', null, 'Karten'),
        cardList(CW.season.cards[r.id])),
      h('div', { class: 'insp-block' },
        h('h3', null, 'Statistik'),
        h('dl', { class: 'kv' }, [
          ['swings', 'Schläge'], ['hits', 'Treffer mit Schaden'], ['glances', 'Streifschläge (eigenes Team)'], ['misses', 'Daneben'],
          ['air', 'Ins Leere'], ['dodged', 'Gegner ausgewichen'], ['dodges', 'Selbst ausgewichen'], ['friendly_hits', 'Friendly Fire'],
          ['held_back', 'Teamkamerad erkannt'], ['intercepts', 'Schläge abgefangen'], ['damage_dealt', 'Schaden ausgeteilt'],
          ['damage_taken', 'Schaden erlitten'], ['distance', 'Strecke (m)'], ['exhausted_s', 'Erschöpft (s)'],
        ].map(([key, label]) => {
          const dd = h('dd', null, '0');
          statRefs[key] = dd;
          return [h('dt', null, label), dd];
        }))),
      h('div', { class: 'insp-block' },
        h('h3', null, 'Aussehen'),
        h('dl', { class: 'kv' },
          h('dt', null, 'Hautton'), h('dd', null, h('span', { style: 'display:inline-block;width:10px;height:10px;border-radius:2px;vertical-align:-1px;background:' + r.look.skin })),
          h('dt', null, 'Frisur'), h('dd', null, LOOK_LABEL.hair[r.look.hair]),
          h('dt', null, 'Bart'), h('dd', null, LOOK_LABEL.beard[r.look.beard]),
          h('dt', null, 'Nase'), h('dd', null, LOOK_LABEL.nose[r.look.nose]),
          h('dt', null, 'Brauen'), h('dd', null, LOOK_LABEL.brows[r.look.brows]),
          h('dt', null, 'Narbe'), h('dd', null, r.look.scar ? 'ja' : 'nein'))));
    inspRefs = { id: r.id, hpBar, hpText, stBar, stText, stateText, stackRefs, statRefs };
    refreshInspector();
  }

  function refreshInspector() {
    if (!inspRefs) { if (!CW.selected) renderInspectorTotals(); return; }
    const ff = CW.fight.byId[inspRefs.id];
    setHp(inspRefs.hpBar, ff.hp, ff.maxHp);
    inspRefs.hpText.textContent = fmtHp(ff.hp) + ' / ' + fmtHp(ff.maxHp) + ' LP';
    setHp(inspRefs.stBar, ff.stamina, ff.d.staminaMax);
    inspRefs.stText.textContent = 'Kraft ' + Math.round(ff.stamina) + ' / ' + Math.round(ff.d.staminaMax) + (ff.exhausted ? ' · erschöpft' : '');
    inspRefs.stateText.textContent = STATE_LABEL[ff.state] + (ff.exhausted && ff.state !== 'down' ? ', erschöpft' : '');
    const top = topStack(ff.tokens);
    const align = alignmentOf(ff.tokens);
    for (const g of groups) {
      const max = Math.max(1, ...g.tokens.map((t) => ff.tokens[t.id]));
      for (const t of g.tokens) {
        const ref = inspRefs.stackRefs[t.id];
        ref.val.textContent = ff.tokens[t.id];
        ref.bar.style.width = (100 * ff.tokens[t.id]) / max + '%';
        ref.row.classList.toggle('is-top', t.id === top || (g.id === 'alignment' && t.id === align && align !== 'neutral'));
      }
    }
    for (const [key, dd] of Object.entries(inspRefs.statRefs)) {
      const v = ff.record[key];
      dd.textContent = key === 'distance' || key === 'exhausted_s' || key.startsWith('damage') ? num(v, 1) : v;
    }
  }

  let lastTotalsKey = '';
  function renderInspectorTotals() {
    const key = JSON.stringify(teamTokenTotals()) + CW.fightNo;
    if (key !== lastTotalsKey) { lastTotalsKey = key; renderInspector(); }
  }

  function teamTokenTotals() {
    const out = {};
    for (const team of Object.keys(state.teams)) out[team] = Object.fromEntries(TOKEN_IDS.map((t) => [t, 0]));
    for (const f of CW.fight.fighters) for (const t of TOKEN_IDS) out[f.team][t] += f.tokens[t];
    return out;
  }

  // ---------------------------------------------------------------- Konsole

  const consoleLog = $('console-log');
  const consoleScroll = $('console-scroll');
  const LOG_FILTERS = [
    ['all', 'Alle'], ['hit', 'Treffer'], ['friendly', 'Eigenes Team'], ['miss', 'Daneben'], ['air', 'Ins Leere'], ['passive', 'Passiv'],
  ];
  let logFilter = 'all';
  const filterBox = $('log-filters');
  for (const [id, label] of LOG_FILTERS) {
    filterBox.append(h('button', {
      class: 'chip', type: 'button', 'aria-pressed': String(id === 'all'), 'data-f': id,
      onclick: () => {
        logFilter = id;
        for (const b of filterBox.children) b.setAttribute('aria-pressed', String(b.dataset.f === id));
        applyFilter(consoleLog, id);
      },
    }, label));
  }

  function applyFilter(list, id) {
    for (const li of list.children) li.hidden = id !== 'all' && !li.classList.contains('f-' + id);
  }

  const nameSpan = (id) => h('span', { class: 'who', style: '--team:' + teamOf(id).color }, rosterById[id].name);

  function eventLine(ev) {
    let cls = 'k-sys';
    let msg;
    const roll = () => ' · W100 ' + ev.roll + (ev.roll <= ev.threshold ? ' ≤ ' : ' > ') + ev.threshold;
    switch (ev.type) {
      case 'start': msg = ['Kampf #' + CW.fightNo + ' gestartet · Seed ' + CW.fight.seed + ' · ' + state.rules.duration_s + ' s']; break;
      case 'end': msg = ['Kampfende nach ' + state.rules.duration_s + ' s']; break;
      case 'hit':
        cls = 'k-hit f-hit' + (ev.friendly ? ' f-friendly' : '');
        msg = [nameSpan(ev.attacker), ev.friendly ? ' trifft Teamkamerad ' : ' trifft ', nameSpan(ev.victim), roll(),
          ' · −' + fmtHp(ev.dmg) + ' LP (' + fmtHp(ev.hp) + ')',
          ev.intercepted ? [' · fängt den Schlag für ', nameSpan(ev.intended), ' ab'] : null];
        break;
      case 'glance':
        cls = 'k-hit f-friendly';
        msg = [nameSpan(ev.attacker), ' streift Teamkamerad ', nameSpan(ev.victim), roll(), ' · kein Schaden'];
        break;
      case 'miss':
        cls = 'k-miss f-miss';
        msg = [nameSpan(ev.attacker), ' schlägt nach ', nameSpan(ev.victim), ' · daneben', roll()];
        break;
      case 'dodge':
        cls = 'k-miss f-miss';
        msg = [nameSpan(ev.victim), ' weicht ', nameSpan(ev.attacker), ' aus'];
        break;
      case 'air': cls = 'k-air f-air'; msg = [nameSpan(ev.attacker), ' schlägt ins Leere']; break;
      case 'wander': cls = 'k-air f-passive'; msg = [nameSpan(ev.attacker), ' irrt ' + state.rules.wander_m_per_chaos + ' m ziellos umher']; break;
      case 'shield': cls = 'k-miss f-passive'; msg = [nameSpan(ev.attacker), ' steht ' + state.rules.shield_s + ' s Schulter an Schulter vor dem Feind']; break;
      case 'neutral': cls = 'k-miss f-passive'; msg = ['Alle, die noch stehen, sammeln Neutral']; break;
      case 'bad-aim': cls = 'k-air f-passive'; msg = [nameSpan(ev.attacker), ' trifft nur ' + ev.rate + ' % seiner Schläge']; break;
      case 'untouched': cls = 'k-hit f-passive'; msg = [nameSpan(ev.attacker), ' übersteht den Kampf, ohne einmal angegriffen zu werden']; break;
      case 'ko': cls = 'k-ko f-hit'; msg = [nameSpan(ev.victim), ' geht K.O. (durch ', nameSpan(ev.attacker), ')']; break;
      default: msg = [ev.type];
    }
    const toks = ev.type === 'neutral'
      ? [h('span', { class: 'tk', style: '--tok:' + tokenById.neutral.color }, '+1 ' + tokenById.neutral.short + ' × ' + ev.tokens.length)]
      : ev.tokens.map((tk) => h('span', { class: 'tk', style: '--tok:' + tokenById[tk.token].color },
        '+' + (tk.n || 1) + ' ' + tokenById[tk.token].short + ' ' + rosterById[tk.fighter].name));
    return h('li', { class: cls },
      h('span', { class: 't' }, clock(ev.t)),
      h('span', { class: 'msg' }, msg),
      h('span', { class: 'toks' }, toks));
  }

  function appendLog(events) {
    if (!events.length) return;
    $('console-empty').hidden = true;
    const stick = consoleScroll.scrollHeight - consoleScroll.scrollTop - consoleScroll.clientHeight < 30;
    const frag = document.createDocumentFragment();
    for (const ev of events) {
      const li = eventLine(ev);
      if (logFilter !== 'all' && !li.classList.contains('f-' + logFilter)) li.hidden = true;
      frag.append(li);
    }
    consoleLog.append(frag);
    if (stick) consoleScroll.scrollTop = consoleScroll.scrollHeight;
  }

  // ---------------------------------------------------------------- Live-Ansicht + Auswertung

  const stage = h('div', { class: 'stage', id: 'stage' },
    h('p', { class: 'stage-msg', id: 'stage-msg' }, '3D-Ansicht lädt …'),
    h('div', { class: 'labels', id: 'labels' }),
    h('div', { class: 'hud', id: 'hud' }),
    h('p', { class: 'hud-hint' }, 'Klick auf Kämpfer = Inspector'));
  CW.stageEl = stage; // arena3d.js haengt sich hier ein, auch wenn die Live-Ansicht noch nicht sichtbar ist

  const hudRows = {};
  for (const team of Object.values(state.teams)) {
    const val = h('span', null, '');
    hudRows[team.id] = val;
    stage.querySelector('#hud').append(h('div', { class: 'hud-team' }, h('i', { class: 'team-dot', style: '--team:' + team.color }), h('b', null, team.short), val));
  }

  function refreshLive(force) {
    const f = CW.fight;
    if (!f) return;
    refreshTransport();
    for (const ff of f.fighters) {
      const r = sceneRows[ff.id];
      setHp(r.bar, ff.hp, ff.maxHp);
      r.st.textContent = ff.exhausted && ff.state !== 'down' ? 'erschöpft' : STATE_LABEL[ff.state];
    }
    const totals = teamTokenTotals();
    for (const [team, el] of Object.entries(hudRows)) {
      const alive = f.fighters.filter((x) => x.team === team && x.state !== 'down').length;
      el.textContent = ' ' + alive + '/5 · ' + primaryTokens.map((t) => t.short + ' ' + totals[team][t.id]).join(' · ') + ' · ' + ['dark', 'light'].map((t) => tokenById[t].short + ' ' + totals[team][t]).join(' · ');
    }
    if (force) renderInspector(); else refreshInspector();
    $('status-left').textContent = 'three.js ' + (window.ChaosArena3D ? 'r' + window.ChaosArena3D.three : 'lädt') +
      ' · ' + state.roster.length + ' Kämpfer · Kampf #' + CW.fightNo + ' · Seed ' + f.seed + ' · ' + CW.fightsDone + ' ausgewertet';
  }

  function resultTable(rows, opts) {
    const cols = opts.ledger ? [] : [
      ['swings', 'Schläge'], ['hits', 'Treffer'], ['air', 'Leere'], ['glances', 'Streif'], ['friendly_hits', 'Eigen'],
      ['intercepts', 'Abgef.'], ['damage_dealt', 'Schad. +'], ['damage_taken', 'Schad. −'],
    ];
    const toks = activeTokens;
    const cell = (v, t) => h('td', { class: v ? 'tok' : 'zero', style: '--tok:' + t.color }, v);
    return h('div', { class: 'table-wrap' }, h('table', { class: 'data' },
      h('thead', null, h('tr', null,
        h('th', { scope: 'col' }, 'Kämpfer'),
        opts.ledger ? [h('th', { scope: 'col' }, 'Kämpfe'), h('th', { scope: 'col' }, 'K.O.')] : h('th', { scope: 'col' }, 'LP'),
        h('th', { scope: 'col', class: 'grp' }, 'Prägung'),
        h('th', { scope: 'col' }, 'Gesinnung'),
        state.attributes.map((a, i) => h('th', { scope: 'col', class: i === 0 ? 'grp' : '', title: a.label }, a.short)),
        toks.map((t, i) => h('th', { scope: 'col', class: i === 0 || t.id === 'dark' ? 'grp' : '', style: 'color:' + t.color, title: t.source }, t.short)),
        cols.map(([, label], i) => h('th', { scope: 'col', class: i === 0 ? 'grp' : '' }, label)))),
      h('tbody', null, rows.map((row) => {
        const top = topStack(row.tokens);
        const align = alignmentOf(row.tokens);
        return h('tr', { onclick: () => { if (opts.onPick) opts.onPick(row.id); } },
          h('td', null, h('span', { class: 'who' }, portrait(row.id), h('span', null, rosterById[row.id].name,
            row.ko && !opts.ledger ? h('span', { class: 'ko-tag' }, ' K.O.') : null))),
          opts.ledger ? [h('td', null, row.fights), h('td', null, row.kos)] : h('td', null, fmtHp(row.hp) + '/' + fmtHp(row.maxHp)),
          h('td', { class: 'grp' }, top ? chip(tokenById[top]) : '–'),
          h('td', null, chip(ALIGN[align])),
          state.attributes.map((a, i) => {
            const v = opts.ledger ? row.stats[a.id] : row.growth.after[a.id];
            const d = opts.ledger ? v - state.rules.start_stats[a.id] : row.growth.delta[a.id];
            return h('td', { class: i === 0 ? 'grp' : '', title: a.label },
              fmt2(v), h('small', { class: 'delta' + (d > 0 ? ' up' : '') }, ' ' + fmtDelta(d)));
          }),
          toks.map((t, i) => {
            const c = cell(row.tokens[t.id], t);
            if (i === 0 || t.id === 'dark') c.classList.add('grp');
            return c;
          }),
          cols.map(([key], i) => h('td', { class: i === 0 ? 'grp' : '' }, key.startsWith('damage') ? fmtHp(row.record[key]) : row.record[key])));
      }))));
  }

  // ---------------------------------------------------------------- Fenster "Nach dem Kampf"

  const AF_STEPS = [
    ['Auswertung', 'Was im Kampf passiert ist'],
    ['Prägungen', 'Token werden zu Währung'],
    ['Evolution', '2 Kämpfer pro Team wählen eine Karte'],
  ];
  let afStep = 0;

  function openAfter(step) {
    if (!CW.after) return;
    afStep = step;
    $('afterfight').hidden = false;
    $('btn-after').hidden = true;
    renderAfter();
    $('af-next').focus();
  }

  function closeAfter() {
    $('afterfight').hidden = true;
    $('btn-after').hidden = !CW.after || CW.fight.t === 0 || !CW.fight.done;
  }

  function openDecisions() {
    return CW.after ? CW.after.phase.evolvers.filter((e) => !e.chosen && e.offers.length).length : 0;
  }

  function renderAfter() {
    const a = CW.after;
    const kos = a.rows.filter((r) => r.ko).length;
    $('af-meta').textContent = 'Kampf #' + a.no + ' · Seed ' + a.seed + ' · ' + state.rules.duration_s + ' s · ' + (kos ? kos + ' K.O.' : 'kein K.O.') + ' · Saison-Kampf ' + CW.season.fights;
    $('af-steps').replaceChildren(...AF_STEPS.map(([label, sub], i) => h('button', {
      type: 'button', class: 'af-step', 'aria-current': i === afStep ? 'step' : null, onclick: () => { afStep = i; renderAfter(); },
    }, h('b', null, (i + 1) + ' · ' + label), h('span', null, sub))));
    const body = $('af-body');
    body.replaceChildren(...[afStats, afImprints, afEvolution][afStep]());
    body.scrollTop = 0;
    $('af-prev').disabled = afStep === 0;
    const open = openDecisions();
    $('af-next').textContent = afStep < 2 ? 'Weiter: ' + AF_STEPS[afStep + 1][0] : '▶ Nächster Kampf';
    $('af-hint').textContent = afStep === 2 && open ? open + ' Evolution' + (open > 1 ? 'en' : '') + ' offen. Offene Evolutionen verfallen, die Prägungen bleiben.' : '';
  }

  function afStats() {
    const a = CW.after;
    const totals = {};
    for (const team of Object.keys(state.teams)) totals[team] = Object.fromEntries(TOKEN_IDS.map((t) => [t, 0]));
    for (const r of a.rows) for (const t of TOKEN_IDS) totals[r.team][t] += r.tokens[t];
    return [
      h('div', { class: 'team-sum' }, Object.values(state.teams).map((team) =>
        h('span', null, h('i', { class: 'team-dot', style: '--team:' + team.color }), h('b', null, team.name),
          activeTokens.map((t) => h('span', { style: 'color:' + t.color }, ' ' + t.short + ' ' + totals[team.id][t.id]))))),
      h('p', { class: 'af-note' }, 'Die Attribute enthalten schon den Zuwachs aus diesem Kampf (kleine Zahl) und gelten ab dem nächsten Kampf. Klick auf eine Zeile zeigt den Kämpfer im Inspector.'),
      h('div', { class: 'card' }, resultTable(a.rows, { onPick: (id) => { closeAfter(); if (CW.selected !== id) select(id); } })),
    ];
  }

  function afImprints() {
    const a = CW.after;
    const E = state.evolution;
    return [
      h('p', { class: 'af-note' }, 'Der größte Stapel aus ' + E.imprint_tokens.map((t) => tokenById[t].label).join(', ') +
        ' wird zu 1 permanenten Prägung. Jeder Teilnehmer bekommt zusätzlich ' + E.neutral_per_fight + ' Neutral-Prägung. Prägungen bezahlen Karten.'),
      h('div', { class: 'imprint-grid' }, a.rows.map((row) => {
        const st = a.phase.settlement[row.id];
        const max = Math.max(1, ...Object.values(st.counts));
        return h('article', { class: 'imprint-card' },
          h('header', null, portrait(row.id), h('div', null, h('h3', null, row.name), h('span', { class: 'insp-sub' }, h('i', { class: 'team-dot', style: '--team:' + teamOf(row.id).color }), teamOf(row.id).name))),
          h('div', { class: 'stacks' }, E.imprint_tokens.map((t) => {
            const tk = tokenById[t];
            return h('div', { class: 'stack' + (t === st.imprint ? ' is-top' : ''), style: '--tok:' + tk.color },
              h('span', null, tk.label), h('span', { class: 'stack-bar' }, h('i', { style: 'width:' + (100 * st.counts[t]) / max + '%' })), h('b', null, st.counts[t]));
          })),
          h('p', { class: 'gain' }, Object.entries(st.gained).map(([t, n]) => chip(tokenById[t], '+' + n + ' ' + tokenById[t].label)),
            st.tied ? h('span', { class: 'insp-empty' }, ' Gleichstand, ausgelost') : null,
            !st.imprint ? h('span', { class: 'insp-empty' }, ' keine Token für eine Prägung') : null),
          h('div', { class: 'wallet' }, h('span', { class: 'label' }, 'Besitz jetzt'), walletChips(CW.season.wallets[row.id])));
      })),
    ];
  }

  function cardTile(c, opts) {
    const cat = catById[c.category];
    return h('div', { class: 'evo-card' + (opts.state ? ' is-' + opts.state : ''), style: '--cat:' + cat.color },
      h('span', { class: 'evo-cat' }, cat.label),
      h('h4', null, c.name),
      h('p', { class: 'evo-cost' }, 'Kosten: ', c.costs.map((cost, i) => [i ? ' oder ' : '', h('b', null, costText(cost))])),
      h('p', { class: 'evo-effect' }, c.effect),
      opts.button || null);
  }

  function afEvolution() {
    const a = CW.after;
    const chosenIds = new Set(a.phase.evolvers.map((e) => e.id));
    const rest = state.roster.filter((r) => !chosenIds.has(r.id));
    return [
      h('p', { class: 'af-note' }, 'Ausgelost: ' + state.evolution.evolvers_per_team + ' Kämpfer pro Team. Jeder zieht bis zu ' + state.evolution.cards_drawn +
        ' Karten, die er mit seinen Prägungen bezahlen kann. Die gewählte Karte kostet Prägungen und wirkt ab dem nächsten Kampf.'),
      ...a.phase.evolvers.map((ev) => {
        const r = rosterById[ev.id];
        const decided = !!ev.chosen;
        return h('section', { class: 'evo-row' + (decided ? ' is-decided' : '') },
          h('div', { class: 'evo-who' },
            portrait(ev.id, 'portrait-lg'),
            h('div', null,
              h('h3', null, r.name),
              h('span', { class: 'insp-sub' }, h('i', { class: 'team-dot', style: '--team:' + teamOf(ev.id).color }), teamOf(ev.id).name),
              h('div', { class: 'wallet' }, h('span', { class: 'label' }, 'Prägungen'), walletChips(CW.season.wallets[ev.id])),
              h('div', { class: 'wallet' }, h('span', { class: 'label' }, 'Karten'), cardList(CW.season.cards[ev.id])),
              decided ? h('p', { class: 'evo-result' }, ev.chosen === 'skip' ? 'Keine Karte genommen.' : 'Gewählt: ' + cardById[ev.chosen].name + ' · bezahlt mit ' + costText(ev.paid)) : null)),
          h('div', { class: 'evo-offers' },
            ev.offers.length ? ev.offers.map((cid) => {
              const c = cardById[cid];
              const pay = !decided ? EVO.payable(c, CW.season.wallets[ev.id]) : null;
              return cardTile(c, {
                state: decided ? (ev.chosen === cid ? 'chosen' : 'faded') : null,
                button: decided ? null : h('button', { class: 'btn btn-primary', type: 'button', onclick: () => {
                  EVO.choose(state, CW.season, ev, cid, a.no);
                  afterChange();
                } }, 'Wählen · ' + costText(pay)),
              });
            }) : h('p', { class: 'insp-empty evo-none' }, 'Keine Karte bezahlbar. Die Prägungen bleiben für das nächste Mal.'),
            !decided && ev.offers.length ? h('button', { class: 'btn evo-skip', type: 'button', onclick: () => { EVO.skip(ev); afterChange(); } }, 'Keine Karte nehmen') : null));
      }),
      h('p', { class: 'af-note' }, 'Diesmal ohne Evolution: ', rest.map((r) => r.name).join(', ') + '. Ihre Prägungen bleiben erhalten.'),
    ];
  }

  function afterChange() {
    renderAfter();
    rerenderDocs(['token-abrechnung', 'roster', 'evolutionen']);
    if (CW.selected) renderInspector();
  }

  $('af-prev').addEventListener('click', () => { if (afStep > 0) { afStep -= 1; renderAfter(); } });
  $('af-next').addEventListener('click', () => {
    if (afStep < 2) { afStep += 1; renderAfter(); return; }
    for (const ev of CW.after.phase.evolvers) EVO.skip(ev);
    rerenderDocs(['evolutionen']);
    newFight(randomSeed());
  });
  $('af-close').addEventListener('click', closeAfter);
  $('btn-after').addEventListener('click', () => openAfter(afStep));
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('afterfight').hidden) closeAfter(); });

  // ---------------------------------------------------------------- Dokumente

  function docHead(id, extra) {
    const sec = state.sections[id] || {};
    return h('header', { class: 'doc-head' },
      h('h1', null, sec.title || itemById[id].label),
      sec.lead ? h('p', null, sec.lead) : null, extra || null);
  }

  const views = {};

  views.live = () => h('section', { class: 'view-live', id: 'view-live' }, stage);

  views.placeholder = (id) => {
    const sec = state.sections[id] || {};
    return h('section', { class: 'doc' }, docHead(id),
      h('div', { class: 'empty' }, h('p', { class: 'empty-title' }, 'Noch leer – in Planung'),
        h('p', { class: 'insp-empty' }, 'Dieser Bereich wird in einem späteren Schritt gebaut. So könnten Einträge aussehen:')),
      h('ul', { class: 'examples' }, (sec.examples || []).map((ex) => h('li', null, h('span', { class: 'tag' }, 'Beispiel'), h('span', null, ex)))));
  };

  views.combatlog = (id) => {
    const s = CW.lastSummary;
    if (!s) {
      return h('section', { class: 'doc' }, docHead(id),
        h('div', { class: 'empty' }, h('p', { class: 'insp-empty' }, 'Noch kein Kampf ausgewertet. Starte in der Live-Ansicht einen Kampf, danach steht hier jeder Schlag.')));
    }
    const list = h('ol', { class: 'log' });
    const evs = [{ type: 'start', t: 0, tokens: [] }, ...s.events];
    for (const ev of evs) list.append(eventLine(ev));
    let f = 'all';
    const chips = h('div', { class: 'console-tools' }, LOG_FILTERS.map(([fid, label]) => h('button', {
      class: 'chip', type: 'button', 'aria-pressed': String(fid === 'all'), 'data-f': fid,
      onclick: (e) => {
        f = fid;
        for (const b of e.currentTarget.parentNode.children) b.setAttribute('aria-pressed', String(b.dataset.f === f));
        applyFilter(list, f);
      },
    }, label)));
    const count = (type) => s.events.filter((e) => e.type === type).length;
    return h('section', { class: 'doc' },
      docHead(id, h('p', { class: 'mono', style: 'color:var(--muted);font-size:12px' },
        'Kampf #' + s.no + ' · Seed ' + s.seed + ' · ' + count('hit') + ' Treffer · ' + count('miss') + ' daneben · ' + count('air') + ' ins Leere · ' + count('ko') + ' K.O.')),
      chips,
      h('div', { class: 'card' }, list));
  };

  views.evolution = (id) => {
    const owners = {};
    for (const [fid, ids] of Object.entries(CW.season.cards)) for (const cid of ids) (owners[cid] = owners[cid] || new Set()).add(fid);
    return h('section', { class: 'doc' }, docHead(id),
      h('h2', null, 'Ablauf nach jedem Kampf'),
      h('ol', { class: 'notes' }, state.evolution.notes.map((n) => h('li', null, n))),
      h('h2', null, 'Kartenpool · ' + state.cards.length + ' Karten'),
      state.card_categories.map((cat) => h('div', { class: 'token-group' },
        h('h3', null, cat.label, h('span', null, cat.text)),
        h('div', { class: 'evo-pool' }, state.cards.filter((c) => c.category === cat.id).map((c) => cardTile(c, {
          button: h('p', { class: 'evo-owners' }, owners[c.id] ? 'Im Besitz von: ' + [...owners[c.id]].map((o) => rosterById[o].name).join(', ') : (c.stackable ? 'stapelbar · ' : '') + 'noch bei niemandem'),
        }))))),
      h('h2', null, 'Evolutionen dieser Saison'),
      CW.season.history.length
        ? h('div', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', { class: 'data' },
          h('thead', null, h('tr', null, h('th', null, 'Kämpfer'), h('th', null, 'Kampf'), h('th', null, 'Karte'), h('th', null, 'Bezahlt'))),
          h('tbody', null, CW.season.history.slice().reverse().map((e) => h('tr', null,
            h('td', null, h('span', { class: 'who' }, portrait(e.id), rosterById[e.id].name)),
            h('td', null, '#' + e.fight),
            h('td', null, cardById[e.card].name),
            h('td', null, costText(e.paid))))))))
        : h('p', { class: 'insp-empty' }, 'Noch keine Evolution. Nach dem ersten Kampf wählen 2 Kämpfer pro Team eine Karte.'));
  };

  views.ledger = (id) => {
    const rows = state.roster.map((r) => ({
      id: r.id, stats: seasonStats(r.id),
      ...(CW.ledger[r.id] || { fights: 0, kos: 0, tokens: Object.fromEntries(TOKEN_IDS.map((t) => [t, 0])) }),
    }));
    return h('section', { class: 'doc' },
      docHead(id, h('p', { class: 'mono', style: 'color:var(--muted);font-size:12px' },
        'Saison: ' + CW.fightsDone + ' Kämpfe ausgewertet · Attribute mit Zuwachs seit Saisonbeginn · nur im Speicher dieser Sitzung')),
      h('div', { class: 'card' }, resultTable(rows, { ledger: true, onPick: (rid) => { location.hash = 'live'; select(rid); } })),
      h('h2', null, 'So wachsen die Attribute'),
      h('dl', { class: 'facts', style: 'max-width:820px;font-size:13px;gap:6px 14px' },
        state.attributes.map((a) => [h('dt', null, a.label), h('dd', null, a.grows)])));
  };

  views.roster = (id) => h('section', { class: 'doc' }, docHead(id),
    Object.values(state.teams).map((team) => [
      h('h2', null, team.name),
      h('div', { class: 'grid-cards' }, state.roster.filter((r) => r.team === team.id).map((r) => {
        const l = CW.ledger[r.id];
        const top = l ? topStack(l.tokens) : null;
        const align = l ? alignmentOf(l.tokens) : null;
        return h('article', { class: 'card' },
          h('img', { class: 'thumb thumb-portrait', alt: 'Porträt ' + r.name, 'data-portrait': r.id, src: CW.portraits[r.id] || null }),
          h('div', { class: 'card-body' },
            h('h3', null, h('i', { class: 'team-dot', style: '--team:' + team.color }), r.name, h('span', { class: 'mono', style: 'color:var(--dim);font-weight:400' }, r.id)),
            h('dl', { class: 'facts' },
              h('dt', null, 'Attribute'), h('dd', null, state.attributes.map((a) => a.short + ' ' + fmt2(seasonStats(r.id)[a.id])).join(' · ')),
              h('dt', null, 'Aussehen'), h('dd', null, [LOOK_LABEL.hair[r.look.hair], 'Bart: ' + LOOK_LABEL.beard[r.look.beard], 'Nase: ' + LOOK_LABEL.nose[r.look.nose]].join(' · ') + (r.look.scar ? ' · Narbe' : '')),
              h('dt', null, 'Kämpfe'), h('dd', null, l ? l.fights : 0),
              h('dt', null, 'Prägung'), h('dd', null, top ? chip(tokenById[top]) : '–'),
              h('dt', null, 'Gesinnung'), h('dd', null, align ? chip(ALIGN[align]) : '–'),
              h('dt', null, 'Prägungen'), h('dd', null, walletChips(CW.season.wallets[r.id], '–')),
              h('dt', null, 'Karten'), h('dd', null, cardList(CW.season.cards[r.id])))));
      }))]));

  views.models = (id) => {
    const stats = state.asset_stats || {};
    const card = (a) => {
      const s = stats[a.web] || {};
      return h('article', { class: 'card' },
        h('canvas', { class: 'thumb', 'data-model': a.web, width: 360, height: 300, 'aria-label': 'Vorschau ' + a.name }),
        h('div', { class: 'card-body' },
          h('h3', null, a.team ? h('i', { class: 'team-dot', style: '--team:' + state.teams[a.team].color }) : null, a.name),
          a.description ? h('p', null, a.description) : null,
          h('dl', { class: 'facts' },
            h('dt', null, 'Dreiecke'), h('dd', null, s.triangles ?? '–'),
            h('dt', null, 'Gelenke'), h('dd', null, s.joints ? s.joints.length : '–'),
            h('dt', null, '.glb'), h('dd', null, h('code', null, a.glb), s.glb_bytes ? ' · ' + kb(s.glb_bytes) : ''),
            h('dt', null, 'Web'), h('dd', null, h('code', null, a.web), s.web_bytes ? ' · ' + kb(s.web_bytes) : ''),
            h('dt', null, 'Quelle'), h('dd', null, h('code', null, a.script)))));
    };
    const groups = {};
    for (const a of CW.assets) (groups[a.category] = groups[a.category] || []).push(a);
    return h('section', { class: 'doc' }, docHead(id),
      Object.entries(groups).map(([cat, list]) => [h('h2', null, cat + ' · ' + list.length), h('div', { class: 'grid-cards' }, list.map(card))]));
  };

  views.animations = (id) => h('section', { class: 'doc' }, docHead(id),
    h('div', { class: 'anim-layout' },
      h('div', { class: 'anim-stage', id: 'anim-stage' }, h('p', { class: 'stage-msg' }, 'Vorschau lädt …')),
      h('div', { class: 'anim-side' },
        h('label', { class: 'field', for: 'anim-fighter' }, 'Kämpfer',
          h('select', { id: 'anim-fighter' }, state.roster.map((r) => h('option', { value: r.id }, r.name + ' (' + teamOf(r.id).short + ')')))),
        h('ul', { class: 'clip-list', id: 'anim-clips' }, state.animations.map((a, i) => h('li', null,
          h('button', { class: 'clip', type: 'button', 'data-clip': a.id, 'aria-pressed': String(i === 2) },
            h('b', null, a.label), h('span', null, a.text))))))));

  views.concept = (id) => {
    const c = state.concept;
    return h('section', { class: 'doc' }, docHead(id),
      h('div', { class: 'pillars' }, c.pillars.map((p) => h('article', { class: 'pillar' }, h('h3', null, p.title), h('p', null, p.text)))),
      h('h2', null, 'Token-Ökosystem'),
      groups.map((g) => h('div', { class: 'token-group' },
        h('h3', null, g.label, h('span', null, g.text)),
        h('ul', { class: 'rules' }, g.tokens.map((t) => h('li', { class: t.active ? '' : 'is-off' },
          chip(t), h('span', null, t.source),
          h('span', { class: 'pill ' + (t.active ? 'pill-done' : 'pill-plan') }, t.active ? 'aktiv' : t.planned)))))),
      h('h2', null, 'Kern-Attribute'),
      h('div', { class: 'table-wrap card' }, h('table', { class: 'data data-text' },
        h('thead', null, h('tr', null, h('th', null, 'Attribut'), h('th', null, 'Wirkung im Kampf'), h('th', null, 'Wächst durch'))),
        h('tbody', null, state.attributes.map((a) => h('tr', null, h('td', null, h('b', null, a.label)), h('td', null, a.effect), h('td', null, a.grows)))))),
      h('h2', null, 'Kampfregeln'),
      h('ul', { class: 'notes' }, state.rules.notes.map((n) => h('li', null, n))),
      h('h2', null, 'Look & Technik'),
      h('dl', { class: 'facts', style: 'max-width:720px;font-size:13px' }, c.style.map((s) => [h('dt', null, s.label), h('dd', null, s.value)])));
  };

  views.roadmap = (id) => {
    const label = { done: 'erledigt', proposal: 'Vorschlag' };
    return h('section', { class: 'doc' }, docHead(id),
      h('ol', { class: 'timeline' }, state.roadmap.map((r) => h('li', { class: 'is-' + r.status },
        h('div', { class: 'when' }, 'Schritt ' + r.step),
        h('div', null,
          h('h3', null, r.title, h('span', { class: 'pill ' + (r.status === 'done' ? 'pill-done' : 'pill-plan') }, label[r.status])),
          h('ul', null, r.items.map((i) => h('li', null, i))))))));
  };

  views.logbook = (id) => h('section', { class: 'doc' }, docHead(id),
    h('ol', { class: 'timeline' }, state.logbook.slice().reverse().map((e) => h('li', { class: 'is-done' },
      h('div', { class: 'when' }, 'v' + e.version, h('small', null, e.date)),
      h('div', null, h('h3', null, e.title), h('ul', null, e.changes.map((c) => h('li', null, c))))))));

  function renderDoc(id) {
    const item = itemById[id];
    const el = (views[item.view] || views.placeholder)(id);
    el.dataset.doc = id;
    return el;
  }

  // Werkstatt-Liste: Basis-Mensch aus state.assets + ein Modell pro Kaempfer + Bodenkachel
  CW.assets = [
    ...state.assets,
    ...state.roster.map((r) => ({
      id: 'fighter_' + r.id, name: r.name, team: r.team, category: 'Kämpfer',
      glb: 'assets/models/fighter_' + r.id + '.glb', web: 'models/fighter_' + r.id + '.gltf.json',
      script: 'models/build_models.py · build_fighter()',
    })),
    { id: 'arena_tile', name: 'Arena-Bodenkachel', category: 'Umgebung', glb: 'assets/models/arena_tile.glb', web: 'models/arena_tile.gltf.json',
      script: 'models/build_models.py · build_arena_tile()', description: 'Sandfarbene Kachel, 5 × 5 m. Die Arena legt 2 × 2 Kacheln aus (10 × 10 m).' },
  ];

  // ---------------------------------------------------------------- Start

  $('brand-version').textContent = 'v' + state.meta.version + ' · Schritt ' + state.meta.step;
  // Architekten-Bruecke: kompletter Code als .txt herunterladen (build_page.py legt project_export.txt daneben)
  $('export-btn').addEventListener('click', async () => {
    const msg = $('export-msg');
    msg.textContent = 'Export wird vorbereitet …';
    try {
      const dl = window.claude && window.claude.use ? await window.claude.use('downloads') : null;
      const res = await fetch('project_export.txt');
      if (!res.ok) throw new Error('project_export.txt fehlt (HTTP ' + res.status + ')');
      const text = await res.text();
      if (!dl) {
        msg.textContent = 'Download ist in dieser Ansicht nicht verfügbar. Lokal: ' + state.meta.export_command;
        return;
      }
      await dl.save({ filename: 'chaos-arena_project_export_v' + state.meta.version + '.txt', data: text });
      msg.textContent = 'Gespeichert, liegt in deinen Downloads.';
    } catch (err) {
      msg.textContent = err && err.code === 'declined' ? 'Download abgebrochen.' : 'Export fehlgeschlagen: ' + ((err && (err.message || err.code)) || err);
    }
  });

  $('console-toggle').addEventListener('click', (e) => {
    const ed = $('editor');
    const min = ed.dataset.console !== 'min';
    ed.dataset.console = min ? 'min' : 'full';
    e.currentTarget.textContent = min ? 'Ausklappen' : 'Einklappen';
    e.currentTarget.setAttribute('aria-expanded', String(!min));
  });
  $('insp-close').addEventListener('click', () => { if (CW.selected) select(CW.selected); });

  newFight(randomSeed());
  showView();
  requestAnimationFrame((t) => { last = t; loop(t); });

  setTimeout(() => {
    if (window.ChaosArena3D) return;
    const msg = $('stage-msg');
    if (msg) msg.textContent = '3D-Ansicht nicht geladen: three.js 0.169 von cdn.jsdelivr.net ist nicht erreichbar. Kampf, Konsole und Auswertung funktionieren trotzdem.';
  }, 12000);
})();
