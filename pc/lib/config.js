// Panel configuration: pages, tiles, look. Stored in data/config.json.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const DATA_DIR = require('./paths').DATA;
const FILE = path.join(DATA_DIR, 'config.json');

const HOME_SLOTS = 8;
const GRID_SLOTS = 12;
const BIG_SLOTS = 6;

// Page layouts: home (A), 4 × 3 grid, big 3 × 2 tiles, sliders (volume mixer).
const LAYOUTS = ['home', 'grid', 'big', 'sliders'];

function defaults() {
  return {
    theme: { tint: 'Emerald', clockFont: 'Fraunces', media: 'volume' },
    weather: { city: 'London', lat: 51.5072, lon: -0.1276 },
    pages: [
      { id: 'home', name: 'Home', layout: 'home', tiles: ['room', 'led', 'plug', 'night', 'mute', 'obs', 'steam', 'screenshot'] },
      { id: 'pc', name: 'PC', layout: 'grid', tiles: ['lock', 'volup', 'voldown', 'volmute', 'desktop', 'taskmgr', 'explorer', 'browser', 'monitor', null, null, 'sleep'] }
    ],
    tiles: {
      room: { label: 'Room', icon: 'bulb', kind: 'light', device: null },
      led: { label: 'Desk LED', icon: 'led', kind: 'light', device: null },
      plug: { label: 'Plug', icon: 'plug', kind: 'macro', toggle: true, steps: [] },
      night: { label: 'Night', icon: 'moon', kind: 'macro', steps: [] },
      mute: { label: 'Mute', icon: 'mic-off', kind: 'macro', toggle: true, steps: [{ type: 'keys', combo: 'ctrl+shift+m' }] },
      obs: { label: 'OBS', icon: 'record', kind: 'macro', toggle: true, steps: [{ type: 'keys', combo: 'ctrl+shift+f9' }] },
      steam: { label: 'Steam', icon: 'gamepad', kind: 'macro', steps: [{ type: 'url', url: 'steam://open/main' }] },
      screenshot: { label: 'Screenshot', icon: 'camera', kind: 'macro', steps: [{ type: 'keys', combo: 'win+shift+s' }] },
      lock: { label: 'Lock', icon: 'lock', kind: 'macro', steps: [{ type: 'system', cmd: 'lock' }] },
      volup: { label: 'Volume +', icon: 'volume', kind: 'macro', steps: [{ type: 'media', key: 'volup' }, { type: 'media', key: 'volup' }] },
      voldown: { label: 'Volume −', icon: 'volume', kind: 'macro', steps: [{ type: 'media', key: 'voldown' }, { type: 'media', key: 'voldown' }] },
      volmute: { label: 'Mute PC', icon: 'volume-x', kind: 'macro', toggle: true, steps: [{ type: 'media', key: 'mute' }] },
      desktop: { label: 'Desktop', icon: 'monitor', kind: 'macro', steps: [{ type: 'keys', combo: 'win+d' }] },
      taskmgr: { label: 'Task Manager', icon: 'terminal', kind: 'macro', steps: [{ type: 'keys', combo: 'ctrl+shift+esc' }] },
      explorer: { label: 'Files', icon: 'folder', kind: 'macro', steps: [{ type: 'keys', combo: 'win+e' }] },
      browser: { label: 'Browser', icon: 'globe', kind: 'macro', steps: [{ type: 'url', url: 'https://www.google.com' }] },
      sleep: { label: 'Sleep PC', icon: 'power', kind: 'macro', steps: [{ type: 'system', cmd: 'sleep' }] },
      monitor: { label: 'Second Monitor', icon: 'monitor', kind: 'display' },
      touchpad: { label: 'Touchpad', icon: 'touchpad', kind: 'touchpad' },
      presenter: { label: 'Presentation', icon: 'slides', kind: 'presenter' },
      draw: { label: 'Drawing', icon: 'pen', kind: 'draw' }
    },
    lights: [],
    devices: { allowed: [] },
    spotify: { clientId: '' },
    notify: { enabled: true, muted: [] },
    // The tablet's clock screensaver. font '' = the same as the home clock; photo = an uploaded file's id.
    saver: {
      background: 'black', photo: null, dim: 35, font: '', position: 'center', size: 100, color: 'white',
      date: true, weather: true, claude: false, seconds: false, drift: true
    },
    // Custom clock fonts uploaded in the settings window: [{ id, name }].
    fonts: [],
    // The virtual monitor for "Second monitor": auto = detach it while the tablet is not showing it.
    vmonitor: { auto: true, pos: null }
  };
}

const POSITIONS = ['top-left', 'top', 'top-right', 'left', 'center', 'right', 'bottom-left', 'bottom', 'bottom-right'];
const clampNum = (v, lo, hi, dflt) => (Number.isFinite(Number(v)) ? Math.max(lo, Math.min(hi, Math.round(Number(v)))) : dflt);

