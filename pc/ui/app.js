// Lumen Desk settings window. Every change is saved right away and sent to the tablet.
const TINTS = [
  ['Graphite', '#3A3A3D'], ['Ocean', '#2F4A8A'], ['Indigo', '#3A3478'], ['Lavender', '#5E4B8B'], ['Rose', '#7A4458'],
  ['Terracotta', '#7A3E2E'], ['Amber', '#8A5A2B'], ['Sand', '#7A6A4A'], ['Sage', '#4E6B5C'], ['Emerald', '#1F5E4E']
];
const FONTS = {
  'Fraunces': { family: 'Fraunces, serif', weight: 300, track: '-0.035em' },
  'Space Grotesk': { family: '"Space Grotesk", sans-serif', weight: 300, track: '-0.06em' },
  'Outfit': { family: 'Outfit, sans-serif', weight: 100, track: '-0.02em' }
};
const STEP_TYPES = {
  app: 'Open app', run: 'Open file or folder', url: 'Open web page',
  keys: 'Keyboard shortcut', text: 'Type text', media: 'Media and volume',
  system: 'System', wait: 'Wait', light: 'Light', spotify: 'Spotify'
};
// Ready-made steps, so shortcuts don't have to be looked up. They can be edited afterwards.
const PRESETS = {
  'Open': {
    'new:app': 'App…', 'new:file': 'File or program…', 'new:folder': 'Folder…', 'new:url': 'Web page…'
  },
  'Browser': {
    newtab: ['New tab', 'ctrl+t'], closetab: ['Close tab', 'ctrl+w'], reopentab: ['Reopen closed tab', 'ctrl+shift+t'],
    nexttab: ['Next tab', 'ctrl+tab'], prevtab: ['Previous tab', 'ctrl+shift+tab'], reload: ['Reload page', 'f5'],
    incognito: ['Private window', 'ctrl+shift+n'], newwindow: ['New window', 'ctrl+n'], address: ['Go to address bar', 'ctrl+l']
  },
  'Windows': {
    alttab: ['Switch window', 'alt+tab'], closewin: ['Close window', 'alt+f4'], desktop: ['Show desktop', 'win+d'],
    maximize: ['Maximize window', 'win+up'], minimize: ['Minimize window', 'win+down'], snipping: ['Screen snip', 'win+shift+s'],
    explorer: ['File Explorer', 'win+e'], settings: ['Windows Settings', 'win+i'], taskmgr: ['Task Manager', 'ctrl+shift+esc'],
    emoji: ['Emoji', 'win+.'], clipboard: ['Clipboard history', 'win+v'], vdright: ['Next desktop', 'ctrl+win+right'], vdleft: ['Previous desktop', 'ctrl+win+left']
  },
  'Editing': {
    copy: ['Copy', 'ctrl+c'], paste: ['Paste', 'ctrl+v'], cut: ['Cut', 'ctrl+x'], undo: ['Undo', 'ctrl+z'],
    redo: ['Redo', 'ctrl+y'], save: ['Save', 'ctrl+s'], selectall: ['Select all', 'ctrl+a'], find: ['Find', 'ctrl+f']
  },
  'Custom step': Object.fromEntries(['keys', 'text', 'media', 'system', 'wait', 'light', 'spotify'].map((k) => ['new:' + k, STEP_TYPES[k]]))
};
const MEDIA = { playpause: 'Play / pause', next: 'Next track', prev: 'Previous track', volup: 'Volume +', voldown: 'Volume −', mute: 'Mute' };
const SYSTEM = { lock: 'Lock PC', sleep: 'Sleep PC', restart: 'Restart', shutdown: 'Shut down PC' };
const LIGHT_CMDS = { toggle: 'Toggle', on: 'Turn on', off: 'Turn off', bright: 'Brightness' };
const SPOTIFY_CMDS = { toggle: 'Play / pause', next: 'Next', prev: 'Previous', uri: 'Play playlist / track' };

let cfg = null;
let base = null; // last state from the server; only the difference to it is saved
let saving = false;
let icons = {};
let status = { tablets: [], pending: [], allowed: [], spotify: {}, lights: [], tiles: {} };
let pageIdx = 0;
let selected = null; // slot index on the current page
let playlists = null;

const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function svg(name) {
  const d = icons[name] || icons.star || '';
  const filled = ['play', 'pause', 'prev', 'next'].includes(name);
  return `<svg viewBox="0 0 24 24" fill="${filled ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${d}"/></svg>`;
}

async function api(method, url, body) {
  const res = await fetch(url, { method, headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined });
  const j = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(j.error || res.statusText);
  return j;
}

