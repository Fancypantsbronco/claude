/* arena3d.js - three.js-Darstellung: Arena, prozedurale Animationen, Porträts,
 * Modell-Vorschauen und die Animations-Werkstatt.
 *
 * Die .gltf.json-Dateien haben einen eingebetteten Buffer (data-URI). Daraus wird im
 * Browser ein GLB gebaut und dem GLTFLoader uebergeben - so entsteht kein Netzwerk-
 * zugriff auf data-URIs. Animationen sind prozedural: pro Frame werden die Gelenke
 * (hips, torso, head, arm_L, arm_R, leg_L, leg_R) aus dem Kampfzustand gedreht.
 */
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';

const CW = window.ChaosWorkspace;
const { state } = CW;
const R = state.rules;
const loader = new GLTFLoader();
const cache = new Map();

// ---------------------------------------------------------------- Modelle laden

function embeddedGltfToGlb(json) {
  const gltf = structuredClone(json);
  const uri = gltf.buffers[0].uri;
  delete gltf.buffers[0].uri;
  const raw = atob(uri.slice(uri.indexOf(',') + 1));
  const bin = new Uint8Array(raw.length + (-raw.length & 3));
  for (let i = 0; i < raw.length; i++) bin[i] = raw.charCodeAt(i);

  const text = new TextEncoder().encode(JSON.stringify(gltf));
  const jsonBytes = new Uint8Array(text.length + (-text.length & 3)).fill(0x20);
  jsonBytes.set(text);

  const total = 12 + 8 + jsonBytes.length + 8 + bin.length;
  const out = new ArrayBuffer(total);
  const view = new DataView(out);
  view.setUint32(0, 0x46546c67, true);
  view.setUint32(4, 2, true);
  view.setUint32(8, total, true);
  view.setUint32(12, jsonBytes.length, true);
  view.setUint32(16, 0x4e4f534a, true);
  new Uint8Array(out, 20, jsonBytes.length).set(jsonBytes);
  view.setUint32(20 + jsonBytes.length, bin.length, true);
  view.setUint32(24 + jsonBytes.length, 0x004e4942, true);
  new Uint8Array(out, 28 + jsonBytes.length, bin.length).set(bin);
  return out;
}

function loadModel(url) {
  if (!cache.has(url)) {
    cache.set(url, fetch(url)
      .then((r) => {
        if (!r.ok) throw new Error(url + ': HTTP ' + r.status);
        return r.json();
      })
      .then((json) => new Promise((resolve, reject) => loader.parse(embeddedGltfToGlb(json), '', resolve, reject))));
  }
  return cache.get(url).then((gltf) => {
    const obj = gltf.scene.clone(true);
    obj.traverse((o) => {
      if (o.isMesh) {
        o.material.flatShading = true;
        o.castShadow = true;
        o.receiveShadow = true;
      }
    });
    return obj;
  });
}

const fighterUrl = (id) => 'models/fighter_' + id + '.gltf.json';
const TILE_URL = 'models/arena_tile.gltf.json';

// ---------------------------------------------------------------- Gemeinsames

const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const clamp01 = (v) => Math.max(0, Math.min(1, v));
const lerp = (a, b, k) => a + (b - a) * k;
const easeInOut = (k) => k * k * (3 - 2 * k);
const easeOut = (k) => 1 - (1 - k) * (1 - k);

function addLights(scene, shadowSize) {
  scene.add(new THREE.HemisphereLight(0xfff3df, 0x4a3d2e, 1.6));
  const sun = new THREE.DirectionalLight(0xfff0d8, 2.5);
  sun.position.set(-4, 10, 7);
  if (shadowSize) {
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    Object.assign(sun.shadow.camera, { left: -shadowSize, right: shadowSize, top: shadowSize, bottom: -shadowSize, near: 1, far: 40 });
    sun.shadow.bias = -0.0005;
    sun.shadow.normalBias = 0.02;
  }
  scene.add(sun);
}

// Iso-Kamera: 45° um Y gedreht, 30° von oben, orthografisch.
const AZIMUTH = THREE.MathUtils.degToRad(45);
const ELEVATION = THREE.MathUtils.degToRad(30);

function placeCamera(camera, target, az = AZIMUTH, el = ELEVATION) {
  const d = 40;
  camera.position.set(
    target.x + d * Math.cos(el) * Math.sin(az),
    target.y + d * Math.sin(el),
    target.z + d * Math.cos(el) * Math.cos(az));
  camera.lookAt(target);
}

