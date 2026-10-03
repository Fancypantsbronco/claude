/* workspace.js - baut Navigation und alle Bereiche aus data/state.json (eingebettet von build_page.py).
 * Die 3D-Szene kommt separat aus arena3d.js (ES-Modul mit three.js).
 */
(function () {
  'use strict';

  const state = JSON.parse(document.getElementById('state-data').textContent);
  const tokenById = Object.fromEntries(state.tokens.map((t) => [t.id, t]));
  window.ChaosWorkspace = { state };

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

  const kb = (bytes) => (bytes / 1024).toFixed(1).replace('.', ',') + ' KB';
  const metres = (v) => v.toFixed(2).replace('.', ',');

  function sectionHead(item, group, statusPill) {
    const sec = state.sections[item.id] || { title: item.label };
    return h('header', { class: 'sec-head' },
      h('p', { class: 'eyebrow' }, group),
      h('div', { class: 'sec-title-row' },
        h('h1', null, sec.title),
        statusPill),
      sec.lead ? h('p', { class: 'lead' }, sec.lead) : null);
  }

  function pill(kind, text) {
    return h('span', { class: 'pill pill-' + kind }, text);
  }

  // ---------------------------------------------------------------- Ansichten

  const views = {};

  views.placeholder = function (item) {
    const sec = state.sections[item.id] || {};
    return [
      h('div', { class: 'empty' },
        h('p', { class: 'empty-title' }, 'Noch leer – in Planung'),
        h('p', { class: 'empty-text' }, 'Dieser Bereich wird in einem späteren Schritt gebaut. So könnten Einträge aussehen:')),
      h('ul', { class: 'examples' },
        (sec.examples || []).map((ex) =>
          h('li', null, h('span', { class: 'tag' }, 'Beispiel'), h('span', null, ex)))),
    ];
  };

  views.live = function () {
    const proto = state.prototype;
    const session = window.ChaosTokens.createSession(state);
    const teams = proto.turn_order.map((id) => state.teams[id]);

    const log = h('ol', { class: 'log', id: 'combat-log', 'aria-live': 'polite' });
    const logEmpty = h('p', { class: 'log-empty' }, proto.log_empty);
    const totalEl = h('span', { class: 'total-num' }, '0');
    const roundEl = h('span', null, '0');
    const tiles = {};
    const fighterCells = {};

    const counter = h('section', { class: 'counter', 'aria-label': 'Token-Zähler' },
      h('div', { class: 'counter-head' },
        h('div', null,
          h('p', { class: 'label' }, 'Token dieser Session'),
          h('p', { class: 'sub' }, 'Runde ', roundEl)),
        totalEl),
      h('div', { class: 'tiles' },
        state.tokens.map((t) => {
          const num = h('span', { class: 'tile-num' }, '0');
          tiles[t.id] = num;
          return h('div', { class: 'tile' + (t.active ? '' : ' tile-off'), style: '--tok:' + t.color },
            h('span', { class: 'tile-label' }, h('i', { class: 'dot' }), t.label),
            num,
            h('span', { class: 'tile-src' }, t.active ? t.source : t.planned_for));
        })),
      h('table', { class: 'per-fighter' },
        h('thead', null, h('tr', null,
          h('th', { scope: 'col' }, 'Kämpfer'),
          state.tokens.filter((t) => t.active).map((t) => h('th', { scope: 'col' }, t.label)))),
        h('tbody', null, teams.map((team) => {
          fighterCells[team.id] = {};
          return h('tr', null,
            h('th', { scope: 'row' }, h('i', { class: 'swatch', style: '--team:' + team.color }), team.fighter),
            state.tokens.filter((t) => t.active).map((t) => {
              const td = h('td', null, '0');
              fighterCells[team.id][t.id] = td;
              return td;
            }));
        }))));

    function refreshCounter() {
      const totals = session.totals;
      const per = session.perFighter;
      totalEl.textContent = session.total;
      roundEl.textContent = session.round;
      for (const [id, el] of Object.entries(tiles)) el.textContent = totals[id];
      for (const [fid, cells] of Object.entries(fighterCells)) {
        for (const [tid, td] of Object.entries(cells)) td.textContent = per[fid][tid];
      }
    }

    function addLogEntry(ev) {
      const team = state.teams[ev.attacker];
      const token = tokenById[ev.token];
      logEmpty.hidden = true;
      const li = h('li', { class: ev.hit ? 'hit' : 'miss' },
        h('span', { class: 'log-round' }, 'R' + ev.round),
        h('div', { class: 'log-body' },
          h('p', { class: 'log-swing' }, h('i', { class: 'swatch', style: '--team:' + team.color }), ev.swingText),
          h('p', { class: 'log-result', style: '--tok:' + token.color }, ev.resultText),
          h('p', { class: 'log-roll' },
            'W100 ', h('b', null, ev.roll), ev.hit ? ' ≤ ' : ' > ', ev.threshold)));
      log.append(li);
      log.scrollTop = log.scrollHeight;
    }

    const rollBtn = h('button', {
      class: 'btn-roll', id: 'roll-btn', type: 'button',
      onclick: () => {
        const ev = session.roll();
        addLogEntry(ev);
        refreshCounter();
        window.dispatchEvent(new CustomEvent('chaos:roll', { detail: ev }));
      },
    }, proto.button_label);

    const resetBtn = h('button', {
      class: 'btn-quiet', id: 'reset-btn', type: 'button',
      onclick: () => {
        session.reset();
        log.replaceChildren();
        logEmpty.hidden = false;
        refreshCounter();
        window.dispatchEvent(new CustomEvent('chaos:reset'));
      },
    }, proto.reset_label);

    const stage = h('div', { class: 'stage', id: 'stage' },
      h('p', { class: 'stage-msg', id: 'stage-msg' }, '3D-Ansicht lädt …'));

    return [
      h('div', { class: 'live' },
        h('figure', { class: 'stage-wrap' },
          stage,
          h('figcaption', { class: 'stage-legend' },
            teams.map((t) => h('span', { class: 'legend-chip' },
              h('i', { class: 'swatch', style: '--team:' + t.color }), t.fighter, ' · ', t.name)),
            h('span', { class: 'legend-cam' }, 'Iso · orthografisch · 45° gedreht · 30° von oben'))),
        h('div', { class: 'combat' },
          rollBtn,
          h('div', { class: 'log-wrap' },
            h('p', { class: 'label' }, 'Kampf-Log'),
            logEmpty,
            log),
          counter,
          resetBtn)),
    ];
  };

  views.models = function () {
    const stats = state.asset_stats || {};
    const groups = {};
    for (const a of state.assets) (groups[a.category] = groups[a.category] || []).push(a);
    return Object.entries(groups).map(([cat, assets]) =>
      h('section', { class: 'model-group' },
        h('h2', null, cat),
        h('div', { class: 'model-grid' }, assets.map((a) => {
          const s = stats[a.web] || {};
          return h('article', { class: 'model-card' },
            h('canvas', { class: 'thumb', 'data-model': a.web, width: 360, height: 300, 'aria-label': 'Vorschau ' + a.name }),
            h('div', { class: 'model-body' },
              h('h3', null, a.name),
              h('p', null, a.description),
              h('dl', { class: 'facts' },
                h('dt', null, 'Dreiecke'), h('dd', null, s.triangles ?? '–'),
                h('dt', null, 'Maße (B×H×T)'), h('dd', null, s.size_m ? s.size_m.map(metres).join(' × ') + ' m' : '–'),
                h('dt', null, '.glb'), h('dd', null, h('code', null, a.glb), s.glb_bytes ? ' · ' + kb(s.glb_bytes) : ''),
                h('dt', null, 'Web'), h('dd', null, h('code', null, a.web), s.web_bytes ? ' · ' + kb(s.web_bytes) : ''),
                h('dt', null, 'Quelle'), h('dd', null, h('code', null, a.script)))));
        }))));
  };

  views.concept = function () {
    const c = state.concept;
    return [
      h('div', { class: 'pillars' }, c.pillars.map((p) =>
        h('article', { class: 'pillar' }, h('h3', null, p.title), h('p', null, p.text)))),
      h('section', { class: 'block' },
        h('h2', null, 'Token-Regeln'),
        h('ul', { class: 'rules' }, c.token_rules.map((r) => {
          const t = tokenById[r.token];
          return h('li', { style: '--tok:' + t.color },
            h('span', { class: 'token-chip' }, t.label),
            h('span', null, r.rule),
            t.active ? pill('live', 'im Prototyp') : pill('planned', t.planned_for));
        }))),
      h('section', { class: 'block' },
        h('h2', null, 'Look & Technik'),
        h('dl', { class: 'facts facts-wide' }, c.style.map((s) => [h('dt', null, s.label), h('dd', null, s.value)]))),
    ];
  };

  views.roadmap = function () {
    const label = { done: 'erledigt', active: 'in Arbeit', proposal: 'Vorschlag' };
    return h('ol', { class: 'roadmap' }, state.roadmap.map((r) =>
      h('li', { class: 'road-' + r.status },
        h('div', { class: 'road-step' }, 'Schritt ', r.step),
        h('div', { class: 'road-body' },
          h('div', { class: 'road-title' }, h('h3', null, r.title), pill(r.status === 'done' ? 'live' : 'planned', label[r.status])),
          h('ul', null, r.items.map((i) => h('li', null, i)))))));
  };

  views.logbook = function () {
    return h('ol', { class: 'logbook' }, state.logbook.slice().reverse().map((e) =>
      h('li', null,
        h('div', { class: 'lb-meta' }, h('span', { class: 'lb-version' }, 'v' + e.version), h('time', { datetime: e.date }, e.date)),
        h('div', null,
          h('h3', null, e.title),
          h('ul', null, e.changes.map((c) => h('li', null, c)))))));
  };

  // ---------------------------------------------------------------- Aufbau

  const nav = document.getElementById('nav');
  const main = document.getElementById('sections');
  const links = {};
  const sections = {};
  const groupOf = {};

  for (const group of state.nav) {
    nav.append(h('div', { class: 'nav-group' },
      h('p', { class: 'nav-group-label' }, group.group),
      h('ul', null, group.items.map((item) => {
        groupOf[item.id] = group.group;
        const planned = item.view === 'placeholder';
        const a = h('a', { href: '#' + item.id, class: planned ? 'is-planned' : '' },
          h('span', null, item.label),
          h('i', { class: 'nav-dot', title: planned ? 'in Planung' : 'vorhanden' }));
        links[item.id] = a;
        return h('li', null, a);
      }))));

    for (const item of group.items) {
      const planned = item.view === 'placeholder';
      const body = (views[item.view] || views.placeholder)(item);
      const sec = h('section', { class: 'view view-' + item.view, id: 'view-' + item.id, hidden: true },
        sectionHead(item, group.group, planned ? pill('planned', 'in Planung') : null),
        body);
      sections[item.id] = sec;
      main.append(sec);
    }
  }

  document.getElementById('brand-name').textContent = state.meta.name;
  document.getElementById('brand-step').textContent = 'v' + state.meta.version + ' · Schritt ' + state.meta.step;
  document.getElementById('export-cmd').textContent = state.meta.export_command;
  document.getElementById('export-file').textContent = state.meta.export_file;

  document.getElementById('export-copy').addEventListener('click', (e) => {
    const btn = e.currentTarget;
    const done = (text) => { btn.textContent = text; setTimeout(() => { btn.textContent = 'Kopieren'; }, 1600); };
    navigator.clipboard.writeText(state.meta.export_command).then(() => done('Kopiert'), () => {
      const range = document.createRange();
      range.selectNodeContents(document.getElementById('export-cmd'));
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      done('Markiert');
    });
  });

  // ---------------------------------------------------------------- Routing (#id)

  function show() {
    const id = location.hash.slice(1);
    const current = sections[id] ? id : 'live';
    for (const [key, sec] of Object.entries(sections)) sec.hidden = key !== current;
    for (const [key, a] of Object.entries(links)) {
      if (key === current) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    }
    window.dispatchEvent(new CustomEvent('chaos:view', { detail: { id: current } }));
  }
  window.addEventListener('hashchange', () => { show(); window.scrollTo(0, 0); });
  show();

  // Wenn three.js nicht nachlaedt (z.B. CDN blockiert), Hinweis statt endlosem Ladetext.
  setTimeout(() => {
    if (window.ChaosArena3D) return;
    const msg = document.getElementById('stage-msg');
    if (msg) msg.textContent = '3D-Ansicht nicht geladen: three.js 0.169 von cdn.jsdelivr.net ist nicht erreichbar. Würfel und Log funktionieren trotzdem.';
  }, 12000);
})();
