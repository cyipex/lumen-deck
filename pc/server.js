// Lumen Desk – PC server. The tablet connects over WebSocket, settings live at http://127.0.0.1:47801
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const dgram = require('dgram');
const { WebSocketServer } = require('ws');

const config = require('./lib/config');
const input = require('./lib/input');
const yeelight = require('./lib/yeelight');
const spotify = require('./lib/spotify');
const info = require('./lib/info');
const actions = require('./lib/actions');
const picker = require('./lib/picker');
const audio = require('./lib/audio');
const display = require('./lib/display');
const browsers = require('./lib/browsers');
const usb = require('./lib/usb');
const notify = require('./lib/notify');

// App names for notifications (same IDs as in the Start menu).
let startApps = [];
picker.apps().then((l) => { startApps = l; }, () => {});

// Unexpected error: log it and exit with code 1, the supervisor (run.js) starts the server again.
process.on('uncaughtException', (e) => {
  console.error('Unexpected error:', e && e.stack ? e.stack : e);
  process.exit(1);
});
process.on('unhandledRejection', (e) => {
  console.error('Unhandled error:', e && e.stack ? e.stack : e);
});

// Name shown on the tablet; LUMEN_PC_NAME overrides the Windows computer name.
const PC_NAME = process.env.LUMEN_PC_NAME || os.hostname();
const PORT = 47801;
const BEACON_PORT = 47802;
const UI_DIR = path.join(__dirname, 'ui');
const ICONS_FILE = path.join(__dirname, '..', 'shared', 'icons.json');

config.load();
spotify.init({ dataDir: config.DATA_DIR, redirectUri: `http://127.0.0.1:${PORT}/spotify/callback` });
spotify.setClientId(config.get().spotify.clientId);
info.setWeatherConfig(config.get().weather);
info.start();
input.warmUp();
audio.warmUp();
yeelight.track(config.get().lights);

// ---------- tile state ----------

const macroOn = {}; // tileId -> bool for toggle macros

function tileStates() {
  const c = config.get();
  const lights = Object.fromEntries(yeelight.states().map((l) => [l.id, l]));
  const out = {};
  for (const [id, t] of Object.entries(c.tiles)) {
    if (t.kind === 'light') out[id] = !!(t.device && lights[t.device] && lights[t.device].power);
    else if (t.toggle) out[id] = !!macroOn[id];
  }
  return out;
}

async function tapTile(id) {
  const c = config.get();
  const t = c.tiles[id];
  if (!t) throw new Error('Tile does not exist');
  const ctx = { lights: c.lights };
  if (t.kind === 'light') {
    const dev = c.lights.find((l) => l.id === t.device);
    if (!dev) throw new Error(`"${t.label}" has no bulb selected. Set it up on the PC.`);
    yeelight.command(dev, 'toggle');
  } else if (t.kind === 'macro') {
    if (t.toggle) {
      macroOn[id] = !macroOn[id];
      broadcastState();
      const steps = !macroOn[id] && t.stepsOff.length ? t.stepsOff : t.steps;
      await actions.runSteps(steps, ctx);
    } else {
      await actions.runSteps(t.steps, ctx);
    }
  }
  broadcastState();
}

// ---------- WebSocket: tablets and settings windows ----------

const tablets = new Set(); // ws with .device
const panels = new Set(); // settings windows
const pending = new Map(); // deviceId -> { deviceId, name, ws }

function send(ws, msg) {
  if (ws.readyState === 1) ws.send(JSON.stringify(msg));
}

function stateMsg() {
  return {
    t: 'state',
    spotify: spotify.current(),
    audio: audio.current(),
    mixer: audio.apps(),
    stats: info.stats(),
    weather: info.weather(),
    tiles: tileStates(),
    pc: PC_NAME
  };
}

function broadcastState() {
  const msg = stateMsg();
  for (const ws of tablets) send(ws, msg);
}

function broadcastConfig() {
  const msg = { t: 'config', config: config.forTablet() };
  for (const ws of tablets) send(ws, msg);
}

function panelStatus() {
  return {
    t: 'status',
    tablets: [...tablets].map((ws) => ({ deviceId: ws.device.deviceId, name: ws.device.name, ip: ws.usb ? 'USB cable' : ws.device.ip })),
    pending: [...pending.values()].map(({ deviceId, name, ip }) => ({ deviceId, name, ip })),
    allowed: config.get().devices.allowed,
    spotify: spotify.status(),
    lights: yeelight.states(),
    tiles: tileStates()
  };
}