let config = null;
const listeners = new Set();

function slotsFor(layout) {
  return { home: HOME_SLOTS, grid: GRID_SLOTS, big: BIG_SLOTS, sliders: 0 }[layout] ?? GRID_SLOTS;
}

// Repairs the configuration so the tablet never receives nonsense (missing fields, wrong slot count).
function normalize(c) {
  const d = defaults();
  c.theme = { ...d.theme, ...(c.theme || {}) };
  c.weather = { ...d.weather, ...(c.weather || {}) };
  c.tiles = c.tiles && typeof c.tiles === 'object' ? c.tiles : {};
  c.lights = Array.isArray(c.lights) ? c.lights : [];
  c.devices = { allowed: [], ...(c.devices || {}) };
  // This PC's id: lets a tablet that knows several PCs keep a separate pairing key and layout for each.
  // Not a secret (it is in the announcement); trust still comes only from the pairing key.
  if (typeof c.pcId !== 'string' || !/^[a-f0-9]{16}$/.test(c.pcId)) c.pcId = crypto.randomBytes(8).toString('hex');
  c.spotify = { ...d.spotify, ...(c.spotify || {}) };
  c.notify = { ...d.notify, ...(c.notify || {}) };
  c.notify.enabled = c.notify.enabled !== false;
  c.notify.muted = Array.isArray(c.notify.muted) ? c.notify.muted.filter((x) => typeof x === 'string') : [];
  c.fonts = (Array.isArray(c.fonts) ? c.fonts : [])
    .filter((f) => f && /^[a-f0-9]{32}$/.test(f.id))
    .map((f) => ({ id: f.id, name: String(f.name || 'Custom font').slice(0, 40) }));
  const s = { ...d.saver, ...(c.saver && typeof c.saver === 'object' ? c.saver : {}) };
  c.saver = {
    background: ['black', 'tint', 'photo'].includes(s.background) ? s.background : 'black',
    photo: /^[a-f0-9]{32}$/.test(String(s.photo)) ? s.photo : null,
    dim: clampNum(s.dim, 0, 90, 35),
    font: typeof s.font === 'string' ? s.font.slice(0, 60) : '',
    position: POSITIONS.includes(s.position) ? s.position : 'center',
    size: clampNum(s.size, 40, 220, 100),
    color: ['white', 'tint', 'warm'].includes(s.color) ? s.color : 'white',
    date: s.date !== false, weather: s.weather !== false, claude: !!s.claude, seconds: !!s.seconds, drift: s.drift !== false
  };
  if (c.saver.background === 'photo' && !c.saver.photo) c.saver.background = 'black';
  const vm = c.vmonitor && typeof c.vmonitor === 'object' ? c.vmonitor : {};
  const pos = vm.pos && typeof vm.pos === 'object' ? vm.pos : null;
  c.vmonitor = {
    auto: vm.auto !== false,
    pos: pos && [pos.w, pos.h, pos.x, pos.y].every(Number.isFinite) && pos.w >= 320 && pos.h >= 200
      ? { w: Math.round(pos.w), h: Math.round(pos.h), x: Math.round(pos.x), y: Math.round(pos.y) } : null
  };
  if (!Array.isArray(c.pages) || c.pages.length === 0) c.pages = d.pages;
  // The home page is always first and there is only one.
  c.pages = c.pages.filter((p, i) => i === 0 || p.layout !== 'home');
  if (c.pages[0].layout !== 'home') c.pages.unshift({ id: 'home', name: 'Home', layout: 'home', tiles: [] });
  for (const p of c.pages) {
    p.id = p.id || crypto.randomBytes(4).toString('hex');
    p.name = p.name || 'Page';
    p.layout = LAYOUTS.includes(p.layout) ? p.layout : 'grid';
    const n = slotsFor(p.layout);
    const t = Array.isArray(p.tiles) ? p.tiles.slice(0, n) : [];
    while (t.length < n) t.push(null);
    p.tiles = t.map((id) => (id && c.tiles[id] ? id : null));
  }
  for (const t of Object.values(c.tiles)) {
    t.label = t.label || '';
    t.icon = t.icon || 'star';
    t.kind = ['light', 'page', 'macro', 'display', 'touchpad', 'presenter', 'draw'].includes(t.kind) ? t.kind : 'macro';
    if (!Array.isArray(t.steps)) t.steps = [];
    if (!Array.isArray(t.stepsOff)) t.stepsOff = [];
  }
  return c;
}