function setFrustum(camera, halfH, aspect) {
  camera.left = -halfH * aspect;
  camera.right = halfH * aspect;
  camera.top = halfH;
  camera.bottom = -halfH;
  camera.updateProjectionMatrix();
}

// ---------------------------------------------------------------- Puppe: Rig + Pose

class Puppet {
  constructor(id, model) {
    this.id = id;
    this.holder = new THREE.Group();     // Weltposition + Blickrichtung
    this.holder.add(model);
    this.holder.userData.fighterId = id;
    const n = (name) => model.getObjectByName(name);
    this.root = n(id) || model;          // kippt beim K.O.
    this.j = {
      hips: n('hips'), torso: n('torso'), head: n('head'),
      armL: n('arm_L'), armR: n('arm_R'), legL: n('leg_L'), legR: n('leg_R'),
    };
    this.hipY = this.j.hips.position.y;
    this.phase = id.charCodeAt(0) * 1.7 + id.charCodeAt(1) * 0.9;
    this.mat = null;
    model.traverse((o) => {
      if (!o.isMesh) return;
      if (!this.mat) this.mat = o.material.clone();
      o.material = this.mat;
      o.userData.fighterId = id;
    });
    this.yaw = 0;
  }

  // s: { state, stateT, walkPhase, sinceHit, downFor, time }
  pose(s) {
    const { hips, torso, head, armL, armR, legL, legR } = this.j;
    for (const j of Object.values(this.j)) j.rotation.set(0, 0, 0);
    hips.position.y = this.hipY;
    this.root.rotation.set(0, 0, 0);
    this.root.position.y = 0;

    const t = s.time + this.phase;
    torso.rotation.x = Math.sin(t * 2.1) * 0.025;
    head.rotation.y = Math.sin(t * 0.9) * 0.35;           // blind umherschauen
    head.rotation.x = Math.sin(t * 1.3) * 0.05;
    armL.rotation.z = 0.05 + Math.sin(t * 2.1) * 0.02;
    armR.rotation.z = -0.05 - Math.sin(t * 2.1) * 0.02;

    switch (s.state) {
      case 'walk': {
        const w = Math.sin(s.walkPhase);
        legL.rotation.x = w * 0.55;
        legR.rotation.x = -w * 0.55;
        armL.rotation.x = -w * 0.4;
        armR.rotation.x = w * 0.4;
        hips.position.y = this.hipY + Math.abs(Math.cos(s.walkPhase)) * 0.035;
        torso.rotation.y = w * 0.08;
        torso.rotation.x += 0.06;
        break;
      }
      case 'windup': {
        const k = easeInOut(clamp01(s.stateT / R.windup_s));
        armR.rotation.x = -3.6 * k;                         // Arm hoch und nach hinten
        armR.rotation.z -= 0.25 * k;
        armL.rotation.x = -0.7 * k;
        torso.rotation.y = -0.4 * k;
        torso.rotation.x -= 0.08 * k;
        head.rotation.y *= 1 - k;
        break;
      }
      case 'recover': {
        const strike = 0.12;
        const a = s.stateT;
        const k = a < strike ? 1 : 1 - easeInOut(clamp01((a - strike) / (R.recover_s - strike)));
        armR.rotation.x = a < strike ? lerp(-3.6, -1.1, easeOut(a / strike)) : -1.1 * k;
        armL.rotation.x = -0.7 * k;
        torso.rotation.y = 0.3 * k;
        torso.rotation.x += 0.18 * k;
        head.rotation.y *= 1 - k;
        break;
      }
      case 'stagger': {
        const k = Math.sin(Math.PI * clamp01(s.stateT / R.stagger_s));
        torso.rotation.x = -0.45 * k;
        head.rotation.x = -0.5 * k;
        armL.rotation.z = 0.7 * k;
        armR.rotation.z = -0.7 * k;
        armL.rotation.x = armR.rotation.x = -0.4 * k;
        legL.rotation.x = 0.25 * k;
        legR.rotation.x = -0.15 * k;
        break;
      }
      case 'down': {
        const k = clamp01(s.downFor / 0.6);
        const fall = k * k;
        this.root.rotation.x = (-Math.PI / 2) * fall;      // nach hinten umkippen
        this.root.position.y = 0.18 * fall;
        armL.rotation.z = 1.2 * k;
        armR.rotation.z = -1.2 * k;
        head.rotation.set(0, 0.4 * k, 0);
        torso.rotation.x = 0;
        break;
      }
      default:
        break;
    }
    const flash = Math.max(0, 1 - s.sinceHit / 0.2);
    this.mat.emissive.setRGB(0.9 * flash, 0.12 * flash, 0.04 * flash);
  }
}

