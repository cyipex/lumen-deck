// Security and protocol test: starts a copy of the server on other ports with its own empty data folder
// (no announcement, no USB, nothing of the real setup is touched) and uses it like a tablet, a web page
// and an attacker would.   Run: node test/server-test.js   (in pc/)
const { spawn } = require('child_process');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const PC = path.join(__dirname, '..');
const WebSocket = require(path.join(PC, 'node_modules', 'ws'));
const secure = require(path.join(PC, 'lib', 'secure.js'));

const T = 48801, S = 48803;
const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'lumen-test-'));
// A pretend tablet app the PC can offer as an update.
const TAB = fs.mkdtempSync(path.join(os.tmpdir(), 'lumen-tab-'));
const APK = crypto.randomBytes(500000);
fs.writeFileSync(path.join(TAB, 'lumen-desk.apk'), APK);
fs.writeFileSync(path.join(TAB, 'version.json'), JSON.stringify({ code: 500, sha256: crypto.createHash('sha256').update(APK).digest('hex'), size: APK.length }));
let ok = true;
const check = (c, what) => { console.log((c ? 'PASS ' : 'FAIL ') + what); if (!c) ok = false; };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

function req(port, method, p, headers = {}, body) {
  return new Promise((resolve) => {
    const r = http.request({ host: '127.0.0.1', port, method, path: p, headers }, (res) => {
      let s = '';
      res.on('data', (d) => { s += d; });
      res.on('end', () => resolve({ code: res.statusCode, body: s }));
    });
    r.on('error', (e) => resolve({ code: 0, body: e.message }));
    if (body) r.write(body);
    r.end();
  });
}

const hkdf = (ikm, salt, info, len) => Buffer.from(crypto.hkdfSync('sha256', ikm, salt, info, len));

// A tablet: resolves after the key exchange (or when the server closes the connection).
function tablet(deviceId, key, opts = {}) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://127.0.0.1:${T}/ws`);
    const inbox = [];
    const ecdh = crypto.createECDH('prime256v1');
    const epk = ecdh.generateKeys();
    const cn = crypto.randomBytes(16);
    let session = null;
    const t = { ws, inbox, closed: false };
    ws.on('close', (c, r) => { t.closed = true; t.closeCode = c; t.closeReason = String(r); resolve(t); });
    ws.on('error', () => {});
    ws.on('open', () => {
      if (opts.legacy) return ws.send(JSON.stringify({ t: 'hello', deviceId, name: 'Old' }));
      const hello = { t: 'hello2', deviceId, name: 'Test tablet', epk: epk.toString('base64'), cn: cn.toString('base64'), paired: !!key };
      if (opts.pcs) hello.pcs = opts.pcs;
      ws.send(JSON.stringify(hello));
    });
    ws.on('message', (data) => {
      if (opts.legacy) return inbox.push(JSON.parse(data));
      if (!session) {
        const m = JSON.parse(data);
        const spk = Buffer.from(m.spk, 'base64'), sn = Buffer.from(m.sn, 'base64');
        const shared = ecdh.computeSecret(spk);
        const salt = crypto.createHash('sha256').update(Buffer.concat([cn, sn, epk, spk, Buffer.from(deviceId)])).digest();
        const ikm = m.mode === 'paired' ? Buffer.concat([shared, key]) : shared;
        t.mode = m.mode;
        t.pcId = m.pcId;
        t.code = String(hkdf(ikm, salt, 'lumen-desk code', 4).readUInt32BE(0) % 1000000).padStart(6, '0');
        session = new secure.Session(hkdf(ikm, salt, 'lumen-desk c2s', 32), hkdf(ikm, salt, 'lumen-desk s2c', 32));
        t.send = (o) => ws.send(session.seal(o), { binary: true });
        t.send({ t: 'ready' });
        setTimeout(() => resolve(t), 400);
        return;
      }
      try { inbox.push(JSON.parse(session.open(data))); } catch { inbox.push({ t: 'UNREADABLE' }); }
    });
  });
}