let toastTimer;
function toast(text) {
  const t = $('#toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 2600);
}

const clone = (o) => JSON.parse(JSON.stringify(o));

function toMap(c) {
  const m = { ...c, pages: {}, pageOrder: c.pages.map((p) => p.id) };
  for (const p of c.pages) m.pages[p.id] = p;
  return m;
}

// Difference between two configurations: objects are compared deeply, arrays and values as a whole.
function diff(a, b, path = [], ops = []) {
  const isObj = (x) => x && typeof x === 'object' && !Array.isArray(x);
  for (const k of Object.keys(b)) {
    if (path.length === 0 && k === 'devices') continue;
    if (isObj(a[k]) && isObj(b[k])) diff(a[k], b[k], path.concat(k), ops);
    else if (JSON.stringify(a[k]) !== JSON.stringify(b[k])) ops.push({ path: path.concat(k), value: b[k] === undefined ? null : b[k] });
  }
  for (const k of Object.keys(a)) if (!(k in b)) ops.push({ path: path.concat(k), del: true });
  return ops;
}

let saveTimer;
function save() {
  clearTimeout(saveTimer);
  saving = true;
  saveTimer = setTimeout(async () => {
    // The server's answer is not taken over, the open editor holds references to objects in cfg.
    const ops = diff(toMap(base), toMap(cfg));
    try {
      if (ops.length) await api('PATCH', '/api/config', { ops });
      base = clone(cfg);
    } catch (e) { toast(e.message); }
    saving = false;
  }, 300);
}

function newId(prefix) {
  let id;
  do { id = prefix + Math.random().toString(36).slice(2, 7); } while (cfg.tiles[id] || cfg.pages.some((p) => p.id === id));
  return id;
}

// ---------- appearance ----------

const MEDIA_STYLES = [
  ['compact', 'Compact', 'Small volume slider next to the buttons'],
  ['volume', 'With volume', 'Big vertical slider, mute and audio output']
];

function applyTheme() {
  const tint = (TINTS.find(([n]) => n === cfg.theme.tint) || TINTS[9])[1];
  const f = FONTS[cfg.theme.clockFont] || FONTS.Fraunces;
  const r = document.documentElement.style;
  r.setProperty('--tint', tint);
  r.setProperty('--clock-font', f.family);
  r.setProperty('--clock-weight', f.weight);
  r.setProperty('--clock-track', f.track);
}

function renderLook() {
  $('#tints').innerHTML = TINTS.map(([n, c]) => `<button class="swatch ${cfg.theme.tint === n ? 'sel' : ''}" data-tint="${n}"><i style="background:${c}"></i>${n}</button>`).join('');
  $('#fonts').innerHTML = Object.entries(FONTS).map(([n, f]) => `<button class="font ${cfg.theme.clockFont === n ? 'sel' : ''}" data-font="${n}"><span style="font-family:${f.family.replace(/"/g, '&quot;')};font-weight:${f.weight};letter-spacing:${f.track}">21:14</span><small>${n}</small></button>`).join('');
  $('#medias').innerHTML = MEDIA_STYLES.map(([id, name, desc]) => `<button class="media ${cfg.theme.media === id ? 'sel' : ''}" data-media="${id}">
    <div class="mini ${id}"><i class="art"></i><span><b></b><b></b><em></em></span>${id === 'volume' ? '<i class="vbar"><i></i></i>' : ''}</div>
    <strong>${name}</strong><small>${desc}</small></button>`).join('');
  $('#cityNow').textContent = cfg.weather.city;
}

$('#medias').onclick = (e) => {
  const b = e.target.closest('[data-media]');
  if (!b) return;
  cfg.theme.media = b.dataset.media;
  renderLook(); save();
};

$('#tints').onclick = (e) => {
  const b = e.target.closest('[data-tint]');
  if (!b) return;
  cfg.theme.tint = b.dataset.tint;
  applyTheme(); renderLook(); save();
};
$('#fonts').onclick = (e) => {
  const b = e.target.closest('[data-font]');
  if (!b) return;
  cfg.theme.clockFont = b.dataset.font;
  applyTheme(); renderLook(); save();
};
$('#citySearch').onclick = async () => {
  try {
    const list = await api('GET', '/api/geocode?q=' + encodeURIComponent($('#cityInput').value));
    $('#cityResults').innerHTML = list.length ? list.map((c, i) => `<div class="item"><div class="grow">${esc(c.city)}<small>${esc(c.country)}</small></div><button class="btn small" data-city="${i}">Choose</button></div>`).join('') : '<p class="muted">Nothing found.</p>';
    $('#cityResults').onclick = (e) => {
      const b = e.target.closest('[data-city]');
      if (!b) return;
      const c = list[b.dataset.city];
      cfg.weather = { city: c.city, lat: c.lat, lon: c.lon };
      $('#cityResults').innerHTML = '';
      renderLook(); save();
    };
  } catch (e) { toast(e.message); }
};

// ---------- panel preview ----------

function page() { return cfg.pages[pageIdx]; }

function renderTabs() {
  $('#pageTabs').innerHTML = cfg.pages.map((p, i) => `<button class="tab ${i === pageIdx ? 'active' : ''}" data-page="${i}">${esc(p.name)}</button>`).join('')
    + '<button class="tab" id="addPage">+ Page</button>';
  $('#deletePage').hidden = page().layout === 'home';
  $('#pageLayout').hidden = page().layout === 'home';
  $('#pageLayout').value = page().layout;
}

const SLOTS = { grid: 12, big: 6, sliders: 0 };

// Layout change: tiles keep their order, whatever doesn't fit stays saved (it can be switched back).
$('#pageLayout').onchange = (e) => {
  const p = page();
  const n = SLOTS[e.target.value] ?? 12;
  const kept = p.tiles.filter(Boolean);
  if (kept.length > n && !confirm(`Only ${n} tiles fit on this page. The rest will disappear from it (they stay saved). Continue?`)) {
    e.target.value = p.layout;
    return;
  }
  p.layout = e.target.value;
  p.tiles = kept.slice(0, n).concat(Array(Math.max(0, n - kept.length)).fill(null));
  selected = null;
  renderPanel(); save();
};

$('#pageTabs').onclick = (e) => {
  if (e.target.id === 'addPage') {
    cfg.pages.push({ id: newId('p'), name: 'Page ' + cfg.pages.length, layout: 'grid', tiles: Array(12).fill(null) });
    pageIdx = cfg.pages.length - 1;
    selected = null;
    renderPanel(); save();
    return;
  }
  const b = e.target.closest('[data-page]');
  if (!b) return;
  pageIdx = Number(b.dataset.page);
  selected = null;
  renderPanel();
};
$('#renamePage').onclick = () => {
  const name = prompt('Page name', page().name);
  if (name && name.trim()) { page().name = name.trim(); renderTabs(); renderInspector(); save(); }
};
$('#deletePage').onclick = () => {
  if (page().layout === 'home' || !confirm(`Delete page "${page().name}"? The tiles stay saved.`)) return;
  const id = page().id;
  cfg.pages.splice(pageIdx, 1);
  for (const t of Object.values(cfg.tiles)) if (t.kind === 'page' && t.page === id) t.page = null;
  pageIdx = 0; selected = null;
  renderPanel(); save();
};

function slotHtml(i) {
  const id = page().tiles[i];
  const t = id && cfg.tiles[id];
  const sel = selected === i ? ' sel' : '';
  if (!t) return `<div class="slot empty${sel}" data-slot="${i}" draggable="false">+</div>`;
  const on = status.tiles[id] ? ' on' : '';
  const kind = t.kind === 'light' ? 'light' : t.kind === 'page' ? 'page' : t.kind === 'display' ? 'monitor' : t.toggle ? 'toggle' : '';
  return `<div class="slot${on}${sel}" data-slot="${i}" draggable="true">${svg(t.icon)}<span>${esc(t.label)}</span><span class="kind">${kind}</span></div>`;
}

function renderPreview() {
  const p = page();
  let inner;
  if (p.layout === 'home') {
    const quad = (from) => `<div class="quad">${[0, 1, 2, 3].map((k) => slotHtml(from + k)).join('')}</div>`;
    inner = `<div class="layout home">
      <div class="pcard pclock glass"><div class="s">Good afternoon</div><div><div class="t">21:14</div><div class="d">Saturday, October 31</div><div class="w">${esc(cfg.weather.city)} · 8° · Cloudy</div></div><div class="chips"><span class="chip glass">CPU <b>12 %</b></span><span class="chip glass">RAM <b>48 %</b></span></div></div>
      <div class="pcard pspot glass"><div class="art"></div><div class="info"><div class="s">Spotify · on PC</div><div class="title">Track title</div><div class="artist">Artist</div><div class="bar"><i></i></div></div></div>
      ${quad(0)}${quad(4)}</div>`;
  } else if (p.layout === 'sliders') {
    const bars = [['PC', 70], ['Spotify', 55], ['Discord', 80], ['Chrome', 35], ['Game', 60]];
    inner = `<div class="layout sliders">${bars.map(([n, v]) => `<div class="mix glass"><span>${n}</span><i><b style="height:${v}%"></b></i></div>`).join('')}</div>`;
  } else {
    inner = `<div class="layout ${p.layout}">${p.tiles.map((_, i) => slotHtml(i)).join('')}</div>`;
  }
  $('#tablet').innerHTML = `<div class="screen"><div class="blob b1"></div><div class="blob b2"></div><div class="blob b3"></div>${inner}</div>`;
}

let dragFrom = null;
const tabletEl = $('#tablet');
tabletEl.onclick = (e) => {
  const s = e.target.closest('[data-slot]');
  if (!s) return;
  selected = Number(s.dataset.slot);
  renderPreview(); renderInspector();
};
tabletEl.addEventListener('dragstart', (e) => {
  const s = e.target.closest('[data-slot]');
  if (s) { dragFrom = Number(s.dataset.slot); e.dataTransfer.effectAllowed = 'move'; }
});
tabletEl.addEventListener('dragover', (e) => {
  const s = e.target.closest('[data-slot]');
  if (s && dragFrom !== null) { e.preventDefault(); s.classList.add('dragover'); }
});
tabletEl.addEventListener('dragleave', (e) => {
  const s = e.target.closest('[data-slot]');
  if (s) s.classList.remove('dragover');
});
tabletEl.addEventListener('drop', (e) => {
  const s = e.target.closest('[data-slot]');
  if (!s || dragFrom === null) return;
  e.preventDefault();
  const to = Number(s.dataset.slot);
  const t = page().tiles;
  [t[dragFrom], t[to]] = [t[to], t[dragFrom]];
  selected = to; dragFrom = null;
  renderPreview(); renderInspector(); save();
});

// ---------- tile editor ----------

function renderInspector() {
  const box = $('#inspector');
  if (page().layout === 'sliders') {
    box.innerHTML = `<h2>${esc(page().name)}</h2><p class="empty-note">The sliders set themselves up: the first one is the volume of the whole PC, then every app that is currently playing sound (Spotify, Discord, browser, game…). On the tablet you drag them with a finger; tap the icon under a slider to mute that app.</p>`;
    return;
  }
  if (selected === null) {
    box.innerHTML = `<h2>${esc(page().name)}</h2><p class="empty-note">Click a tile in the preview to edit it. Tiles can be rearranged by dragging.</p>`;
    return;
  }
  const id = page().tiles[selected];
  const t = id && cfg.tiles[id];
  if (!t) {
    const others = Object.entries(cfg.tiles);
    box.innerHTML = `<h2>Empty slot</h2><p class="muted">Add a new tile or reuse one you already have.</p>
      <div class="actions"><button class="btn primary" id="newTile">New tile</button></div>
      ${others.length ? `<div class="divider"></div><label class="field">Use existing<select id="useTile"><option value="">Choose…</option>${others.map(([k, o]) => `<option value="${k}">${esc(o.label || k)}</option>`).join('')}</select></label>` : ''}`;
    $('#newTile').onclick = () => {
      const nid = newId('t');
      cfg.tiles[nid] = { label: 'New', icon: 'star', kind: 'macro', toggle: false, steps: [], stepsOff: [] };
      page().tiles[selected] = nid;
      renderPreview(); renderInspector(); save();
    };
    const use = $('#useTile');
    if (use) use.onchange = () => { if (use.value) { page().tiles[selected] = use.value; renderPreview(); renderInspector(); save(); } };
    return;
  }

  const lightOpts = cfg.lights.map((l) => `<option value="${esc(l.id)}" ${t.device === l.id ? 'selected' : ''}>${esc(l.name)}</option>`).join('');
  const pageOpts = cfg.pages.map((p) => `<option value="${esc(p.id)}" ${t.page === p.id ? 'selected' : ''}>${esc(p.name)}</option>`).join('');
  box.innerHTML = `
    <label class="field">Name<input id="tLabel" value="${esc(t.label)}" maxlength="24"></label>
    <div class="field" style="display:block;color:var(--g2);font-size:12px;margin-bottom:6px">Icon</div>
    <div class="icons" id="tIcons">${Object.keys(icons).filter((n) => !['play', 'pause', 'prev', 'next', 'plus'].includes(n)).map((n) => `<button class="${t.icon === n ? 'sel' : ''}" data-icon="${n}" title="${n}">${svg(n)}</button>`).join('')}</div>
    <label class="field">Action<select id="tKind">
      <option value="macro" ${t.kind === 'macro' ? 'selected' : ''}>Macro (steps on the PC)</option>
      <option value="light" ${t.kind === 'light' ? 'selected' : ''}>Light (on / off)</option>
      <option value="page" ${t.kind === 'page' ? 'selected' : ''}>Go to page</option>
      <option value="display" ${t.kind === 'display' ? 'selected' : ''}>Second monitor (PC screen)</option>
    </select></label>
    <div id="kindBox"></div>
    <div class="divider"></div>
    <div class="actions">
      <button class="btn primary" id="tTest">Test</button>
      <button class="btn ghost" id="tRemove">Remove from page</button>
      <button class="btn ghost danger" id="tDelete">Delete</button>
    </div>`;

  const kindBox = $('#kindBox');
  if (t.kind === 'light') {
    kindBox.innerHTML = cfg.lights.length
      ? `<label class="field">Bulb<select id="tDevice"><option value="">Choose…</option>${lightOpts}</select></label>`
      : '<p class="muted">You have no lights yet. Add them in the Lights section.</p>';
    const d = $('#tDevice');
    if (d) d.onchange = () => { t.device = d.value || null; save(); };
  } else if (t.kind === 'page') {
    kindBox.innerHTML = `<label class="field">Page<select id="tPage"><option value="">Choose…</option>${pageOpts}</select></label>`;
    $('#tPage').onchange = (e) => { t.page = e.target.value || null; save(); };
  } else if (t.kind === 'display') {
    kindBox.innerHTML = '<p class="muted">The tablet switches to the PC\'s second monitor. Tap = click, drag = mouse drag, hold = right click, two fingers = scroll. Back to the panel: tap with three fingers.</p>';
  } else {
    kindBox.innerHTML = `<label class="check"><input type="checkbox" id="tToggle" ${t.toggle ? 'checked' : ''}>Toggle (on / off, lights up like a light)</label>
      <div class="field" style="color:var(--g2);font-size:12px;margin-bottom:8px">${t.toggle ? 'Steps when turning on' : 'Steps'}</div><div id="stepsOn"></div>
      ${t.toggle ? '<div class="field" style="color:var(--g2);font-size:12px;margin:14px 0 8px">Steps when turning off (empty = same as turning on)</div><div id="stepsOff"></div>' : ''}`;
    $('#tToggle').onchange = (e) => { t.toggle = e.target.checked; renderPreview(); renderInspector(); save(); };
    // A new tile takes its name and icon from the first thing picked.
    const onName = (name, icon) => {
      if (!name || (t.label && t.label !== 'New')) return;
      t.label = name.slice(0, 24);
      if (t.icon === 'star' && icon) t.icon = icon;
      $('#tLabel').value = t.label;
      box.querySelectorAll('#tIcons button').forEach((b) => b.classList.toggle('sel', b.dataset.icon === t.icon));
      renderPreview(); save();
    };
    renderSteps($('#stepsOn'), t.steps, onName);
    if (t.toggle) renderSteps($('#stepsOff'), t.stepsOff, onName);
  }

  $('#tLabel').oninput = (e) => { t.label = e.target.value; renderPreview(); save(); };
  $('#tIcons').onclick = (e) => {
    const b = e.target.closest('[data-icon]');
    if (!b) return;
    t.icon = b.dataset.icon;
    box.querySelectorAll('#tIcons .sel').forEach((x) => x.classList.remove('sel'));
    b.classList.add('sel');
    renderPreview(); save();
  };
  $('#tKind').onchange = (e) => { t.kind = e.target.value; renderPreview(); renderInspector(); save(); };
  $('#tTest').onclick = async () => {
    if (t.kind === 'page' || t.kind === 'display') return toast('This only works on the tablet');
    try { await api('POST', '/api/run', { tile: id }); toast('Done'); } catch (e) { toast(e.message); }
  };
  $('#tRemove').onclick = () => { page().tiles[selected] = null; renderPreview(); renderInspector(); save(); };
  $('#tDelete').onclick = () => {
    if (!confirm(`Delete tile "${t.label}" from all pages?`)) return;
    delete cfg.tiles[id];
    for (const p of cfg.pages) p.tiles = p.tiles.map((x) => (x === id ? null : x));
    renderPreview(); renderInspector(); save();
  };
}

function opts(map, val) {
  return Object.entries(map).map(([k, v]) => `<option value="${k}" ${val === k ? 'selected' : ''}>${v}</option>`).join('');
}

function stepFields(s) {
  switch (s.type) {
    case 'keys': return `<input data-f="combo" value="${esc(s.combo)}" placeholder="ctrl+shift+m"><button class="btn small" data-rec>Record</button>`;
    case 'text': return `<input data-f="text" value="${esc(s.text)}" placeholder="Text to type">`;
    case 'media': return `<select data-f="key">${opts(MEDIA, s.key)}</select>`;
    case 'app': return `<input data-f="appName" list="appsList" value="${esc(s.appName)}" placeholder="Start typing a name, e.g. Discord">`;
    case 'run': return `<input data-f="path" value="${esc(s.path)}" placeholder="C:\\…\\file or folder" style="flex-basis:100%"><button class="btn small" data-pick="file">Choose file</button><button class="btn small" data-pick="folder">Choose folder</button><input data-f="args" value="${esc(s.args)}" placeholder="Arguments (optional)">`;
    case 'url': return `<input data-f="url" value="${esc(s.url)}" placeholder="youtube.com or steam://open/main"><select data-f="browser" title="Browser and account"><option value="">Default browser</option>${(browserList || []).map((b) => b.profiles.map((p) => {
      const v = `${b.id}|${p.dir}`;
      return `<option value="${esc(v)}" ${s.browser === v ? 'selected' : ''}>${esc(b.name)} – ${esc(p.name)}${p.email ? ` (${esc(p.email)})` : ''}</option>`;
    }).join('')).join('')}</select>`;
    case 'system': return `<select data-f="cmd">${opts(SYSTEM, s.cmd)}</select>`;
    case 'wait': return `<input data-f="ms" type="number" min="0" step="100" value="${esc(s.ms ?? 500)}"><span class="muted" style="flex:0 0 auto;margin:auto 0">ms</span>`;
    case 'light': return `<select data-f="device"><option value="">Bulb…</option>${cfg.lights.map((l) => `<option value="${esc(l.id)}" ${s.device === l.id ? 'selected' : ''}>${esc(l.name)}</option>`).join('')}</select><select data-f="cmd">${opts(LIGHT_CMDS, s.cmd || 'toggle')}</select>${s.cmd === 'bright' ? `<input data-f="value" type="number" min="1" max="100" value="${esc(s.value ?? 50)}">` : ''}`;
    case 'spotify': return `<select data-f="cmd">${opts(SPOTIFY_CMDS, s.cmd || 'toggle')}</select>${s.cmd === 'uri' ? `<input data-f="uri" value="${esc(s.uri)}" placeholder="spotify:playlist:…"><button class="btn small" data-pl>My playlists</button>` : ''}`;
    default: return '';
  }
}

const KEYNAMES = { Escape: 'esc', Enter: 'enter', Space: 'space', Tab: 'tab', Backspace: 'backspace', Delete: 'delete', Insert: 'insert', Home: 'home', End: 'end', PageUp: 'pageup', PageDown: 'pagedown', ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down', PrintScreen: 'printscreen', Minus: '-', Equal: '=', Comma: ',', Period: '.', Slash: '/', Semicolon: ';', Backquote: '`', BracketLeft: '[', BracketRight: ']', Backslash: '\\', Quote: "'" };

function codeToKey(code) {
  if (/^Key[A-Z]$/.test(code)) return code.slice(3).toLowerCase();
  if (/^Digit\d$/.test(code)) return code.slice(5);
  if (/^F\d{1,2}$/.test(code)) return code.toLowerCase();
  return KEYNAMES[code] || null;
}

function addSelect() {
  return `<select data-add><option value="">+ Add step…</option>${Object.entries(PRESETS).map(([group, items]) =>
    `<optgroup label="${group}">${Object.entries(items).map(([k, v]) => `<option value="${k}">${esc(Array.isArray(v) ? v[0] : v)}</option>`).join('')}</optgroup>`).join('')}</select>`;
}

function presetStep(value) {
  if (value.startsWith('new:')) {
    const type = value.slice(4);
    if (type === 'file' || type === 'folder') return { type: 'run', path: '', pickNow: type };
    const s = { type };
    if (type === 'media') s.key = 'playpause';
    if (type === 'system') s.cmd = 'lock';
    if (type === 'wait') s.ms = 500;
    if (type === 'light' || type === 'spotify') s.cmd = 'toggle';
    return s;
  }
  for (const items of Object.values(PRESETS)) {
    const p = items[value];
    if (Array.isArray(p)) return { type: 'keys', combo: p[1], note: p[0] };
  }
  return null;
}

let browserList = null;
async function loadBrowsers() {
  if (!browserList) browserList = await api('GET', '/api/browsers');
  return browserList;
}

let appsList = null;
async function loadApps() {
  if (appsList) return appsList;
  appsList = await api('GET', '/api/apps');
  let dl = document.getElementById('appsList');
  if (!dl) { dl = document.createElement('datalist'); dl.id = 'appsList'; document.body.appendChild(dl); }
  dl.innerHTML = appsList.map((a) => `<option value="${esc(a.name)}"></option>`).join('');
  return appsList;
}

const baseName = (p) => String(p).replace(/[\\/]+$/, '').split(/[\\/]/).pop().replace(/\.(exe|lnk|url|bat|cmd)$/i, '');
const hostName = (u) => { try { return new URL(/^[a-z][a-z0-9+.-]*:/i.test(u) ? u : 'https://' + u).hostname.replace(/^www\./, ''); } catch { return ''; } };

// onName: when you pick an app, file or page, a new tile gets its name and icon from it.
function renderSteps(box, steps, onName = () => {}) {
  box.innerHTML = `<div class="steplist">${steps.map((s, i) => `
    <div class="step" data-i="${i}">
      <div class="top"><b>${i + 1}. ${esc(s.note || STEP_TYPES[s.type] || s.type)}</b>
        <button class="btn small ghost" data-up ${i === 0 ? 'disabled' : ''}>↑</button>
        <button class="btn small ghost" data-del>Delete</button></div>
      <div class="fields">${stepFields(s)}</div>
    </div>`).join('')}</div>${addSelect()}`;

  if (steps.some((s) => s.type === 'app')) loadApps().catch((e) => toast(e.message));
  // The list of browser profiles is loaded once, then the steps are redrawn with it.
  if (!browserList && steps.some((s) => s.type === 'url')) loadBrowsers().then(() => renderSteps(box, steps, onName), (e) => toast(e.message));

  const pickInto = async (i, mode) => {
    try {
      toast(mode === 'folder' ? 'Choose a folder in the Windows dialog…' : 'Choose a file in the Windows dialog…');
      const { path } = await api('POST', '/api/pick', { mode });
      if (!path) return;
      steps[i].path = path;
      renderSteps(box, steps, onName); save();
      onName(baseName(path), mode === 'folder' ? 'folder' : 'terminal');
    } catch (e) { toast(e.message); }
  };

  box.onchange = (e) => {
    if (e.target.matches('[data-add]')) {
      const s = e.target.value && presetStep(e.target.value);
      if (!s) return;
      const pickNow = s.pickNow;
      delete s.pickNow;
      steps.push(s);
      renderSteps(box, steps, onName); save();
      if (s.note) onName(s.note, 'keyboard');
      if (pickNow) pickInto(steps.length - 1, pickNow);
      const last = box.querySelectorAll('.step')[steps.length - 1];
      const first = last && last.querySelector('input');
      if (first && !pickNow) first.focus();
      return;
    }
    const f = e.target.dataset.f;
    if (!f) return;
    const s = steps[Number(e.target.closest('.step').dataset.i)];
    s[f] = e.target.type === 'number' ? Number(e.target.value) : e.target.value;
    if (e.target.tagName === 'SELECT') renderSteps(box, steps, onName);
    if (f === 'url' && s.url) onName(hostName(s.url), 'globe');
    save();
  };
  box.oninput = (e) => {
    const f = e.target.dataset.f;
    if (!f || e.target.tagName === 'SELECT') return;
    const s = steps[Number(e.target.closest('.step').dataset.i)];
    s[f] = e.target.type === 'number' ? Number(e.target.value) : e.target.value;
    if (f === 'appName') {
      const app = (appsList || []).find((a) => a.name.toLowerCase() === s.appName.trim().toLowerCase());
      s.appId = app ? app.id : '';
      if (app) onName(app.name, 'grid');
    }
    save();
  };
  box.onclick = async (e) => {
    const stepEl = e.target.closest('.step');
    if (!stepEl) return;
    const i = Number(stepEl.dataset.i);
    if (e.target.matches('[data-del]')) { steps.splice(i, 1); renderSteps(box, steps, onName); save(); }
    else if (e.target.matches('[data-up]') && i > 0) { [steps[i - 1], steps[i]] = [steps[i], steps[i - 1]]; renderSteps(box, steps, onName); save(); }
    else if (e.target.matches('[data-rec]')) recordCombo(e.target, stepEl.querySelector('[data-f="combo"]'), steps[i]);
    else if (e.target.matches('[data-pl]')) pickPlaylist(stepEl.querySelector('[data-f="uri"]'), steps[i]);
    else if (e.target.matches('[data-pick]')) pickInto(i, e.target.dataset.pick);
  };
}

function recordCombo(btn, input, step) {
  btn.textContent = 'Press a shortcut…';
  input.classList.add('rec');
  const onKey = (e) => {
    e.preventDefault();
    if (['Control', 'Shift', 'Alt', 'Meta'].includes(e.key)) return;
    const key = codeToKey(e.code);
    if (!key) return;
    const parts = [];
    if (e.ctrlKey) parts.push('ctrl');
    if (e.shiftKey) parts.push('shift');
    if (e.altKey) parts.push('alt');
    if (e.metaKey) parts.push('win');
    parts.push(key);
    step.combo = input.value = parts.join('+');
    window.removeEventListener('keydown', onKey, true);
    input.classList.remove('rec');
    btn.textContent = 'Record';
    save();
  };
  window.addEventListener('keydown', onKey, true);
}

async function pickPlaylist(input, step) {
  try {
    if (!playlists) playlists = await api('GET', '/api/spotify/playlists');
    const names = playlists.map((p, i) => `${i + 1}. ${p.name}`).join('\n');
    const n = Number(prompt('Playlist number:\n' + names));
    const p = playlists[n - 1];
    if (p) { step.uri = input.value = p.uri; save(); }
  } catch (e) { toast(e.message); }
}

function renderPanel() {
  renderTabs(); renderPreview(); renderInspector();
}

// ---------- lights, Spotify, tablet ----------

function renderLights() {
  const live = Object.fromEntries(status.lights.map((l) => [l.id, l]));
  $('#lightList').innerHTML = cfg.lights.length ? cfg.lights.map((l, i) => {
    const s = live[l.id];
    const state = !s ? 'unknown state' : !s.online ? 'offline' : s.power ? `on · ${s.bright} %` : 'off';
    return `<div class="item"><div class="grow"><input data-light="${i}" value="${esc(l.name)}" style="width:100%"><small>${esc(l.ip)} · ${esc(l.model)} · ${state}</small></div><button class="btn small" data-ltoggle="${i}">Toggle</button><button class="btn small ghost danger" data-ldel="${i}">Remove</button></div>`;
  }).join('') : '<p class="muted">No lights yet.</p>';
}

$('#lightList').oninput = (e) => {
  const i = e.target.dataset.light;
  if (i !== undefined) { cfg.lights[i].name = e.target.value; save(); }
};
$('#lightList').onclick = async (e) => {
  const t = e.target.dataset.ltoggle;
  const d = e.target.dataset.ldel;
  if (t !== undefined) {
    try { await api('POST', '/api/run', { steps: [{ type: 'light', device: cfg.lights[t].id, cmd: 'toggle' }] }); } catch (err) { toast(err.message); }
  } else if (d !== undefined) {
    cfg.lights.splice(d, 1); renderLights(); save();
  }
};
$('#discover').onclick = async () => {
  $('#discover').textContent = 'Searching…';
  try {
    const r = await api('POST', '/api/lights/discover');
    cfg.lights = r.lights;
    toast(r.found ? `Found ${r.found} bulbs` : 'No bulbs found. Is LAN Control turned on?');
    renderLights();
  } catch (e) { toast(e.message); }
  $('#discover').textContent = 'Search for bulbs';
};

function renderSpotify() {
  const s = status.spotify || {};
  $('#redirectUri').textContent = s.redirectUri || 'http://127.0.0.1:47801/spotify/callback';
  if (document.activeElement !== $('#clientId')) $('#clientId').value = cfg.spotify.clientId || '';
  $('#spotifyState').textContent = s.connected ? 'Spotify is connected.' + (s.error ? ' ' + s.error : '') : s.configured ? 'Client ID is saved, now log in.' : 'Spotify is not set up yet. Without it only play, next and previous work (as media keys).';
  $('#spotifyLogin').hidden = !s.configured;
  $('#spotifyLogout').hidden = !s.connected;
}
$('#saveClient').onclick = async () => {
  try {
    cfg.spotify.clientId = $('#clientId').value.trim();
    await api('POST', '/api/spotify/client', { clientId: cfg.spotify.clientId });
    cfg = await api('GET', '/api/config');
    base = clone(cfg);
    selected = null; renderPanel();
    toast('Saved');
  } catch (e) { toast(e.message); }
};
$('#spotifyLogout').onclick = () => api('POST', '/api/spotify/logout').catch((e) => toast(e.message));

function renderTablet() {
  $('#pendingList').innerHTML = status.pending.length ? status.pending.map((p) => `<div class="item"><div class="grow">${esc(p.name)}<small>${esc(p.ip)}</small></div><button class="btn small primary" data-approve="${esc(p.deviceId)}">Allow</button></div>`).join('') : '<p class="muted">Nothing is waiting. When you open the app on the tablet, it shows up here.</p>';
  const online = new Set(status.tablets.map((t) => t.deviceId));
  $('#allowedList').innerHTML = status.allowed.length ? status.allowed.map((d) => `<div class="item"><div class="grow">${esc(d.name)}<small>${online.has(d.deviceId) ? 'connected' : 'offline'}</small></div><button class="btn small ghost danger" data-remove="${esc(d.deviceId)}">Remove</button></div>`).join('') : '<p class="muted">None yet.</p>';
  $('#pendingBadge').hidden = status.pending.length === 0;
  $('#pendingBadge').textContent = status.pending.length;
  const conn = status.tablets.length > 0;
  $('#conn').classList.toggle('on', conn);
  $('#connText').textContent = conn ? status.tablets[0].name + ' connected' : status.pending.length ? 'Tablet waiting for approval' : 'Tablet not connected';
}
async function renderLog() {
  try {
    const l = await api('GET', '/api/log');
    const up = l.uptime < 3600 ? Math.round(l.uptime / 60) + ' min' : (l.uptime / 3600).toFixed(1) + ' h';
    $('#serverState').textContent = `Running for ${up}. ` + (l.supervised ? 'It restarts by itself after a crash.' : 'Started without the supervisor – it will not restart after a crash (start it with the Lumen Desk icon).');
    $('#serverLog').textContent = l.lines.length ? l.lines.join('\n') : 'The log is empty.';
    $('#serverLog').scrollTop = 1e9;
  } catch (e) { toast(e.message); }
}
$('#reloadLog').onclick = renderLog;

async function renderNotify() {
  $('#notifyOn').checked = cfg.notify.enabled;
  try {
    const apps = await api('GET', '/api/notify/apps');
    $('#notifyApps').innerHTML = apps.length
      ? apps.map((a) => `<label class="check"><input type="checkbox" data-napp="${esc(a.id)}" ${cfg.notify.muted.includes(a.id) ? '' : 'checked'} ${cfg.notify.enabled ? '' : 'disabled'}>${esc(a.name)}</label>`).join('')
      : '<p class="muted">No app has sent a notification yet.</p>';
  } catch (e) {
    $('#notifyApps').innerHTML = `<p class="muted">${esc(e.message)}</p>`;
  }
}
$('#notifyOn').onchange = (e) => { cfg.notify.enabled = e.target.checked; save(); renderNotify(); };
$('#notifyApps').onchange = (e) => {
  const id = e.target.dataset.napp;
  if (!id) return;
  cfg.notify.muted = cfg.notify.muted.filter((x) => x !== id);
  if (!e.target.checked) cfg.notify.muted.push(id);
  save();
};

$('#view-tablet').onclick = async (e) => {
  const a = e.target.dataset.approve;
  const r = e.target.dataset.remove;
  try {
    if (a) { await api('POST', '/api/devices/approve', { deviceId: a }); toast('Tablet allowed'); }
    if (r && confirm('Remove this tablet? Next time it connects you will have to allow it again.')) await api('POST', '/api/devices/remove', { deviceId: r });
    cfg = await api('GET', '/api/config');
    base = clone(cfg);
    selected = null; renderPanel();
  } catch (err) { toast(err.message); }
};

// ---------- navigation and connection ----------

function showView() {
  const v = (location.hash || '#panel').slice(1);
  document.querySelectorAll('.view').forEach((s) => { s.hidden = s.id !== 'view-' + v; });
  document.querySelectorAll('#nav a').forEach((a) => a.classList.toggle('active', a.dataset.view === v));
  if (v === 'look') renderLook();
  if (v === 'lights') renderLights();
  if (v === 'spotify') renderSpotify();
  if (v === 'tablet') { renderTablet(); renderLog(); renderNotify(); }
}
window.addEventListener('hashchange', showView);

function connect() {
  const ws = new WebSocket(`ws://${location.host}/ws?role=panel`);
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    // Settings changed elsewhere (another window, tablet, server): take them over unless we are saving our own.
    if (m.t === 'config') {
      if (saving) return;
      base = clone(m.config);
      if (JSON.stringify(m.config) === JSON.stringify(cfg)) return;
      cfg = m.config;
      if (pageIdx >= cfg.pages.length) pageIdx = 0;
      applyTheme();
      renderPanel();
      showView();
      return;
    }
    if (m.t !== 'status') return;
    const tilesChanged = JSON.stringify(m.tiles) !== JSON.stringify(status.tiles);
    status = m;
    renderTablet();
    if (!$('#view-spotify').hidden) renderSpotify();
    if (!$('#view-lights').hidden) renderLights();
    if (tilesChanged && !$('#view-panel').hidden) renderPreview();
  };
  ws.onclose = () => setTimeout(connect, 1500);
}

(async function start() {
  [cfg, icons] = await Promise.all([api('GET', '/api/config'), api('GET', '/api/icons')]);
  base = clone(cfg);
  applyTheme();
  renderPanel();
  showView();
  connect();
  const err = new URLSearchParams(location.search).get('spotifyError');
  if (err) { toast('Spotify: ' + err); history.replaceState(null, '', location.pathname + location.hash); }
})();