function makeRing(color, inner, outer, opacity) {
  const ring = new THREE.Mesh(
    new THREE.RingGeometry(inner, outer, 10, 1),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity, depthWrite: false }));
  ring.rotation.x = -Math.PI / 2;
  return ring;
}

async function buildFloor(scene) {
  for (const [x, z] of [[-2.5, -2.5], [2.5, -2.5], [-2.5, 2.5], [2.5, 2.5]]) {
    const tile = await loadModel(TILE_URL);
    tile.position.set(x, 0, z);
    tile.traverse((o) => { if (o.isMesh) o.castShadow = false; });
    scene.add(tile);
  }
}

// ---------------------------------------------------------------- Live-Arena

async function mountArena() {
  const stage = CW.stageEl;
  const msg = stage.querySelector('.stage-msg');
  const labelsEl = stage.querySelector('.labels');

  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  stage.prepend(renderer.domElement);

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(cssVar('--stage'));
  const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 100);
  const target = new THREE.Vector3(0, 0.6, 0);
  placeCamera(camera, target);
  addLights(scene, 7);

  function resize() {
    const w = stage.clientWidth;
    const hgt = stage.clientHeight;
    if (!w || !hgt) return;
    renderer.setSize(w, hgt, false);
    const aspect = w / hgt;
    setFrustum(camera, Math.max(4.4, 7.5 / aspect), aspect);
  }
  new ResizeObserver(resize).observe(stage);

  const puppets = {};
  const labels = {};
  const selRing = makeRing(0xffffff, 0.5, 0.58, 0.9);
  selRing.visible = false;
  scene.add(selRing);

  try {
    await buildFloor(scene);
    const models = await Promise.all(state.roster.map((r) => loadModel(fighterUrl(r.id))));
    state.roster.forEach((r, i) => {
      const p = new Puppet(r.id, models[i]);
      const ring = makeRing(state.teams[r.team].color, 0.36, 0.44, 0.75);
      ring.position.y = 0.04;
      p.holder.add(ring);
      scene.add(p.holder);
      puppets[r.id] = p;
      const bar = document.createElement('span');
      bar.className = 'hpbar';
      bar.append(document.createElement('i'));
      const name = document.createElement('span');
      name.textContent = r.name;
      const el = document.createElement('div');
      el.className = 'flabel';
      el.append(name, bar);
      labelsEl.append(el);
      labels[r.id] = { el, bar, name };
    });
    msg.hidden = true;
  } catch (err) {
    msg.textContent = 'Modelle konnten nicht geladen werden: ' + err.message;
    console.error(err);
    return;
  }

  // Auswahl per Klick
  const ray = new THREE.Raycaster();
  const ndc = new THREE.Vector2();
  renderer.domElement.addEventListener('click', (e) => {
    const rect = renderer.domElement.getBoundingClientRect();
    ndc.set(((e.clientX - rect.left) / rect.width) * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1);
    ray.setFromCamera(ndc, camera);
    const hit = ray.intersectObjects(Object.values(puppets).map((p) => p.holder), true).find((h) => h.object.userData.fighterId);
    const id = hit ? hit.object.userData.fighterId : null;
    if (id && id !== CW.selected) CW.select(id);        // anderen Kaempfer waehlen
    else if (!id && CW.selected) CW.select(CW.selected); // Klick ins Leere hebt Auswahl auf
  });

  // Token-Einblendungen
  const tokenById = Object.fromEntries(state.tokens.map((t) => [t.id, t]));
  const v = new THREE.Vector3();
  function screenPos(id, y) {
    const f = CW.fight.byId[id];
    v.set(f.x, y, f.z).project(camera);
    return [(v.x * 0.5 + 0.5) * stage.clientWidth, (-v.y * 0.5 + 0.5) * stage.clientHeight];
  }
  const popups = {};
  window.addEventListener('chaos:events', (e) => {
    if (CW.view !== 'live') return;
    for (const ev of e.detail) {
      const per = {};
      for (const tk of ev.tokens) (per[tk.fighter] = per[tk.fighter] || []).push(tk.token);
      for (const [fid, toks] of Object.entries(per)) {
        const [x, y] = screenPos(fid, 2.6);
        // Pro Kaempfer nur eine Einblendung gleichzeitig, sonst entsteht bei 4x ein Knaeuel
        if (popups[fid]) popups[fid].remove();
        const pop = document.createElement('div');
        popups[fid] = pop;
        pop.className = 'popup';
        pop.style.left = x + 'px';
        pop.style.top = y + 'px';
        for (const t of toks) {
          const b = document.createElement('b');
          b.style.setProperty('--tok', tokenById[t].color);
          b.textContent = '+1 ' + tokenById[t].short + ' ';
          pop.append(b);
        }
        if (ev.type === 'hit' && ev.victim === fid) {
          const d = document.createElement('span');
          d.className = 'dmg';
          d.textContent = '−' + ev.dmg;
          pop.append(d);
        }
        labelsEl.append(pop);
        setTimeout(() => { pop.remove(); if (popups[fid] === pop) delete popups[fid]; }, 1300);
      }
    }
  });
  window.addEventListener('chaos:fight-new', () => {
    for (const el of labelsEl.querySelectorAll('.popup')) el.remove();
    for (const p of Object.values(puppets)) p.yaw = null;
  });

  // Renderschleife
  let last = performance.now();
  function frame(now) {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    requestAnimationFrame(frame);
    if (CW.view !== 'live' || !CW.fight) return;
    const fight = CW.fight;
    const a = CW.running ? CW.alpha : 1;
    const time = now / 1000;
    for (const f of fight.fighters) {
      const p = puppets[f.id];
      const x = lerp(f.px, f.x, a);
      const z = lerp(f.pz, f.z, a);
      p.holder.position.set(x, 0, z);
      if (p.yaw == null) p.yaw = f.heading;
      const diff = Math.atan2(Math.sin(f.heading - p.yaw), Math.cos(f.heading - p.yaw));
      p.yaw += diff * Math.min(1, dt * 10 * Math.max(1, CW.speed));
      p.holder.rotation.y = p.yaw;
      p.pose({
        state: f.state,
        stateT: f.stateT + (CW.running ? a * fight.dt : 0),
        walkPhase: f.walkPhase,
        sinceHit: (fight.t - f.lastHitT) / Math.max(1, CW.speed),
        downFor: f.state === 'down' ? fight.t - f.downT : 0,
        time,
      });
      const lb = labels[f.id];
      v.set(x, 2.35, z).project(camera);
      lb.el.style.left = (v.x * 0.5 + 0.5) * stage.clientWidth + 'px';
      lb.el.style.top = (-v.y * 0.5 + 0.5) * stage.clientHeight + 'px';
      const pct = (100 * f.hp) / f.maxHp;
      lb.bar.firstChild.style.width = pct + '%';
      lb.bar.classList.toggle('low', pct <= 35);
      lb.el.classList.toggle('is-down', f.state === 'down');
      lb.el.classList.toggle('is-selected', f.id === CW.selected);
      lb.name.textContent = f.state === 'down' ? f.name + ' · K.O.' : f.name;
    }
    if (CW.selected && puppets[CW.selected]) {
      const p = puppets[CW.selected];
      selRing.visible = true;
      selRing.position.set(p.holder.position.x, 0.05, p.holder.position.z);
    } else {
      selRing.visible = false;
    }
    renderer.render(scene, camera);
  }
  window.addEventListener('chaos:view', (e) => { if (e.detail.id === 'live') requestAnimationFrame(resize); });
  resize();
  requestAnimationFrame(frame);
}

