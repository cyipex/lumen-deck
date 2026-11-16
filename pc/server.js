// Lumen Desk – PC server. The tablet connects over an encrypted WebSocket on port 47801 (all networks),
// the settings live at http://127.0.0.1:47803 (only this PC).
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
const tools = require('./lib/tools');
const notify = require('./lib/notify');
const secure = require('./lib/secure');
const claude = require('./lib/claude');
const netinfo = require('./lib/netinfo');
const assets = require('./lib/assets');

// App names for notifications (same IDs as in the Start menu).
let startApps = [];
picker.apps().then((l) => { startApps = l; }, () => {});

// Unexpected error: log it and exit with code 1, the supervisor (run.js) starts the server again.
process.on('uncaughtException', (e) => {
  // Port already taken = Lumen Desk already runs: quietly stop (exit code 0 stops the supervisor too).
  if (e && e.code === 'EADDRINUSE') {
    console.log(`Port ${e.port} is already in use (another Lumen Desk, or another program). This copy stops.`);
    process.exit(0);
  }
  console.error('Unexpected error:', e && e.stack ? e.stack : e);
  process.exit(1);
});
process.on('unhandledRejection', (e) => {
  console.error('Unhandled error:', e && e.stack ? e.stack : e);
});

// Name shown on the tablet; LUMEN_PC_NAME overrides the Windows computer name.
const PC_NAME = process.env.LUMEN_PC_NAME || os.hostname();
// LUMEN_PORT / LUMEN_SETTINGS_PORT / LUMEN_QUIET (no announcement, no USB) let a test copy run next to the real one.
const PORT = Number(process.env.LUMEN_PORT) || 47801;
const BEACON_PORT = 47802;
const SETTINGS_PORT = Number(process.env.LUMEN_SETTINGS_PORT) || 47803;
const QUIET = !!process.env.LUMEN_QUIET;
const paths = require('./lib/paths');
const UI_DIR = paths.UI;
const ICONS_FILE = paths.ICONS;

config.load();
spotify.init({ dataDir: config.DATA_DIR, redirectUri: `http://127.0.0.1:${PORT}/spotify/callback` });
spotify.setClientId(config.get().spotify.clientId);
info.setWeatherConfig(config.get().weather);
info.start();
claude.start();
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
const pending = new Map(); // deviceId -> { deviceId, name, ip, code, ws }
const MAX_PENDING = 4;

// Tablets get every message encrypted (lib/secure.js), settings windows plain JSON (they are on this PC).
function send(ws, msg) {
  if (ws.readyState !== 1) return;
  if (ws.session) ws.send(ws.session.seal(msg), { binary: true });
  else if (ws.panel) ws.send(JSON.stringify(msg));
}

// The pairing key of an allowed tablet (made when it was allowed), or null.
function keyFor(deviceId) {
  const d = config.get().devices.allowed.find((x) => x.deviceId === deviceId && x.key);
  return d ? Buffer.from(d.key, 'base64') : null;
}

// Allowed tablets without their keys: what the settings window may see.
const allowedList = () => config.get().devices.allowed.map(({ deviceId, name }) => ({ deviceId, name }));
const forPanel = (c) => ({ ...c, devices: { ...c.devices, allowed: c.devices.allowed.map(({ deviceId, name }) => ({ deviceId, name })) } });

