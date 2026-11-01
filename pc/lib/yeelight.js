// Yeelight bulbs directly on the home network (no cloud). In the Yeelight app, "LAN Control"
// must be turned on for the bulb.
const dgram = require('dgram');
const net = require('net');
const { EventEmitter } = require('events');

const SSDP_ADDR = '239.255.255.250';
const SSDP_PORT = 1982;
const CONTROL_PORT = 55443;

const events = new EventEmitter();
const bulbs = new Map(); // id -> { id, ip, name, model, power, bright, socket, queue }

function parseHeaders(text) {
  const h = {};
  for (const line of text.split(/\r?\n/)) {
    const i = line.indexOf(':');
    if (i > 0) h[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim();
  }
  return h;
}

// Sends M-SEARCH and waits for the bulbs to answer.
function discover(timeoutMs = 2500) {
  return new Promise((resolve) => {
    const found = new Map();
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    const msg = Buffer.from('M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1982\r\nMAN: "ssdp:discover"\r\nST: wifi_bulb\r\n');
    sock.on('message', (buf) => {
      const h = parseHeaders(buf.toString());
      const m = /yeelight:\/\/([\d.]+):(\d+)/.exec(h.location || '');
      if (!m || !h.id) return;
      found.set(h.id, {
        id: h.id,
        ip: m[1],
        model: h.model || '',
        name: decodeName(h.name) || 'Yeelight ' + (h.model || ''),
        power: h.power === 'on',
        bright: Number(h.bright) || 0
      });
    });
    sock.on('error', () => {});
    sock.bind(() => {
      try { sock.send(msg, SSDP_PORT, SSDP_ADDR); } catch {}
      setTimeout(() => { try { sock.send(msg, SSDP_PORT, SSDP_ADDR); } catch {} }, 600);
    });
    setTimeout(() => {
      try { sock.close(); } catch {}
      for (const b of found.values()) remember(b);
      resolve([...found.values()]);
    }, timeoutMs);
  });
}

function decodeName(n) {
  if (!n) return '';
  try { return Buffer.from(n, 'base64').toString('utf8'); } catch { return n; }
}

function remember(info) {
  const b = bulbs.get(info.id) || { queue: [], socket: null, nextId: 1 };
  Object.assign(b, info);
  bulbs.set(info.id, b);
  events.emit('state', publicState(b));
  return b;
}

function publicState(b) {
  return { id: b.id, ip: b.ip, name: b.name, model: b.model, power: !!b.power, bright: b.bright || 0, online: !!b.socket };
}

function connect(b) {
  if (b.socket || b.connecting) return;
  b.connecting = true;
  const s = net.createConnection({ host: b.ip, port: CONTROL_PORT });
  s.setNoDelay(true);
  s.setKeepAlive(true, 10000);
  let buf = '';
  s.on('connect', () => {
    b.connecting = false;
    b.socket = s;
    for (const line of b.queue.splice(0)) s.write(line);
    sendRaw(b, 'get_prop', ['power', 'bright']);
    events.emit('state', publicState(b));
  });
  s.on('data', (d) => {
    buf += d.toString();
    let i;
    while ((i = buf.indexOf('\r\n')) >= 0) {
      const line = buf.slice(0, i);
      buf = buf.slice(i + 2);
      handleLine(b, line);
    }
  });
  const drop = () => {
    b.connecting = false;
    if (b.socket === s) b.socket = null;
    events.emit('state', publicState(b));
  };
  s.on('error', drop);
  s.on('close', drop);
}

function handleLine(b, line) {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  if (msg.method === 'props' && msg.params) {
    if (msg.params.power) b.power = msg.params.power === 'on';
    if (msg.params.bright) b.bright = Number(msg.params.bright);
    events.emit('state', publicState(b));
  } else if (Array.isArray(msg.result) && msg.result.length === 2 && (msg.result[0] === 'on' || msg.result[0] === 'off')) {
    b.power = msg.result[0] === 'on';
    b.bright = Number(msg.result[1]) || b.bright;
    events.emit('state', publicState(b));
  }
}

function sendRaw(b, method, params) {
  const line = JSON.stringify({ id: b.nextId++, method, params }) + '\r\n';
  if (b.socket) b.socket.write(line);
  else {
    b.queue.push(line);
    connect(b);
  }
}

function ensure(device) {
  if (!device || !device.id) throw new Error('The tile has no bulb selected');
  return bulbs.get(device.id) || remember({ ...device, power: false, bright: 0 });
}

// cmd: toggle | on | off | bright (value 1–100)
function command(device, cmd, value) {
  const b = ensure(device);
  const smooth = ['smooth', 200];
  if (cmd === 'toggle') {
    b.power = !b.power;
    sendRaw(b, 'toggle', []);
  } else if (cmd === 'on' || cmd === 'off') {
    b.power = cmd === 'on';
    sendRaw(b, 'set_power', [cmd, ...smooth]);
  } else if (cmd === 'bright') {
    const v = Math.max(1, Math.min(100, Math.round(Number(value) || 100)));
    b.bright = v;
    sendRaw(b, 'set_bright', [v, ...smooth]);
  } else {
    throw new Error('Unknown bulb command: ' + cmd);
  }
  events.emit('state', publicState(b));
}

function states() {
  return [...bulbs.values()].map(publicState);
}

// Connects to the bulbs saved in the configuration, so their state is known right away.
function track(list) {
  for (const d of list || []) connect(ensure(d));
}

module.exports = { discover, command, states, track, events };