// ---------------------------------------------------------------- Offscreen: Porträts + Vorschauen

let offscreen = null;
function getOffscreen() {
  if (!offscreen) {
    offscreen = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
    offscreen.setPixelRatio(1);
  }
  return offscreen;
}

async function renderPortraits() {
  const r = getOffscreen();
  r.setSize(160, 160, false);
  for (const f of state.roster) {
    const model = await loadModel(fighterUrl(f.id));
    const scene = new THREE.Scene();
    scene.background = new THREE.Color(cssVar('--stage'));
    addLights(scene, 0);
    scene.add(model);
    const cam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 100);
    placeCamera(cam, new THREE.Vector3(0, 1.74, 0.04), THREE.MathUtils.degToRad(28), THREE.MathUtils.degToRad(8));
    setFrustum(cam, 0.33, 1);
    r.render(scene, cam);
    CW.portraits[f.id] = r.domElement.toDataURL('image/png');
  }
  window.dispatchEvent(new CustomEvent('chaos:portraits'));
}

async function renderThumbs() {
  const canvases = [...document.querySelectorAll('canvas[data-model]:not([data-done])')];
  if (!canvases.length) return;
  const r = getOffscreen();
  for (const canvas of canvases) {
    canvas.dataset.done = '1';
    try {
      r.setSize(canvas.width, canvas.height, false);
      const model = await loadModel(canvas.dataset.model);
      const scene = new THREE.Scene();
      addLights(scene, 0);
      scene.add(model);
      const box = new THREE.Box3().setFromObject(model);
      const sphere = box.getBoundingSphere(new THREE.Sphere());
      const cam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 100);
      placeCamera(cam, sphere.center);
      setFrustum(cam, sphere.radius * 0.85, canvas.width / canvas.height);
      r.render(scene, cam);
      const ctx = canvas.getContext('2d');
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(r.domElement, 0, 0, canvas.width, canvas.height);
    } catch (err) {
      console.error(err);
      canvas.replaceWith(Object.assign(document.createElement('p'), { className: 'thumb insp-empty', textContent: 'Vorschau fehlgeschlagen: ' + err.message }));
    }
  }
}