function broadcastPanels() {
  const msg = panelStatus();
  for (const ws of panels) send(ws, msg);
}

function updateSpotifyPolling() {
  spotify.setActive(tablets.size > 0 || panels.size > 0);
  audio.setActive(tablets.size > 0);
  // Notifications are watched only while a tablet is connected.
  if (tablets.size > 0) notify.start(() => config.get().notify, () => startApps);
  else notify.stop();
}

function acceptTablet(ws) {
  pending.delete(ws.device.deviceId);
  tablets.add(ws);
  send(ws, { t: 'pair', status: 'ok' });
  send(ws, { t: 'config', config: config.forTablet() });
  send(ws, stateMsg());
  updateSpotifyPolling();
  broadcastPanels();
}

function onTabletMessage(ws, msg) {
  if (msg.t === 'hello') {
    if (fs.existsSync(path.join(config.DATA_DIR, 'pinglog'))) console.log('hello', ws.ip, String(msg.deviceId).slice(0, 8));
    ws.device = { deviceId: String(msg.deviceId || ''), name: String(msg.name || 'Tablet'), ip: ws.ip };
    if (!ws.device.deviceId) return ws.close();
    // The same tablet connected again (app restart, Wi-Fi <-> USB switch): drop the old connection.
    for (const other of tablets) if (other !== ws && other.device.deviceId === ws.device.deviceId) { tablets.delete(other); other.terminate(); }
    if (config.get().devices.allowed.some((d) => d.deviceId === ws.device.deviceId)) acceptTablet(ws);
    else {
      pending.set(ws.device.deviceId, { ...ws.device, ws });
      send(ws, { t: 'pair', status: 'pending', pc: PC_NAME });
      broadcastPanels();
    }
    return;
  }
  if (!tablets.has(ws)) return;
  const fail = (e) => send(ws, { t: 'toast', text: e.message });
  if (msg.t === 'tap') tapTile(String(msg.tile)).catch(fail);
  else if (msg.t === 'spotify') {
    spotify.control(String(msg.cmd), msg.value).then((ok) => {
      if (!ok && ['toggle', 'next', 'prev'].includes(msg.cmd)) input.media({ toggle: 'playpause', next: 'next', prev: 'prev' }[msg.cmd]);
    }).catch(fail);
  } else if (msg.t === 'audio') {
    const v = msg.value;
    const job = {
      volume: () => audio.setVolume(v),
      mute: () => audio.toggleMute(),
      output: () => audio.nextDevice(),
      appvol: () => audio.setAppVolume(msg.key, v),
      appmute: () => audio.setAppMuted(msg.key, !!v)
    }[msg.cmd];
    if (job) Promise.resolve().then(job).catch(fail);
  } else if (msg.t === 'lib') {
    // Spotify library: the tablet asks, the PC answers with the same request number.
    const reply = (body) => send(ws, { t: 'lib', req: msg.req, ...body });
    const job = { sources: () => spotify.sources(), tracks: () => spotify.tracks(msg.source, msg.offset), search: () => spotify.search(msg.q) }[msg.op];
    if (!job) return;
    job().then((data) => reply({ data }), (e) => reply({ error: e.message }));
  } else if (msg.t === 'libplay') {
    spotify.playItem(msg).catch(fail);
  } else if (msg.t === 'set') {
    // The tablet may only change the look and notifications, never macros or devices.
    const allowed = ['theme.tint', 'theme.clockFont', 'theme.media', 'notify.enabled'];
    const ops = (Array.isArray(msg.ops) ? msg.ops : []).filter((o) => Array.isArray(o.path) && allowed.includes(o.path.join('.')) && !o.del);
    if (ops.length) config.patch(ops.map((o) => ({ path: o.path, value: o.value })));
  } else if (msg.t === 'ptr') display.pointer(msg);
  else if (msg.t === 'ping') send(ws, { t: 'pong', at: msg.at });
}

// ---------- HTTP: settings and API ----------

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.png': 'image/png' };

function json(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let s = '';
    req.on('data', (d) => { s += d; if (s.length > 2e6) req.destroy(); });
    req.on('end', () => { try { resolve(s ? JSON.parse(s) : {}); } catch (e) { reject(new Error('Invalid JSON')); } });
  });
}