(async () => {
  const srv = spawn(process.execPath, ['server.js'], {
    cwd: PC,
    env: { ...process.env, LUMEN_PORT: T, LUMEN_SETTINGS_PORT: S, LUMEN_QUIET: '1', LUMEN_DATA_DIR: DATA, LUMEN_TABLET_DIR: TAB },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  srv.stderr.on('data', (d) => process.stderr.write('[server] ' + d));
  await wait(2500);
  const H = { Host: `127.0.0.1:${S}`, 'X-Lumen': '1' };
  const JH = { ...H, 'Content-Type': 'application/json' };
  try {
    // ---- settings API ----
    check((await req(S, 'GET', '/api/config', { Host: `127.0.0.1:${S}` })).code === 403, 'API without X-Lumen header refused');
    check((await req(S, 'GET', '/api/config', H)).code === 200, 'API from the settings page works');
    check((await req(S, 'GET', '/api/config', { ...H, Origin: 'https://evil.example' })).code === 403, 'API from another website refused (Origin)');
    check((await req(S, 'GET', '/api/config', { ...H, Host: `evil.example:${S}` })).code === 403, 'DNS rebinding refused (Host)');
    check((await req(S, 'POST', '/api/run', { Host: `127.0.0.1:${S}`, 'Content-Type': 'text/plain' }, '{"steps":[]}')).code === 403, 'cross-site "simple" POST /api/run refused');
    check((await req(T, 'GET', '/api/config', { 'X-Lumen': '1' })).code === 404, 'no settings API on the tablet port');
    check((await req(T, 'GET', '/', {})).code === 404, 'no settings page on the tablet port');
    check((await req(T, 'GET', '/ping')).body === 'lumen-desk', 'USB ping still answers');
    const net = await req(S, 'GET', '/api/network', H);
    check(net.code === 200 && Array.isArray(JSON.parse(net.body).addresses), 'connection info for the settings window');
    check((await req(T, 'GET', '/api/network', {})).code === 404, 'connection info not on the tablet port');

    // ---- settings window WebSocket ----
    const panel = (origin) => new Promise((resolve) => {
      const w = new WebSocket(`ws://127.0.0.1:${S}/ws`, origin ? { origin } : {});
      let got = null;
      w.on('message', (d) => { got = got || JSON.parse(d); });
      w.on('error', () => {});
      w.on('close', () => resolve({ got, closed: true, w }));
      setTimeout(() => resolve({ got, closed: false, w }), 700);
    });
    const evil = await panel('https://evil.example');
    check(evil.closed && !evil.got, 'settings WebSocket from another website refused');
    check((await panel(null)).closed, 'settings WebSocket without Origin refused');
    const good = await panel(`http://127.0.0.1:${S}`);
    check(!good.closed && good.got && good.got.t === 'status', 'settings WebSocket from the settings page works');
    good.w.close();

    // ---- tablet ----
    const old = await tablet('dev-old', null, { legacy: true });
    check(old.closed, 'old unencrypted hello refused');
    check(old.inbox.some((m) => m.t === 'toast') && old.closeCode === 4426, 'old app is told to update (toast + close 4426)');

    const id = 'test-' + crypto.randomBytes(4).toString('hex');
    const t1 = await tablet(id, null);
    check(t1.mode === 'pair' && t1.inbox[0] && t1.inbox[0].status === 'pending', 'new tablet waits for approval');
    check(t1.inbox[0] && t1.inbox[0].code === t1.code, 'tablet and PC computed the same code');
    const st = JSON.parse((await req(S, 'GET', '/api/status', H)).body);
    const pend = st.pending.find((p) => p.deviceId === id);
    check(pend && pend.code === t1.code, 'settings window shows the same code');
    check((await req(S, 'POST', '/api/devices/approve', JH, JSON.stringify({ deviceId: id }))).code === 200, 'approve');
    await wait(400);
    const okMsg = t1.inbox.find((m) => m.t === 'pair' && m.status === 'ok');
    check(okMsg && okMsg.key && Buffer.from(okMsg.key, 'base64').length === 32, 'tablet received its pairing key');
    check(t1.inbox.some((m) => m.t === 'config'), 'tablet gets config after approval');
    const state = t1.inbox.find((m) => m.t === 'state');
    check(state && 'claude' in state, 'state carries the Claude usage field');
    const key = Buffer.from(okMsg.key, 'base64');
    const cfgText = (await req(S, 'GET', '/api/config', H)).body;
    check(!cfgText.includes(okMsg.key) && JSON.parse(cfgText).devices.allowed.every((d) => !('key' in d)), 'settings API does not reveal pairing keys');
    check(!(await req(S, 'GET', '/api/status', H)).body.includes(okMsg.key), 'status does not reveal pairing keys');
    t1.ws.close();
    await wait(300);

    const t2 = await tablet(id, key);
    check(t2.mode === 'paired' && t2.inbox.some((m) => m.t === 'pair' && m.status === 'ok' && !m.key), 'paired tablet reconnects without approval');

    // Claude usage written the way the status-line helper writes it reaches the tablet.
    fs.writeFileSync(path.join(DATA, 'claude-usage.json'), JSON.stringify({ at: Date.now(), five_hour: { pct: 42, resets: Math.floor(Date.now() / 1000) + 3600 } }));
    await wait(3500);
    const cl = t2.inbox.filter((m) => m.t === 'state' && m.claude).pop();
    check(cl && cl.claude.five && cl.claude.five.pct === 42, 'Claude usage file reaches the tablet');

    // A font uploaded in the settings window reaches the tablet in encrypted pieces, byte for byte.
    const fontBuf = fs.readFileSync(path.join(PC, '..', 'shared', 'fonts', 'Lobster-Regular.ttf'));
    const up = JSON.parse((await req(S, 'POST', '/api/assets?kind=font&name=Mine.ttf', { ...H, 'Content-Type': 'application/octet-stream' }, fontBuf)).body);
    check(/^[a-f0-9]{32}$/.test(up.id || ''), 'font upload');
    check((await req(S, 'POST', '/api/assets?kind=font', { ...H, 'Content-Type': 'application/octet-stream' }, Buffer.from('not a font at all'))).code === 400, 'a file that is not a font is refused');
    t2.send({ t: 'asset', id: up.id, req: 1 });
    await wait(800);
    const pieces = t2.inbox.filter((m) => m.t === 'asset' && m.id === up.id).sort((a, b) => a.i - b.i);
    const got = Buffer.concat(pieces.map((m) => Buffer.from(m.data, 'base64')));
    check(pieces.length > 0 && got.equals(fontBuf), `asset arrives complete (${pieces.length} pieces)`);
    check(crypto.createHash('sha256').update(got).digest('hex').slice(0, 32) === up.id, 'asset id is its content hash');

    // Tablet app update over the encrypted connection.
    t2.send({ t: 'version', code: 600 });
    await wait(300);
    check(!t2.inbox.some((m) => m.t === 'update'), 'no update offered to a newer tablet app');
    t2.send({ t: 'version', code: 100 });
    await wait(300);
    const offer = t2.inbox.find((m) => m.t === 'update');
    check(offer && offer.code === 500 && offer.size === APK.length, 'update offered to an older tablet app');
    t2.send({ t: 'apk' });
    await wait(1000);
    const ap = t2.inbox.filter((m) => m.t === 'apk').sort((a, b) => a.i - b.i);
    const apk = Buffer.concat(ap.map((m) => Buffer.from(m.data, 'base64')));
    check(apk.equals(APK) && ap[0].sha256 === crypto.createHash('sha256').update(APK).digest('hex'), `update arrives complete and checkable (${ap.length} pieces)`);

    // Someone who knows the tablet id but not the key.
    const fake = await tablet(id, crypto.randomBytes(32));
    await wait(500);
    check(fake.closed && !fake.inbox.some((m) => m.t === 'config'), 'impostor with the right id but wrong key is dropped');
    check(fake.closeCode === 4401, 'wrong key is told with close code 4401');
    check(!t2.closed, 'the real tablet stays connected during the impostor attempt');

    t2.send({ t: 'ping', at: 1 });
    await wait(300);
    check(t2.inbox.some((m) => m.t === 'pong'), 'encrypted request/answer works');
    const t3 = await tablet(id, key);
    await wait(300);
    t3.ws.send(JSON.stringify({ t: 'type', text: 'x' }));
    await wait(400);
    check(t3.closed, 'plain-text message after the handshake is refused');

    // PC ids: a tablet with keys for several PCs names them; this PC uses its key only when it is named.
    check(/^[a-f0-9]{16}$/.test(t2.pcId || ''), 'PC sends its id in the key exchange');
    const named = await tablet(id, key, { pcs: [t2.pcId] });
    check(named.mode === 'paired', 'tablet naming this PC id is treated as paired');
    named.ws.close();
    const other = await tablet(id, key, { pcs: ['0000000000000000'] });
    check(other.mode === 'pair', 'tablet holding keys only for other PCs is asked to pair');
    other.ws.close();
    await wait(300);

    // Second monitor ticket.
    const t4 = await tablet(id, key);
    t4.send({ t: 'display', req: 7 });
    await wait(300);
    const tk = t4.inbox.find((m) => m.t === 'display');
    check(tk && tk.req === 7 && tk.token && Buffer.from(tk.key, 'base64').length === 32, 'display ticket issued over the encrypted link');
    const closes = (url) => new Promise((resolve) => { const w = new WebSocket(url); w.on('error', () => {}); w.on('close', () => resolve(true)); setTimeout(() => resolve(false), 700); });
    check(await closes(`ws://127.0.0.1:${T}/ws?role=display&token=wrong`), 'video connection with a wrong ticket refused');
    check(await closes(`ws://127.0.0.1:${T}/ws?role=display&device=${id}`), 'old video connection by tablet id refused');

    // Pending flood stays limited.
    for (let i = 0; i < 8; i++) tablet('flood-' + i, null);
    await wait(1500);
    const st2 = JSON.parse((await req(S, 'GET', '/api/status', H)).body);
    check(st2.pending.length <= 4, `waiting tablets limited (${st2.pending.length})`);
  } finally {
    srv.kill();
    setTimeout(() => {
      try { fs.rmSync(DATA, { recursive: true, force: true }); fs.rmSync(TAB, { recursive: true, force: true }); } catch {}
      console.log(ok ? 'ALL PASS' : 'SOMETHING FAILED');
      process.exit(ok ? 0 : 1);
    }, 500);
  }
})();
