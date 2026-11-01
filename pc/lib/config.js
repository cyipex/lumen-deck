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
      monitor: { label: 'Second Monitor', icon: 'monitor', kind: 'display' }
    },
    lights: [],
    devices: { allowed: [] },
    spotify: { clientId: '' },
    notify: { enabled: true, muted: [] }
  };
}

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
  c.spotify = { ...d.spotify, ...(c.spotify || {}) };
  c.notify = { ...d.notify, ...(c.notify || {}) };
  c.notify.enabled = c.notify.enabled !== false;
  c.notify.muted = Array.isArray(c.notify.muted) ? c.notify.muted.filter((x) => typeof x === 'string') : [];
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
    t.kind = ['light', 'page', 'macro', 'display'].includes(t.kind) ? t.kind : 'macro';
    if (!Array.isArray(t.steps)) t.steps = [];
    if (!Array.isArray(t.stepsOff)) t.stepsOff = [];
  }
  return c;
}

function load() {
  try {
    config = normalize(JSON.parse(fs.readFileSync(FILE, 'utf8')));
  } catch {
    config = normalize(defaults());
    save();
  }
  return config;
}

function save() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
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
    if (path[0] === 'devices') continue; // the list of allowed tablets is changed only by pairing
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
  return { theme: c.theme, pages: c.pages, tiles: c.tiles, notify: { enabled: c.notify.enabled } };
}

module.exports = { DATA_DIR, load, get, set, update, patch, onChange, forTablet, slotsFor };