// Settings may only be changed by programs on this PC, not by devices on the network.
function isLocal(req) {
  const a = req.socket.remoteAddress || '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

const routes = {
  'GET /api/config': () => config.get(),
  'PUT /api/config': async (req) => {
    const next = await readBody(req);
    // The list of allowed tablets is changed only by pairing, not by the editor.
    next.devices = config.get().devices;
    return config.set(next);
  },
  'PATCH /api/config': async (req) => {
    const { ops } = await readBody(req);
    config.patch(ops);
    return { ok: true };
  },
  'GET /api/log': () => {
    const file = path.join(config.DATA_DIR, 'logs', 'server.log');
    const lines = fs.existsSync(file) ? fs.readFileSync(file, 'utf8').split('\n').filter(Boolean) : [];
    return { lines: lines.slice(-80), supervised: !!process.env.LC_SUPERVISED, uptime: Math.round(process.uptime()) };
  },
  'GET /api/notify/apps': () => notify.knownApps(),
  'GET /api/icons': () => JSON.parse(fs.readFileSync(ICONS_FILE, 'utf8')),
  'GET /api/status': () => panelStatus(),
  'POST /api/run': async (req) => {
    const body = await readBody(req);
    if (body.tile) await tapTile(body.tile);
    else await actions.runSteps(body.steps || [], { lights: config.get().lights });
    return { ok: true };
  },
  'POST /api/lights/discover': async () => {
    const found = await yeelight.discover();
    config.update((c) => {
      for (const f of found) {
        const old = c.lights.find((l) => l.id === f.id);
        if (old) Object.assign(old, { ip: f.ip, model: f.model });
        else c.lights.push({ id: f.id, ip: f.ip, model: f.model, name: f.name });
      }
    });
    yeelight.track(config.get().lights);
    return { found: found.length, lights: config.get().lights };
  },
  'POST /api/devices/approve': async (req) => {
    const { deviceId } = await readBody(req);
    const p = pending.get(deviceId);
    if (!p) throw new Error('The tablet is no longer waiting for approval');
    config.update((c) => { c.devices.allowed.push({ deviceId, name: p.name }); });
    acceptTablet(p.ws);
    return { ok: true };
  },
  'POST /api/devices/remove': async (req) => {
    const { deviceId } = await readBody(req);
    config.update((c) => { c.devices.allowed = c.devices.allowed.filter((d) => d.deviceId !== deviceId); });
    for (const ws of tablets) if (ws.device.deviceId === deviceId) ws.close();
    pending.delete(deviceId);
    broadcastPanels();
    return { ok: true };
  },
  'POST /api/spotify/client': async (req) => {
    const { clientId } = await readBody(req);
    config.update((c) => { c.spotify.clientId = String(clientId || '').trim(); });
    return spotify.status();
  },
  'POST /api/spotify/logout': () => { spotify.logout(); return spotify.status(); },
  'GET /api/spotify/playlists': () => spotify.playlists(),
  'GET /api/geocode': (req, url) => info.geocode(url.searchParams.get('q') || ''),
  'POST /api/pick': async (req) => {
    const { mode } = await readBody(req);
    return { path: await picker.pick(mode === 'folder' ? 'folder' : 'file') };
  },
  'GET /api/apps': () => picker.apps(),
  'GET /api/browsers': () => browsers.list()
};

async function handle(req, res) {
  const url = new URL(req.url, 'http://x');

  // The tablet uses this to check whether it can reach the PC over the USB cable (adb reverse).
  if (url.pathname === '/ping') {
    if (process.env.LC_PINGLOG || fs.existsSync(path.join(config.DATA_DIR, 'pinglog'))) console.log('ping', req.socket.remoteAddress);
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    return res.end('lumen-desk');
  }

  if (url.pathname === '/spotify/login') {
    if (!isLocal(req)) return json(res, 403, { error: 'Only from this PC' });
    try {
      res.writeHead(302, { Location: spotify.loginUrl() });
    } catch (e) {
      res.writeHead(302, { Location: '/?spotifyError=' + encodeURIComponent(e.message) });
    }
    return res.end();
  }
  if (url.pathname === '/spotify/callback') {
    try {
      if (url.searchParams.get('error')) throw new Error(url.searchParams.get('error'));
      await spotify.finishLogin(url.searchParams.get('code'), url.searchParams.get('state'));
      res.writeHead(302, { Location: '/#spotify' });
    } catch (e) {
      res.writeHead(302, { Location: '/?spotifyError=' + encodeURIComponent(e.message) + '#spotify' });
    }
    return res.end();
  }

  if (url.pathname.startsWith('/api/')) {
    if (!isLocal(req)) return json(res, 403, { error: 'Settings can only be changed from this PC' });
    const route = routes[`${req.method} ${url.pathname}`];
    if (!route) return json(res, 404, { error: 'Not found' });
    try {
      return json(res, 200, await route(req, url));
    } catch (e) {
      return json(res, 400, { error: e.message });
    }
  }

  if (!isLocal(req)) {
    res.writeHead(403);
    return res.end();
  }
  const rel = url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname.slice(1));
  const file = path.join(UI_DIR, rel);
  if (!file.startsWith(UI_DIR + path.sep)) {
    res.writeHead(403);
    return res.end();
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404);
      return res.end('Not found');
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(data);
  });
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((e) => json(res, 500, { error: e.message }));
});