function stateMsg() {
  return {
    t: 'state',
    spotify: spotify.current(),
    audio: audio.current(),
    mixer: audio.apps(),
    stats: info.stats(),
    weather: info.weather(),
    tiles: tileStates(),
    claude: claude.current(),
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
    pending: [...pending.values()].map(({ deviceId, name, ip, code }) => ({ deviceId, name, ip, code })),
    allowed: allowedList(),
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

// extra: the new pairing key, sent once when the tablet is allowed.
// Connections in the log (server.log), so "the tablet does not connect" can be traced; repeats are not logged again.
let lastLog = '';
function logTablet(what, ws) {
  const line = `Tablet ${ws.device ? '"' + ws.device.name + '" ' : ''}${what} (${ws.usb ? 'USB cable' : ws.ip})`;
  if (line !== lastLog) console.log(line);
  lastLog = line;
}

function acceptTablet(ws, extra = {}) {
  // The same tablet connected again (app restart, Wi-Fi <-> USB switch): drop the old connection.
  for (const other of tablets) if (other !== ws && other.device.deviceId === ws.device.deviceId) { tablets.delete(other); other.terminate(); }
  pending.delete(ws.device.deviceId);
  tablets.add(ws);
  logTablet('connected', ws);
  send(ws, { t: 'pair', status: 'ok', ...extra });
  send(ws, { t: 'config', config: config.forTablet() });
  send(ws, stateMsg());
  updateSpotifyPolling();
  broadcastPanels();
}

// The first message of a connection: the key exchange (the only one sent unencrypted).
function onHello(ws, msg) {
  let hs;
  try {
    hs = secure.accept(msg, keyFor, config.get().pcId);
  } catch {
    return ws.close(4400, 'bad handshake');
  }
  clearTimeout(ws.helloTimer);
  if (fs.existsSync(path.join(config.DATA_DIR, 'pinglog'))) console.log('hello', ws.ip, String(msg.deviceId).slice(0, 8), hs.mode);
  ws.device = { deviceId: String(msg.deviceId), name: String(msg.name || 'Tablet').slice(0, 60), ip: ws.ip };
  ws.mode = hs.mode;
  ws.send(JSON.stringify({ ...hs.reply, pc: PC_NAME }));
  ws.session = hs.session;
  if (hs.mode === 'pair') {
    // A tablet the PC does not know: it waits until the user compares the code and presses Allow.
    // Only a few can wait at once, so nobody can flood the settings window.
    if (!pending.has(ws.device.deviceId) && pending.size >= MAX_PENDING) {
      const [oldest] = pending.values();
      pending.delete(oldest.deviceId);
      oldest.ws.close();
    }
    pending.set(ws.device.deviceId, { ...ws.device, code: hs.code, ws });
    logTablet('is waiting for approval', ws);
    send(ws, { t: 'pair', status: 'pending', pc: PC_NAME, code: hs.code });
    broadcastPanels();
  }
  // A paired tablet is let in only after its first encrypted message proves it has the pairing key ("ready").
}

function onTabletMessage(ws, msg) {
  if (msg.t === 'ready') {
    if (ws.mode === 'paired' && !tablets.has(ws)) acceptTablet(ws);
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
    const ops = (Array.isArray(msg.ops) ? msg.ops : []).filter((o) => o && Array.isArray(o.path) && allowed.includes(o.path.join('.')) && !o.del &&
      (typeof o.value === 'boolean' || (typeof o.value === 'string' && o.value.length <= 40)));
    if (ops.length) config.patch(ops.map((o) => ({ path: o.path, value: o.value })));
  } else if (msg.t === 'ptr') display.pointer(msg);
  // Touchpad and keyboard screen on the tablet.
  else if (msg.t === 'pad') input.pad(msg);
  else if (msg.t === 'key') { try { input.key(msg); } catch (e) { fail(e); } }
  else if (msg.t === 'type') input.type(String(msg.text || '').slice(0, 2000));
  else if (msg.t === 'hold') input.hold(String(msg.k), !!msg.down);
  // Drawing tablet.
  else if (msg.t === 'drawscreen') display.drawScreen(msg.i).then((s) => send(ws, { t: 'drawscreen', ...s })).catch(fail);
  else if (msg.t === 'draw') display.draw(msg);
  // Second monitor: a one-time ticket (30 s) and its own key for the separate, encrypted video connection.
  // A photo or font for the screensaver/clock, in pieces (each an encrypted message of its own).
  else if (msg.t === 'asset') {
    const a = assets.find(msg.id);
    if (!a) return send(ws, { t: 'asset', req: msg.req, id: msg.id, error: 'missing' });
    const CHUNK = 192 * 1024;
    const n = Math.max(1, Math.ceil(a.buf.length / CHUNK));
    for (let i = 0; i < n; i++) {
      send(ws, { t: 'asset', req: msg.req, id: msg.id, ext: a.ext, i, n, data: a.buf.subarray(i * CHUNK, (i + 1) * CHUNK).toString('base64') });
    }
  } else if (msg.t === 'display') {
    const now = Date.now();
    for (const [k, v] of displayTickets) if (v.exp < now) displayTickets.delete(k);
    const token = secure.token();
    const key = secure.newKey();
    displayTickets.set(token, { key, exp: now + 30000 });
    send(ws, { t: 'display', req: msg.req, token, key: key.toString('base64') });
  } else if (msg.t === 'ping') send(ws, { t: 'pong', at: msg.at });
}

const displayTickets = new Map(); // token -> { key, exp }

// ---------- HTTP: settings and API ----------

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.png': 'image/png' };

function json(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

// A file upload: the raw bytes, at most max (the settings window sends the file as it is).
function readRaw(req, max) {
  return new Promise((resolve, reject) => {
    const parts = [];
    let size = 0;
    req.on('data', (d) => {
      size += d.length;
      if (size > max) { reject(new Error(`The file is too big (at most ${Math.round(max / 1024 / 1024)} MB).`)); req.destroy(); return; }
      parts.push(d);
    });
    req.on('end', () => resolve(Buffer.concat(parts)));
    req.on('error', reject);
  });
}

// Uploaded files nothing points to any more are removed.
function pruneAssets(c) {
  const used = new Set([c.saver.photo, ...c.fonts.map((f) => f.id)].filter(Boolean));
  assets.prune(used);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let s = '';
    req.on('data', (d) => { s += d; if (s.length > 2e6) req.destroy(); });
    req.on('end', () => { try { resolve(s ? JSON.parse(s) : {}); } catch (e) { reject(new Error('Invalid JSON')); } });
  });
}

function isLocal(req) {
  const a = req.socket.remoteAddress || '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

// The settings server listens only on this PC (and its port is not passed through the USB cable, so apps
// on the tablet cannot reach it). A web page open in a browser could still send it requests, so it also checks
// that the request comes from the settings page itself: the right Host (stops DNS tricks), the right Origin
// and a custom header that other sites cannot add without the PC agreeing.
const SETTINGS_HOSTS = new Set([`127.0.0.1:${SETTINGS_PORT}`, `localhost:${SETTINGS_PORT}`]);

function fromSettingsPage(req, needOrigin) {
  if (!isLocal(req) || !SETTINGS_HOSTS.has(String(req.headers.host || '').toLowerCase())) return false;
  const origin = req.headers.origin;
  if (!origin) return !needOrigin;
  return SETTINGS_HOSTS.has(String(origin).toLowerCase().replace(/^http:\/\//, ''));
}

const routes = {
  'GET /api/config': () => forPanel(config.get()),
  'PUT /api/config': async (req) => {
    const next = await readBody(req);
    // The list of allowed tablets is changed only by pairing, not by the editor; the PC id never changes.
    next.devices = config.get().devices;
    next.pcId = config.get().pcId;
    return config.set(next);
  },
  // Screensaver photo and custom clock fonts (stored under data/assets, sent to the tablet encrypted).
  'POST /api/assets': async (req, url) => {
    const kind = url.searchParams.get('kind') === 'font' ? 'font' : 'photo';
    const a = assets.save(kind, await readRaw(req, assets.LIMITS[kind]));
    if (kind === 'font') {
      const name = String(url.searchParams.get('name') || 'Custom font').replace(/\.(ttf|otf)$/i, '').slice(0, 40);
      config.update((c) => { if (!c.fonts.some((f) => f.id === a.id)) c.fonts.push({ id: a.id, name }); });
    } else {
      config.update((c) => { c.saver.photo = a.id; c.saver.background = 'photo'; });
    }
    pruneAssets(config.get());
    return a;
  },
  // "Show it on the tablet now" in the screensaver settings.
  'POST /api/saver/show': () => {
    if (!tablets.size) throw new Error('No tablet is connected');
    for (const ws of tablets) send(ws, { t: 'saver' });
    return { ok: true };
  },
  'POST /api/fonts/remove': async (req) => {
    const { id } = await readBody(req);
    const ref = 'custom:' + id;
    config.update((c) => {
      c.fonts = c.fonts.filter((f) => f.id !== id);
      if (c.theme.clockFont === ref) c.theme.clockFont = 'Fraunces';
      if (c.saver.font === ref) c.saver.font = '';
    });
    pruneAssets(config.get());
    return { ok: true };
  },
  // Earlier versions of the layout (kept automatically) and bringing one back.
  'GET /api/backups': () => config.backups().map((b) => {
    try {
      const c = config.readBackup(b.file);
      return { file: b.file, at: b.at, pages: c.pages.map((p) => p.name), tiles: Object.keys(c.tiles).length };
    } catch {
      return null;
    }
  }).filter(Boolean),
  'POST /api/backups/restore': async (req) => {
    const { file } = await readBody(req);
    config.restore(String(file || ''));
    console.log('Settings restored from backup', String(file));
    return { ok: true };
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
  'GET /api/tools': () => tools.status(),
  'POST /api/tools/install': async (req) => {
    const { tool } = await readBody(req);
    // adb right after installing: start watching for a tablet on the USB cable.
    return tools.install(String(tool), (name) => { if (name === 'adb') usb.start(PORT); });
  },
  'GET /api/notify/apps': () => notify.knownApps(),
  // How the tablet can reach this PC (shown in the settings window, which only this PC can open).
  'GET /api/network': async () => ({ pc: PC_NAME, port: PORT, usb: usb.connected(), ...(await netinfo.info()) }),
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
    if (!p || p.ws.readyState !== 1) throw new Error('The tablet is no longer waiting for approval');
    // A new random pairing key; the tablet gets it once, over the connection whose code the user just compared.
    const key = secure.newKey().toString('base64');
    config.update((c) => {
      c.devices.allowed = c.devices.allowed.filter((d) => d.deviceId !== deviceId);
      c.devices.allowed.push({ deviceId, name: p.name, key });
    });
    acceptTablet(p.ws, { key });
    return { ok: true };
  },
  'POST /api/devices/remove': async (req) => {
    const { deviceId } = await readBody(req);
    config.update((c) => { c.devices.allowed = c.devices.allowed.filter((d) => d.deviceId !== deviceId); });
    for (const ws of tablets) if (ws.device.deviceId === deviceId) ws.terminate();
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

// ---------- port 47801 (all networks): the tablet's encrypted connection, the USB check and Spotify's return ----------

const SETTINGS_URL = `http://127.0.0.1:${SETTINGS_PORT}/`;

async function handleTablet(req, res) {
  const url = new URL(req.url, 'http://x');

  // The tablet uses this to check whether it can reach the PC over the USB cable (adb reverse).
  if (url.pathname === '/ping') {
    if (process.env.LC_PINGLOG || fs.existsSync(path.join(config.DATA_DIR, 'pinglog'))) console.log('ping', req.socket.remoteAddress);
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    return res.end('lumen-desk');
  }

  // Spotify sends the browser back here after login (the address registered in the Spotify app); then on to the settings.
  if (url.pathname === '/spotify/callback' && isLocal(req)) {
    try {
      if (url.searchParams.get('error')) throw new Error(url.searchParams.get('error'));
      await spotify.finishLogin(url.searchParams.get('code'), url.searchParams.get('state'));
      res.writeHead(302, { Location: SETTINGS_URL + '#spotify' });
    } catch (e) {
      res.writeHead(302, { Location: SETTINGS_URL + '?spotifyError=' + encodeURIComponent(e.message) + '#spotify' });
    }
    return res.end();
  }

  res.writeHead(404);
  res.end();
}

const server = http.createServer((req, res) => {
  handleTablet(req, res).catch(() => { res.writeHead(500); res.end(); });
});

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 1 << 20 });
wss.on('connection', (ws, req) => {
  ws.ip = (req.socket.remoteAddress || '').replace('::ffff:', '');
  // Over adb reverse the tablet comes from 127.0.0.1 (USB cable).
  ws.usb = ws.ip === '127.0.0.1' || ws.ip === '::1';
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  const params = new URL(req.url, 'http://x').searchParams;

  // Video for the tablet (second monitor): a separate connection, so video never slows down the controls.
  // It needs a one-time ticket the paired tablet got over its encrypted connection; the video is encrypted too.
  if (params.get('role') === 'display') {
    const ticket = displayTickets.get(String(params.get('token') || ''));
    displayTickets.delete(String(params.get('token') || ''));
    if (!ticket || ticket.exp < Date.now()) return ws.close();
    ws.session = new secure.Session(ticket.key, ticket.key);
    display.open(ws);
    return;
  }

  // Without a key exchange within 10 s the connection is closed.
  ws.helloTimer = setTimeout(() => { if (!ws.session) ws.close(4408, 'no hello2 within 10 s'); }, 10000);
  ws.on('message', (data, isBinary) => {
    if (!ws.session) {
      let msg;
      try { msg = JSON.parse(data); } catch { return ws.close(4400, 'bad hello'); }
      // An app from before the encrypted connection: tell it why instead of closing silently
      // (old apps show the toast; the close reason is for anything else that checks).
      if (!isBinary && msg && msg.t === 'hello') {
        if (ws.ip) logTablet('refused: old app without encryption, update Lumen Desk on the tablet', ws);
        ws.send(JSON.stringify({ t: 'toast', text: 'Update Lumen Desk on this tablet – the PC now needs the encrypted connection.' }));
        return ws.close(4426, 'update required: encrypted hello2');
      }
      if (isBinary || !msg || msg.t !== 'hello2') return ws.close(4400, 'bad hello');
      return onHello(ws, msg);
    }
    // After the key exchange only encrypted messages count; anything else ends the connection.
    let msg;
    try {
      if (!isBinary) throw new Error('plain message');
      msg = JSON.parse(ws.session.open(data));
    } catch {
      logTablet('refused: wrong key or damaged message', ws);
      return ws.close(4401, 'not authorized: wrong key or damaged message');
    }
    if (msg && typeof msg === 'object') onTabletMessage(ws, msg);
  });
  ws.on('close', (code) => {
    clearTimeout(ws.helloTimer);
    if (ws.device && fs.existsSync(path.join(config.DATA_DIR, 'pinglog'))) console.log('close', ws.ip, code);
    // Nothing stays pressed when the tablet goes away in the middle of a drag or with a locked Ctrl.
    if (tablets.has(ws)) {
      input.releaseAll();
      display.drawRelease();
      logTablet(`disconnected, code ${code}`, ws);
    }
    tablets.delete(ws);
    if (ws.device) {
      const p = pending.get(ws.device.deviceId);
      if (p && p.ws === ws) pending.delete(ws.device.deviceId);
    }
    updateSpotifyPolling();
    broadcastPanels();
  });
});

// ---------- port 47803 (only this PC): the settings window and its API ----------

async function handleSettings(req, res) {
  const url = new URL(req.url, 'http://x');
  if (!fromSettingsPage(req, false)) {
    res.writeHead(403);
    return res.end();
  }

  if (url.pathname === '/spotify/login') {
    try {
      res.writeHead(302, { Location: spotify.loginUrl() });
    } catch (e) {
      res.writeHead(302, { Location: '/?spotifyError=' + encodeURIComponent(e.message) });
    }
    return res.end();
  }

  // Uploaded photos and fonts for the preview (an <img> or @font-face cannot send the X-Lumen header;
  // the Host check above already keeps other sites out, and the ids are content hashes).
  if (url.pathname.startsWith('/asset/')) {
    const a = assets.find(url.pathname.slice(7));
    if (!a) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': a.type, 'Cache-Control': 'max-age=31536000, immutable', 'X-Content-Type-Options': 'nosniff' });
    return res.end(a.buf);
  }
  // The bundled clock fonts (the same files the tablet has).
  if (url.pathname.startsWith('/fonts/')) {
    const name = decodeURIComponent(url.pathname.slice(7));
    if (!/^[\w.-]+\.(ttf|json)$/.test(name)) { res.writeHead(404); return res.end(); }
    return fs.readFile(path.join(paths.FONTS, name), (err, data) => {
      if (err) { res.writeHead(404); return res.end(); }
      res.writeHead(200, { 'Content-Type': name.endsWith('.json') ? 'application/json' : 'font/ttf', 'Cache-Control': 'no-cache' });
      res.end(data);
    });
  }

  if (url.pathname.startsWith('/api/')) {
    if (req.headers['x-lumen'] !== '1') return json(res, 403, { error: 'Settings can only be changed from the settings window' });
    const route = routes[`${req.method} ${url.pathname}`];
    if (!route) return json(res, 404, { error: 'Not found' });
    try {
      return json(res, 200, await route(req, url));
    } catch (e) {
      return json(res, 400, { error: e.message });
    }
  }

  let rel;
  try {
    rel = url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname.slice(1));
  } catch {
    res.writeHead(400);
    return res.end();
  }
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
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
      'X-Frame-Options': 'DENY',
      'X-Content-Type-Options': 'nosniff'
    });
    res.end(data);
  });
}

