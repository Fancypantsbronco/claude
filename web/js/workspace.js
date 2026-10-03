/* workspace.js - Editor-Oberflaeche von Chaos Arena.
 * Baut Projektbaum, Szene, Inspector, Konsole und alle Dokumente aus data/state.json
 * und steuert den Kampf (combat.js). Die 3D-Darstellung kommt aus arena3d.js und liest
 * den Kampfzustand ueber window.ChaosWorkspace.
 */
(function () {
  'use strict';

  const state = JSON.parse(document.getElementById('state-data').textContent);
  const { createFight, topStack, TOKEN_IDS } = window.ChaosCombat;
  const tokenById = Object.fromEntries(state.tokens.map((t) => [t.id, t]));
  const rosterById = Object.fromEntries(state.roster.map((r) => [r.id, r]));
  const teamOf = (id) => state.teams[rosterById[id].team];

  const CW = {
    state, fight: null, alpha: 0, speed: 1, running: false, selected: null,
    portraits: {}, fightNo: 0, lastSummary: null, ledger: {}, fightsDone: 0, view: 'live',
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
    CW.fight = createFight(state, seed);
    CW.fightNo += 1;
    CW.running = false;
    CW.lastSummary = null;
    acc = 0;
    CW.alpha = 0;
    $('seed-input').value = seed;
    consoleLog.replaceChildren();
    $('console-empty').hidden = false;
    $('console-empty').textContent = 'Kampf #' + CW.fightNo + ' bereit · Seed ' + seed + '. ▶ Kampf starten.';
    results.hidden = true;
    emit('chaos:fight-new', { seed });
    refreshLive(true);
  }

  function play() {
    const f = CW.fight;
    if (f.done) { newFight(randomSeed()); }
    CW.running = !CW.running;
    if (CW.running && CW.fight.t === 0) {
      appendLog([{ type: 'start', t: 0, tokens: [] }]);
      results.hidden = true;
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
    }
    renderResults();
    results.hidden = false;
    rerenderDocs(['kampf-log', 'token-abrechnung', 'roster']);
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
      insp.append(
        h('div', { class: 'insp-block' },
          h('h3', null, 'Kampf #' + CW.fightNo + ' · Seed ' + f.seed),
          h('p', { class: 'insp-empty' }, 'Klick auf einen Kämpfer im Viewport oder in der Szene, um ihn zu inspizieren.')),
        h('div', { class: 'insp-block' },
          h('h3', null, 'Token je Team'),
          Object.values(state.teams).map((team) => h('dl', { class: 'kv' },
            h('dt', null, h('span', { class: 'insp-sub' }, h('i', { class: 'team-dot', style: '--team:' + team.color }), team.name)), h('dd', null, ''),
            state.tokens.map((t) => [h('dt', null, t.label), h('dd', { style: 'color:' + t.color }, teamTotals[team.id][t.id])])))),
        h('div', { class: 'insp-block' },
          h('h3', null, 'Regeln'),
          h('dl', { class: 'kv' },
            h('dt', null, 'Kampfdauer'), h('dd', null, R.duration_s + ' s'),
            h('dt', null, 'Lebenspunkte'), h('dd', null, R.hp_base + R.hp_per_toughness * R.base_stats.toughness),
            h('dt', null, 'Trefferchance'), h('dd', null, Math.round(R.hit_chance * 100) + ' %'),
            h('dt', null, 'Schaden'), h('dd', null, R.damage_min + '–' + R.damage_max),
            h('dt', null, 'Reichweite'), h('dd', null, num(R.reach_m, 2) + ' m'),
            h('dt', null, 'Ausholen / Erholen'), h('dd', null, num(R.windup_s, 1) + ' / ' + num(R.recover_s, 1) + ' s'))));
      return;
    }
    const r = rosterById[CW.selected];
    const ff = f.byId[r.id];
    const team = teamOf(r.id);
    const stackRefs = {};
    const statRefs = {};
    const hpBar = hpbar(ff.hp, ff.maxHp);
    const hpText = h('span', null, '');
    const stateText = h('span', null, '');
    insp.append(
      h('div', { class: 'insp-head' },
        portrait(r.id),
        h('div', null,
          h('h2', null, r.name),
          h('p', { class: 'insp-sub' }, h('i', { class: 'team-dot', style: '--team:' + team.color }), team.name, ' · ', r.id, ' · ', stateText))),
      h('div', { class: 'hp-big' }, hpBar, hpText),
      h('div', { class: 'insp-block' },
        h('h3', null, 'Werte'),
        h('div', { class: 'statgrid' },
          [['Stärke', 'strength'], ['Zähigkeit', 'toughness'], ['Geschick', 'agility']].map(([label, key]) =>
            h('div', { class: 'stat' }, h('span', null, label), h('b', null, ff.stats[key]))))),
      h('div', { class: 'insp-block' },
        h('h3', null, 'Token-Stapel'),
        h('div', { class: 'stacks' }, state.tokens.map((t) => {
          const bar = h('i');
          const val = h('b', null, '0');
          const row = h('div', { class: 'stack', style: '--tok:' + t.color }, h('span', null, t.label), h('span', { class: 'stack-bar' }, bar), val);
          stackRefs[t.id] = { row, bar, val };
          return row;
        }))),
      h('div', { class: 'insp-block' },
        h('h3', null, 'Statistik'),
        h('dl', { class: 'kv' }, [
          ['swings', 'Schläge'], ['hits', 'Treffer'], ['misses', 'Daneben'], ['air', 'Ins Leere'],
          ['friendly_hits', 'Eigentreffer'], ['damage_dealt', 'Schaden ausgeteilt'], ['damage_taken', 'Schaden erlitten'],
          ['interrupted', 'Schlag unterbrochen'], ['distance', 'Strecke (m)'],
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
    inspRefs = { id: r.id, hpBar, hpText, stateText, stackRefs, statRefs };
    refreshInspector();
  }

  function refreshInspector() {
    if (!inspRefs) { if (!CW.selected) renderInspectorTotals(); return; }
    const ff = CW.fight.byId[inspRefs.id];
    setHp(inspRefs.hpBar, ff.hp, ff.maxHp);
    inspRefs.hpText.textContent = ff.hp + ' / ' + ff.maxHp + ' LP';
    inspRefs.stateText.textContent = STATE_LABEL[ff.state];
    const max = Math.max(1, ...TOKEN_IDS.map((t) => ff.tokens[t]));
    const top = topStack(ff.tokens);
    for (const [t, ref] of Object.entries(inspRefs.stackRefs)) {
      ref.val.textContent = ff.tokens[t];
      ref.bar.style.width = (100 * ff.tokens[t]) / max + '%';
      ref.row.classList.toggle('is-top', t === top);
    }
    for (const [key, dd] of Object.entries(inspRefs.statRefs)) {
      dd.textContent = key === 'distance' ? num(ff.record.distance, 1) : ff.record[key];
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
    ['all', 'Alle'], ['hit', 'Treffer'], ['friendly', 'Eigentreffer'], ['miss', 'Daneben'], ['air', 'Ins Leere'],
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
    switch (ev.type) {
      case 'start': msg = ['Kampf #' + CW.fightNo + ' gestartet · Seed ' + CW.fight.seed + ' · ' + state.rules.duration_s + ' s']; break;
      case 'end': msg = ['Kampfende nach ' + state.rules.duration_s + ' s']; break;
      case 'hit':
        cls = 'k-hit f-hit' + (ev.friendly ? ' f-friendly' : '');
        msg = [nameSpan(ev.attacker), ev.friendly ? ' trifft Teamkamerad ' : ' trifft ', nameSpan(ev.victim),
          ' · W100 ' + ev.roll + ' ≤ ' + ev.threshold + ' · −' + ev.dmg + ' LP (' + ev.hp + ')'];
        break;
      case 'miss':
        cls = 'k-miss f-miss';
        msg = [nameSpan(ev.attacker), ' schlägt nach ', nameSpan(ev.victim), ' · daneben · W100 ' + ev.roll + ' > ' + ev.threshold];
        break;
      case 'air': cls = 'k-air f-air'; msg = [nameSpan(ev.attacker), ' schlägt ins Leere']; break;
      case 'ko': cls = 'k-ko f-hit'; msg = [nameSpan(ev.victim), ' geht K.O. (durch ', nameSpan(ev.attacker), ')']; break;
      default: msg = [ev.type];
    }
    return h('li', { class: cls },
      h('span', { class: 't' }, clock(ev.t)),
      h('span', { class: 'msg' }, msg),
      h('span', { class: 'toks' }, ev.tokens.map((tk) =>
        h('span', { class: 'tk', style: '--tok:' + tokenById[tk.token].color }, '+1 ' + tokenById[tk.token].short + ' ' + rosterById[tk.fighter].name))));
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
  const results = h('section', { class: 'results', hidden: true, 'aria-label': 'Auswertung' });
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
      r.st.textContent = STATE_LABEL[ff.state];
    }
    const totals = teamTokenTotals();
    for (const [team, el] of Object.entries(hudRows)) {
      const alive = f.fighters.filter((x) => x.team === team && x.state !== 'down').length;
      el.textContent = ' ' + alive + '/5 · ' + state.tokens.map((t) => t.short + ' ' + totals[team][t.id]).join(' · ');
    }
    if (force) renderInspector(); else refreshInspector();
    $('status-left').textContent = 'three.js ' + (window.ChaosArena3D ? 'r' + window.ChaosArena3D.three : 'lädt') +
      ' · ' + state.roster.length + ' Kämpfer · Kampf #' + CW.fightNo + ' · Seed ' + f.seed + ' · ' + CW.fightsDone + ' ausgewertet';
  }

  function resultTable(rows, opts) {
    const cols = [
      ['swings', 'Schläge'], ['hits', 'Treffer'], ['misses', 'Daneben'], ['air', 'Leere'],
      ['friendly_hits', 'Eigen'], ['damage_dealt', 'Schaden +'], ['damage_taken', 'Schaden −'],
    ];
    return h('div', { class: 'table-wrap' }, h('table', { class: 'data' },
      h('thead', null, h('tr', null,
        h('th', { scope: 'col' }, 'Kämpfer'),
        opts.ledger ? [h('th', { scope: 'col' }, 'Kämpfe'), h('th', { scope: 'col' }, 'K.O.')] : h('th', { scope: 'col' }, 'LP'),
        opts.ledger ? null : cols.map(([, label], i) => h('th', { scope: 'col', class: i === 0 ? 'grp' : '' }, label)),
        state.tokens.map((t, i) => h('th', { scope: 'col', class: i === 0 ? 'grp' : '', style: 'color:' + t.color }, t.short)),
        h('th', { scope: 'col', class: 'grp' }, 'Größter Stapel'))),
      h('tbody', null, rows.map((row) => {
        const top = topStack(row.tokens);
        return h('tr', { onclick: () => { if (opts.onPick) opts.onPick(row.id); } },
          h('td', null, h('span', { class: 'who' }, portrait(row.id), h('span', null, rosterById[row.id].name,
            row.ko && !opts.ledger ? h('span', { class: 'ko-tag' }, ' K.O.') : null))),
          opts.ledger ? [h('td', null, row.fights), h('td', null, row.kos)] : h('td', null, row.hp + '/' + row.maxHp),
          opts.ledger ? null : cols.map(([key], i) => h('td', { class: i === 0 ? 'grp' : '' }, row.record[key])),
          state.tokens.map((t, i) => h('td', { class: (i === 0 ? 'grp ' : '') + (row.tokens[t.id] ? 'tok' : 'zero'), style: '--tok:' + t.color }, row.tokens[t.id])),
          h('td', { class: 'grp' }, top ? h('span', { class: 'top-chip', style: '--tok:' + tokenById[top].color }, tokenById[top].label) : '–'));
      }))));
  }

  function renderResults() {
    const s = CW.lastSummary;
    const totals = teamTokenTotals();
    const kos = s.rows.filter((r) => r.ko).length;
    results.replaceChildren(
      h('div', { class: 'results-head' },
        h('h2', null, 'Auswertung'),
        h('span', { class: 'meta' }, 'Kampf #' + s.no + ' · Seed ' + s.seed + ' · ' + state.rules.duration_s + ' s · ' + (kos ? kos + ' K.O.' : 'kein K.O.')),
        h('div', { class: 'actions' },
          h('button', { class: 'btn', type: 'button', onclick: () => { results.hidden = true; } }, 'Arena ansehen'),
          h('button', { class: 'btn btn-primary', type: 'button', onclick: () => { newFight(randomSeed()); play(); } }, '▶ Neuer Kampf'))),
      h('div', { class: 'team-sum' }, Object.values(state.teams).map((team) =>
        h('span', null, h('i', { class: 'team-dot', style: '--team:' + team.color }), h('b', null, team.name),
          state.tokens.map((t) => h('span', { style: 'color:' + t.color }, ' ' + t.short + ' ' + totals[team.id][t.id]))))),
      resultTable(s.rows, { onPick: select }));
  }

  // ---------------------------------------------------------------- Dokumente

  function docHead(id, extra) {
    const sec = state.sections[id] || {};
    return h('header', { class: 'doc-head' },
      h('h1', null, sec.title || itemById[id].label),
      sec.lead ? h('p', null, sec.lead) : null, extra || null);
  }

  const views = {};

  views.live = () => h('section', { class: 'view-live', id: 'view-live' }, stage, results);

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

  views.ledger = (id) => {
    const rows = state.roster.map((r) => ({ id: r.id, ...(CW.ledger[r.id] || { fights: 0, kos: 0, tokens: Object.fromEntries(TOKEN_IDS.map((t) => [t, 0])) }) }));
    return h('section', { class: 'doc' },
      docHead(id, h('p', { class: 'mono', style: 'color:var(--muted);font-size:12px' },
        CW.fightsDone + ' Kämpfe ausgewertet · nur im Speicher dieser Sitzung (dauerhaft ab Schritt 3)')),
      h('div', { class: 'card' }, resultTable(rows, { ledger: true, onPick: (rid) => { location.hash = 'live'; select(rid); } })));
  };

  views.roster = (id) => h('section', { class: 'doc' }, docHead(id),
    Object.values(state.teams).map((team) => [
      h('h2', null, team.name),
      h('div', { class: 'grid-cards' }, state.roster.filter((r) => r.team === team.id).map((r) => {
        const l = CW.ledger[r.id];
        const top = l ? topStack(l.tokens) : null;
        return h('article', { class: 'card' },
          h('img', { class: 'thumb thumb-portrait', alt: 'Porträt ' + r.name, 'data-portrait': r.id, src: CW.portraits[r.id] || null }),
          h('div', { class: 'card-body' },
            h('h3', null, h('i', { class: 'team-dot', style: '--team:' + team.color }), r.name, h('span', { class: 'mono', style: 'color:var(--dim);font-weight:400' }, r.id)),
            h('dl', { class: 'facts' },
              h('dt', null, 'Werte'), h('dd', null, 'Stärke 1 · Zähigkeit 1 · Geschick 1'),
              h('dt', null, 'Aussehen'), h('dd', null, [LOOK_LABEL.hair[r.look.hair], 'Bart: ' + LOOK_LABEL.beard[r.look.beard], 'Nase: ' + LOOK_LABEL.nose[r.look.nose]].join(' · ') + (r.look.scar ? ' · Narbe' : '')),
              h('dt', null, 'Kämpfe'), h('dd', null, l ? l.fights : 0),
              h('dt', null, 'Größter Stapel'), h('dd', null, top ? h('span', { class: 'top-chip', style: '--tok:' + tokenById[top].color }, tokenById[top].label) : '–'))));
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
      h('h2', null, 'Token-Regeln'),
      h('ul', { class: 'rules' }, c.token_rules.map((r) => {
        const t = tokenById[r.token];
        return h('li', null, h('span', { class: 'top-chip', style: '--tok:' + t.color }, t.label), h('span', null, r.rule));
      })),
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
  $('export-cmd').textContent = state.meta.export_command;
  $('export-file').textContent = state.meta.export_file;
  $('export-copy').addEventListener('click', (e) => {
    const btn = e.currentTarget;
    const done = (text) => { btn.textContent = text; setTimeout(() => { btn.textContent = 'Kopieren'; }, 1600); };
    navigator.clipboard.writeText(state.meta.export_command).then(() => done('Kopiert'), () => {
      const range = document.createRange();
      range.selectNodeContents($('export-cmd'));
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      done('Markiert');
    });
  });

  newFight(randomSeed());
  showView();
  requestAnimationFrame((t) => { last = t; loop(t); });

  setTimeout(() => {
    if (window.ChaosArena3D) return;
    const msg = $('stage-msg');
    if (msg) msg.textContent = '3D-Ansicht nicht geladen: three.js 0.169 von cdn.jsdelivr.net ist nicht erreichbar. Kampf, Konsole und Auswertung funktionieren trotzdem.';
  }, 12000);
})();