const wss = new WebSocketServer({ server, path: '/ws' });
wss.on('connection', (ws, req) => {
  ws.ip = (req.socket.remoteAddress || '').replace('::ffff:', '');
  // Over adb reverse the tablet comes from 127.0.0.1 (USB cable).
  ws.usb = ws.ip === '127.0.0.1' || ws.ip === '::1';
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  const role = new URL(req.url, 'http://x').searchParams.get('role');

  if (role === 'panel') {
    if (!isLocal(req)) return ws.close();
    panels.add(ws);
    send(ws, panelStatus());
    updateSpotifyPolling();
    ws.on('close', () => { panels.delete(ws); updateSpotifyPolling(); });
    return;
  }

  // Video for the tablet (second monitor): a separate connection, so video never slows down the controls.
  if (role === 'display') {
    const device = new URL(req.url, 'http://x').searchParams.get('device');
    if (!config.get().devices.allowed.some((d) => d.deviceId === device)) return ws.close();
    display.open(ws);
    return;
  }

  ws.on('message', (data) => {
    let msg;
    try { msg = JSON.parse(data); } catch { return; }
    onTabletMessage(ws, msg);
  });
  ws.on('close', (code) => {
    if (ws.device && fs.existsSync(path.join(config.DATA_DIR, 'pinglog'))) console.log('close', ws.ip, code);
    tablets.delete(ws);
    if (ws.device) {
      const p = pending.get(ws.device.deviceId);
      if (p && p.ws === ws) pending.delete(ws.device.deviceId);
    }
    updateSpotifyPolling();
    broadcastPanels();
  });
});

// Dead connections (tablet asleep, Wi-Fi off) are dropped within 20 s.
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 10000);

// ---------- events -> tablets ----------

config.onChange((c) => {
  spotify.setClientId(c.spotify.clientId);
  info.setWeatherConfig(c.weather);
  broadcastConfig();
  broadcastState();
  broadcastPanels();
  // Open settings windows get the current state, so their next save does not overwrite changes made elsewhere.
  const msg = { t: 'config', config: c };
  for (const ws of panels) send(ws, msg);
});
yeelight.events.on('state', () => { broadcastState(); broadcastPanels(); });
spotify.events.on('playback', () => broadcastState());
spotify.events.on('status', () => broadcastPanels());
audio.events.on('change', () => broadcastState());
audio.events.on('apps', () => broadcastState());
notify.events.on('notify', (n) => {
  const msg = { t: 'notify', id: n.id, app: n.app, title: n.title, body: n.body };
  for (const ws of tablets) send(ws, msg);
});

// The mixer (per-app volume) is read only while the tablet has a sliders page.
const updateMixer = (c) => audio.setMixer(c.pages.some((p) => p.layout === 'sliders'));
updateMixer(config.get());
config.onChange(updateMixer);
info.events.on('weather', () => broadcastState());
info.events.on('stats', (s) => {
  const msg = { t: 'stats', stats: s };
  for (const ws of tablets) send(ws, msg);
});

// ---------- network announcement, so the tablet finds the PC by itself ----------

function broadcastAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      const ip = a.address.split('.').map(Number);
      const mask = a.netmask.split('.').map(Number);
      out.push(ip.map((b, i) => (b | (~mask[i] & 255))).join('.'));
    }
  }
  return out;
}

const beacon = dgram.createSocket('udp4');
beacon.bind(() => {
  beacon.setBroadcast(true);
  const msg = Buffer.from(JSON.stringify({ app: 'lumen-desk', port: PORT, name: PC_NAME }));
  setInterval(() => {
    for (const addr of broadcastAddresses()) beacon.send(msg, BEACON_PORT, addr, () => {});
  }, 2000);
});

// A second start (e.g. from the desktop icon) just exits, the server is already running.
server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') process.exit(0);
  throw e;
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Lumen Desk running: http://127.0.0.1:${PORT}`);
  usb.start(PORT);
});