// ---------------------------------------------------------------- Werkstatt: Animations-Vorschau

let preview = null;
async function mountAnimPreview() {
  const host = document.getElementById('anim-stage');
  if (!host || host.dataset.mounted) return;
  host.dataset.mounted = '1';
  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.shadowMap.enabled = true;
  host.prepend(renderer.domElement);
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(cssVar('--stage'));
  addLights(scene, 3);
  const tile = await loadModel(TILE_URL);
  tile.traverse((o) => { if (o.isMesh) o.castShadow = false; });
  scene.add(tile);
  const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 100);
  placeCamera(camera, new THREE.Vector3(0, 0.95, 0));
  host.querySelector('.stage-msg').hidden = true;

  preview = { renderer, scene, camera, puppet: null, clip: 'swing', t0: performance.now() / 1000, host };

  async function setFighter(id) {
    if (preview.puppet) scene.remove(preview.puppet.holder);
    preview.puppet = new Puppet(id, await loadModel(fighterUrl(id)));
    preview.puppet.holder.rotation.y = 0.35;
    scene.add(preview.puppet.holder);
  }
  await setFighter(state.roster[0].id);
  document.getElementById('anim-fighter').addEventListener('change', (e) => setFighter(e.target.value));
  const clips = document.getElementById('anim-clips');
  clips.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-clip]');
    if (!btn) return;
    preview.clip = btn.dataset.clip;
    preview.t0 = performance.now() / 1000;
    for (const b of clips.querySelectorAll('[data-clip]')) b.setAttribute('aria-pressed', String(b === btn));
  });

  function resize() {
    const w = host.clientWidth;
    const hgt = host.clientHeight;
    if (!w || !hgt) return;
    renderer.setSize(w, hgt, false);
    setFrustum(camera, 1.45, w / hgt);
  }
  new ResizeObserver(resize).observe(host);
  resize();

  function frame(now) {
    requestAnimationFrame(frame);
    if (CW.view !== 'animationen' || !preview.puppet) return;
    const time = now / 1000;
    const t = time - preview.t0;
    const s = { state: 'idle', stateT: 0, walkPhase: 0, sinceHit: 9, downFor: 0, time };
    switch (preview.clip) {
      case 'walk':
        s.state = 'walk';
        s.walkPhase = t * R.move_speed_mps * 5.5;
        break;
      case 'swing': {
        const c = t % (R.windup_s + R.recover_s + 0.6);
        if (c < R.windup_s) { s.state = 'windup'; s.stateT = c; }
        else if (c < R.windup_s + R.recover_s) { s.state = 'recover'; s.stateT = c - R.windup_s; }
        break;
      }
      case 'hit': {
        const c = t % (R.stagger_s + 0.8);
        if (c < R.stagger_s) { s.state = 'stagger'; s.stateT = c; s.sinceHit = c; }
        break;
      }
      case 'ko': {
        const c = t % 3;
        if (c < 2.3) { s.state = 'down'; s.downFor = c; }
        break;
      }
      default:
        break;
    }
    preview.puppet.pose(s);
    renderer.render(scene, camera);
  }
  requestAnimationFrame(frame);
}

// ---------------------------------------------------------------- Start

window.ChaosArena3D = { three: THREE.REVISION };
window.addEventListener('chaos:view', (e) => {
  if (e.detail.id === 'modelle') requestAnimationFrame(renderThumbs);
  if (e.detail.id === 'animationen') mountAnimPreview();
});
window.addEventListener('chaos:docs-updated', () => requestAnimationFrame(renderThumbs));
mountArena().then(renderPortraits);
if (CW.view === 'modelle') renderThumbs();
if (CW.view === 'animationen') mountAnimPreview();
