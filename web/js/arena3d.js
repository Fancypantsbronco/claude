/* arena3d.js - three.js-Szene der Live-Ansicht und Modell-Vorschauen in der Werkstatt.
 * Laedt die .gltf.json-Dateien (eingebetteter Buffer), baut daraus intern ein GLB
 * und uebergibt es dem GLTFLoader. So entsteht kein Netzwerkzugriff auf data-URIs.
 * Statische Szene: gerendert wird nur bei Aenderungen, keine Dauerschleife.
 */
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';

const { state } = window.ChaosWorkspace;
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

  let text = new TextEncoder().encode(JSON.stringify(gltf));
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

// ---------------------------------------------------------------- Gemeinsames

const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

function addLights(scene, shadows) {
  scene.add(new THREE.HemisphereLight(0xfff3df, 0x5a4a36, 1.7));
  const sun = new THREE.DirectionalLight(0xfff0d8, 2.4);
  sun.position.set(-3, 9, 6);
  if (shadows) {
    sun.castShadow = true;
    sun.shadow.mapSize.set(1024, 1024);
    Object.assign(sun.shadow.camera, { left: -4, right: 4, top: 4, bottom: -4, near: 1, far: 25 });
    sun.shadow.bias = -0.0005;
    sun.shadow.normalBias = 0.02;
  }
  scene.add(sun);
}

// Iso-Kamera: 45° um Y gedreht, 30° von oben, orthografisch.
const AZIMUTH = THREE.MathUtils.degToRad(45);
const ELEVATION = THREE.MathUtils.degToRad(30);

function placeIso(camera, target) {
  const d = 30;
  camera.position.set(
    target.x + d * Math.cos(ELEVATION) * Math.sin(AZIMUTH),
    target.y + d * Math.sin(ELEVATION),
    target.z + d * Math.cos(ELEVATION) * Math.cos(AZIMUTH));
  camera.lookAt(target);
}

function setFrustum(camera, halfH, aspect) {
  camera.left = -halfH * aspect;
  camera.right = halfH * aspect;
  camera.top = halfH;
  camera.bottom = -halfH;
  camera.updateProjectionMatrix();
}

// ---------------------------------------------------------------- Live-Ansicht

async function mountArena() {
  const stage = document.getElementById('stage');
  const msg = document.getElementById('stage-msg');
  if (!stage) return;

  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  stage.prepend(renderer.domElement);

  const scene = new THREE.Scene();
  const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 100);
  const target = new THREE.Vector3(0, 0.55, 0);
  placeIso(camera, target);
  addLights(scene, true);

  const render = () => renderer.render(scene, camera);

  function resize() {
    const w = stage.clientWidth;
    const hgt = stage.clientHeight;
    if (!w || !hgt) return;
    renderer.setSize(w, hgt, false);
    const aspect = w / hgt;
    // Kachel (5 m) muss in Breite und Hoehe ganz ins Bild passen.
    setFrustum(camera, Math.max(2.35, 3.75 / aspect), aspect);
    render();
  }

  function applyTheme() {
    scene.background = new THREE.Color(cssVar('--stage'));
    render();
  }

  // Kaempfer stehen sich auf der Bildschirm-Horizontalen gegenueber, leicht zur Kamera gedreht.
  const screenRight = new THREE.Vector3(Math.cos(AZIMUTH), 0, -Math.sin(AZIMUTH));
  const placement = {
    A: { pos: screenRight.clone().multiplyScalar(-0.95), rotY: THREE.MathUtils.degToRad(100) },
    B: { pos: screenRight.clone().multiplyScalar(0.95), rotY: THREE.MathUtils.degToRad(-10) },
  };

  const rings = {};
  try {
    const [tile, ...fighters] = await Promise.all([
      loadModel(state.prototype.arena_tile),
      ...state.prototype.turn_order.map((id) => loadModel(state.teams[id].model)),
    ]);
    tile.traverse((o) => { if (o.isMesh) o.castShadow = false; });
    scene.add(tile);

    state.prototype.turn_order.forEach((id, i) => {
      const p = placement[id] || { pos: new THREE.Vector3(i * 1.5 - 0.75, 0, 0), rotY: 0 };
      const fighter = fighters[i];
      fighter.position.copy(p.pos);
      fighter.rotation.y = p.rotY;
      fighter.name = 'fighter_' + id;
      scene.add(fighter);

      const ring = new THREE.Mesh(
        new THREE.RingGeometry(0.62, 0.74, 8, 1),
        new THREE.MeshBasicMaterial({ color: state.teams[id].color, transparent: true, opacity: 0.45, depthWrite: false }));
      ring.rotation.x = -Math.PI / 2;
      ring.position.set(p.pos.x, 0.035, p.pos.z);
      scene.add(ring);
      rings[id] = ring;
    });
    msg.hidden = true;
  } catch (err) {
    msg.textContent = 'Modelle konnten nicht geladen werden: ' + err.message;
    console.error(err);
  }

  window.addEventListener('chaos:roll', (e) => {
    for (const [id, ring] of Object.entries(rings)) ring.material.opacity = id === e.detail.attacker ? 1 : 0.3;
    render();
  });
  window.addEventListener('chaos:reset', () => {
    for (const ring of Object.values(rings)) ring.material.opacity = 0.45;
    render();
  });
  window.addEventListener('chaos:view', (e) => { if (e.detail.id === 'live') requestAnimationFrame(resize); });
  new ResizeObserver(resize).observe(stage);
  watchTheme(applyTheme);
  applyTheme();
  resize();
}

// ---------------------------------------------------------------- Werkstatt-Vorschauen

async function renderThumbs() {
  const canvases = [...document.querySelectorAll('canvas[data-model]')];
  if (!canvases.length) return;
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(1);
  renderer.setSize(canvases[0].width, canvases[0].height, false);

  for (const canvas of canvases) {
    try {
      const model = await loadModel(canvas.dataset.model);
      const scene = new THREE.Scene();
      addLights(scene, false);
      scene.add(model);
      const box = new THREE.Box3().setFromObject(model);
      const sphere = box.getBoundingSphere(new THREE.Sphere());
      const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 100);
      placeIso(camera, sphere.center);
      setFrustum(camera, sphere.radius * 0.82, canvas.width / canvas.height);
      renderer.render(scene, camera);
      const ctx = canvas.getContext('2d');
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(renderer.domElement, 0, 0, canvas.width, canvas.height);
    } catch (err) {
      console.error(err);
      canvas.replaceWith(Object.assign(document.createElement('p'), {
        className: 'thumb thumb-error', textContent: 'Vorschau fehlgeschlagen: ' + err.message,
      }));
    }
  }
  renderer.dispose();
}

function watchTheme(fn) {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', fn);
  new MutationObserver(fn).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
}

window.ChaosArena3D = { three: THREE.REVISION };
mountArena();
renderThumbs();