// Your layout must never be replaced by the defaults just because the file could not be read for a moment
// (antivirus, backup or sync tools sometimes hold it): try again, and if it really is broken, keep a copy.
function load() {
  if (fs.existsSync(FILE)) {
    let lastErr;
    for (let i = 0; i < 5; i++) {
      try {
        const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
        const hadId = raw.pcId; // normalize() fills in a new id on the same object
        config = normalize(raw);
        if (hadId !== config.pcId) save();
        return config;
      } catch (e) {
        lastErr = e;
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 200);
      }
    }
    const broken = path.join(DATA_DIR, `config.broken-${stamp()}.json`);
    try { fs.copyFileSync(FILE, broken); } catch {}
    console.error(`Settings could not be read (${lastErr && lastErr.message}); a copy is in ${path.basename(broken)}, starting with the defaults.`);
    const restored = latestBackup();
    if (restored) {
      config = restored;
      console.error('Restored the newest backup of the settings instead.');
      save();
      return config;
    }
  }
  config = normalize(defaults());
  save();
  return config;
}

const BACKUP_DIR = path.join(DATA_DIR, 'backups');
const stamp = (d = new Date()) => d.toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);

// Before the file changes, the previous version is kept (at most one copy per 10 minutes, the newest 30),
// so any earlier layout can be brought back from the settings window.
function backup() {
  if (!fs.existsSync(FILE)) return;
  fs.mkdirSync(BACKUP_DIR, { recursive: true });
  const list = backups();
  if (list.length && Date.now() - list[0].at < 10 * 60000) return;
  fs.copyFileSync(FILE, path.join(BACKUP_DIR, `config-${stamp()}.json`));
  for (const old of backups().slice(30)) {
    try { fs.unlinkSync(path.join(BACKUP_DIR, old.file)); } catch {}
  }
}

// Newest first: { file, at, pages, tiles }.
function backups() {
  let files = [];
  try { files = fs.readdirSync(BACKUP_DIR).filter((f) => /^config-\d{8}-\d{6}\.json$/.test(f)); } catch {}
  return files.map((file) => {
    let at = 0;
    try { at = fs.statSync(path.join(BACKUP_DIR, file)).mtimeMs; } catch {}
    return { file, at };
  }).sort((a, b) => b.at - a.at);
}

function readBackup(file) {
  if (!/^config-\d{8}-\d{6}\.json$/.test(String(file))) throw new Error('Unknown backup');
  return normalize(JSON.parse(fs.readFileSync(path.join(BACKUP_DIR, file), 'utf8')));
}

function latestBackup() {
  for (const b of backups()) {
    try { return readBackup(b.file); } catch {}
  }
  return null;
}

// A backup brought back keeps today's paired tablets (their keys are not in old copies).
function restore(file) {
  const c = readBackup(file);
  c.devices = get().devices;
  c.pcId = get().pcId;
  return set(c);
}

function save() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  try { backup(); } catch {}
  const tmp = FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(config, null, 2));
  fs.renameSync(tmp, FILE);
}

function get() {
  return config || load();
}

function set(next) {
  config = normalize(next);
  save();
  for (const fn of listeners) fn(config);
  return config;
}

function update(mutator) {
  const c = JSON.parse(JSON.stringify(get()));
  mutator(c);
  return set(c);
}

// ---------- saving by changes ----------
// The settings window sends only what it changed (path + value), so it never overwrites changes made elsewhere
// (tablet, another window, server). Pages are addressed by id, not by position.

function toMap(c) {
  const m = { ...c, pages: {}, pageOrder: (c.pages || []).map((p) => p.id) };
  for (const p of c.pages || []) m.pages[p.id] = p;
  return m;
}

function fromMap(m) {
  const { pageOrder, ...rest } = m;
  rest.pages = (pageOrder || []).map((id) => m.pages[id]).filter(Boolean);
  return rest;
}

// ops: [{ path: ['tiles', 'room', 'label'], value }] or [{ path, del: true }]
function patch(ops) {
  const m = toMap(JSON.parse(JSON.stringify(get())));
  for (const op of Array.isArray(ops) ? ops : []) {
    const path = Array.isArray(op.path) ? op.path.map(String) : [];
    if (!path.length || path.some((k) => k === '__proto__' || k === 'constructor' || k === 'prototype')) continue;
    if (path[0] === 'devices' || path[0] === 'pcId') continue; // allowed tablets change only by pairing; the PC id never
    let o = m;
    for (const k of path.slice(0, -1)) {
      if (o[k] === null || typeof o[k] !== 'object' || Array.isArray(o[k])) o[k] = {};
      o = o[k];
    }
    const last = path[path.length - 1];
    if (op.del) delete o[last];
    else o[last] = op.value;
  }
  return set(fromMap(m));
}

function onChange(fn) {
  listeners.add(fn);
}

// The part of the configuration the tablet needs (without secrets like Spotify and the device list).
function forTablet() {
  const c = get();
  return { theme: c.theme, pages: c.pages, tiles: c.tiles, notify: { enabled: c.notify.enabled }, saver: c.saver, fonts: c.fonts };
}

module.exports = { DATA_DIR, load, get, set, update, patch, onChange, forTablet, slotsFor, backups, readBackup, restore };