const settingsServer = http.createServer((req, res) => {
  handleSettings(req, res).catch((e) => json(res, 500, { error: e.message }));
});

const panelWss = new WebSocketServer({ server: settingsServer, path: '/ws', maxPayload: 1 << 20 });
panelWss.on('connection', (ws, req) => {
  // Browsers always send the page's Origin with a WebSocket: only the settings page itself may listen.
  if (!fromSettingsPage(req, true)) return ws.close();
  ws.panel = true;
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  panels.add(ws);
  send(ws, panelStatus());
  updateSpotifyPolling();
  ws.on('close', () => { panels.delete(ws); updateSpotifyPolling(); });
});

// Dead connections (tablet asleep, Wi-Fi off) are dropped within 20 s.
setInterval(() => {
  for (const ws of [...wss.clients, ...panelWss.clients]) {
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
  const msg = { t: 'config', config: forPanel(c) };
  for (const ws of panels) send(ws, msg);
});
yeelight.events.on('state', () => { broadcastState(); broadcastPanels(); });
spotify.events.on('playback', () => broadcastState());
spotify.events.on('status', () => broadcastPanels());
audio.events.on('change', () => broadcastState());
claude.events.on('change', () => broadcastState());
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
if (!QUIET) beacon.bind(() => {
  beacon.setBroadcast(true);
  const msg = Buffer.from(JSON.stringify({ app: 'lumen-desk', port: PORT, name: PC_NAME, id: config.get().pcId }));
  setInterval(() => {
    // Each network's own broadcast address, plus 255.255.255.255 for networks with an unusual mask (phone hotspots).
    for (const addr of [...broadcastAddresses(), '255.255.255.255']) beacon.send(msg, BEACON_PORT, addr, () => {});
  }, 2000);
});

// A second start (e.g. from the desktop icon) just exits, the server is already running.
const onListenError = (e) => {
  if (e.code === 'EADDRINUSE') {
    // Said out loud: a copy that quietly stops makes "the server does not run" very hard to find.
    console.log(`Port ${e.port} is already in use (another Lumen Desk, or another program). This copy stops.`);
    process.exit(0);
  }
  throw e;
};
server.on('error', onListenError);
settingsServer.on('error', onListenError);

server.listen(PORT, '0.0.0.0', () => {
  if (!QUIET) usb.start(PORT);
});
settingsServer.listen(SETTINGS_PORT, '127.0.0.1', () => {
  console.log(`Lumen Desk running: ${SETTINGS_URL}`);
});
