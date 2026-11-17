// The tablet as a second monitor. Windows has a virtual monitor (Virtual Display Driver), ffmpeg
// captures it (ddagrab), encodes it to H.264, and the server sends individual NAL units to the tablet.
// Touches on the tablet become mouse moves and clicks on that monitor.
const { spawn, execFile } = require('child_process');
const fs = require('fs');
const path = require('path');
const input = require('./input');
const vmonitor = require('./vmonitor');

const paths = require('./paths');
const FFMPEG = path.join(paths.BIN, 'ffmpeg.exe');

// Monitors in physical pixels (the process must be DPI aware, otherwise Windows returns scaled numbers).
const SCREENS_PS = `
Add-Type -Namespace LcDpi -Name U -MemberDefinition '[DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);'
[void][LcDpi.U]::SetProcessDpiAwarenessContext([IntPtr]-4)
Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.Screen]::AllScreens | ForEach-Object { [pscustomobject]@{ name = $_.DeviceName; primary = $_.Primary; x = $_.Bounds.X; y = $_.Bounds.Y; w = $_.Bounds.Width; h = $_.Bounds.Height } } | ConvertTo-Json -Compress
`;

function screens() {
  return new Promise((resolve, reject) => {
    const encoded = Buffer.from(SCREENS_PS, 'utf16le').toString('base64');
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], { windowsHide: true, timeout: 15000 }, (err, out) => {
      if (err) return reject(err);
      try {
        const list = JSON.parse(out.trim());
        resolve(Array.isArray(list) ? list : [list]);
      } catch (e) {
        reject(new Error('Cannot read the monitors'));
      }
    });
  });
}

// A short ffmpeg run finds out which DXGI output is which monitor (by desktop coordinates).
function probe(idx) {
  return new Promise((resolve) => {
    execFile(FFMPEG, ['-hide_banner', '-v', 'verbose', '-f', 'lavfi', '-i', `ddagrab=output_idx=${idx}:framerate=1`, '-frames:v', '1', '-f', 'null', '-'],
      { windowsHide: true, timeout: 10000 }, (err, out, errOut) => {
        const m = /desktop coordinates[^\d-]*(-?\d+)[^\d-]+(-?\d+)/i.exec(errOut || '');
        const size = /,\s*(\d{3,5})x(\d{3,5})/.exec(errOut || '');
        resolve(err && !size ? null : { idx, x: m ? Number(m[1]) : null, y: m ? Number(m[2]) : null, w: size ? Number(size[1]) : null, h: size ? Number(size[2]) : null, log: errOut });
      });
  });
}

let target = null; // { screen, idx, desk }

async function findTarget() {
  if (!fs.existsSync(FFMPEG)) throw new Error('ffmpeg is not installed yet. On the PC open Lumen Deck → Tablet → Extras and install it.');
  const list = await screens();
  const others = list.filter((s) => !s.primary);
  if (!others.length) throw new Error('Virtual monitor not found. Install Virtual Display Driver and set Windows to Extend these displays.');
  const screen = others.find((s) => s.w === 1280 && s.h === 800) || others[others.length - 1];
  const desk = {
    x: Math.min(...list.map((s) => s.x)),
    y: Math.min(...list.map((s) => s.y)),
    r: Math.max(...list.map((s) => s.x + s.w)),
    b: Math.max(...list.map((s) => s.y + s.h))
  };
  let idx = null;
  const probes = [];
  for (let i = 0; i < 4; i++) {
    const p = await probe(i);
    if (!p) break;
    probes.push(p);
    if (p.x === screen.x && p.y === screen.y) { idx = i; break; }
  }
  if (idx === null) {
    const bySize = probes.filter((p) => p.w === screen.w && p.h === screen.h);
    if (bySize.length) idx = bySize[bySize.length - 1].idx;
  }
  if (idx === null) throw new Error('ffmpeg cannot see the virtual monitor');
  target = { screen, idx, desk };
  return target;
}

// ---------- video stream ----------

let viewer = null; // { ws, proc }

// usb: the tablet is connected by cable (adb reverse) and handles a higher bitrate = sharper picture.
function encoderArgs(amf, usb) {
  // Frames are sent all the time (even when nothing moves): the tablet's decoder outputs a frame only after it gets the next one,
  // so without them a still screen would stay black. On Wi-Fi (tablet on battery) 30 fps is enough.
  const fps = usb ? 60 : 30;
  const grab = `ddagrab=output_idx=${target.idx}:framerate=${fps}:draw_mouse=1`;
  const rate = amf ? (usb ? '16M' : '8M') : (usb ? '10M' : '6M');
  // AMF does not accept frames from the virtual monitor directly from the GPU (error 18), so they go through memory.
  // -latency 1 -async_depth 1: the encoder outputs every frame right away, without waiting for more.
  return amf
    ? ['-hide_banner', '-loglevel', 'fatal', '-filter_complex', `${grab},hwdownload,format=bgra,format=nv12`,
       '-c:v', 'h264_amf', '-usage', 'ultralowlatency', '-latency', '1', '-async_depth', '1', '-quality', 'speed', '-rc', 'cbr', '-b:v', rate, '-maxrate', rate, '-bufsize', '2M',
       '-g', String(fps * 2), '-bf', '0', '-profile:v', 'main', '-header_insertion_mode', 'idr', '-f', 'h264', '-']
    : ['-hide_banner', '-loglevel', 'fatal', '-filter_complex', `${grab},hwdownload,format=bgra,format=yuv420p`,
       '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency', '-profile:v', 'baseline', '-b:v', rate, '-maxrate', rate, '-bufsize', '2M',
       '-g', '120', '-x264-params', 'repeat-headers=1', '-f', 'h264', '-'];
}

// Splits the H.264 (Annex B) stream into NAL units by the 00 00 01 start codes.
function nalSplitter(onNal) {
  let buf = Buffer.alloc(0);
  return (chunk) => {
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    let start = buf.indexOf(Buffer.from([0, 0, 1]));
    if (start < 0) return;
    for (;;) {
      const next = buf.indexOf(Buffer.from([0, 0, 1]), start + 3);
      if (next < 0) break;
      let end = next;
      if (end > 0 && buf[end - 1] === 0) end--;
      onNal(buf.subarray(start + 3, end));
      start = next;
    }
    buf = buf.subarray(start);
  };
}

const START = Buffer.from([0, 0, 0, 1]);

// Everything on the video connection is encrypted with the key from its one-time ticket (server.js).
function out(ws, data) {
  ws.send(ws.session.seal(Buffer.isBuffer(data) ? data : Buffer.from(JSON.stringify(data))), { binary: true });
}

function startStream(ws, amf = true) {
  const usb = ws.usb;
  const proc = spawn(FFMPEG, encoderArgs(amf, usb), { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const v = { ws, proc, amf, frames: 0, dropping: false, err: '' };
  viewer = v;
  if (fs.existsSync(path.join(paths.DATA, 'pinglog'))) {
    console.log('display: start', amf ? 'amf' : 'x264', usb ? 'usb' : 'wifi');
    v.dbg = setInterval(() => console.log('display: NAL', v.frames, 'buffered', ws.bufferedAmount, 'dropping', v.dropping, 'err', v.err.slice(-200)), 3000);
  }
  // After the connection was flooded: once the backlog is sent, restart encoding. The new stream starts
  // with a keyframe right away (otherwise a still screen would wait a very long time for the next one).
  v.watch = setInterval(() => {
    if (viewer !== v || !v.dropping || ws.bufferedAmount > 100e3) return;
    clearInterval(v.watch);
    viewer = null;
    try { proc.kill(); } catch {}
    if (ws.readyState === 1) startStream(ws, amf);
  }, 200);
  proc.stderr.setEncoding('utf8');
  proc.stderr.on('data', (d) => { v.err = (v.err + d).slice(-2000); });
  proc.stdout.on('data', nalSplitter((nal) => {
    if (!nal.length || ws.readyState !== 1) return;
    const type = nal[0] & 0x1f;
    v.frames++;
    // Slow Wi-Fi: drop frames until the next keyframe, so the picture does not lag behind.
    if (ws.bufferedAmount > 1.5e6) v.dropping = true;
    if (v.dropping) {
      if (type !== 7) return;
      v.dropping = false;
    }
    out(ws, Buffer.concat([START, nal]));
  }));
  proc.on('exit', (code) => {
    clearInterval(v.watch);
    clearInterval(v.dbg);
    if (v.dbg) console.log('display: ffmpeg stopped', code, v.err.slice(-300));
    if (viewer !== v) return;
    // AMF does not work (different graphics driver) -> try the CPU encoder.
    if (amf && v.frames === 0) {
      console.log('h264_amf failed, trying libx264:', v.err.trim());
      return startStream(ws, false);
    }
    viewer = null;
    if (ws.readyState === 1) ws.close(1011, 'ffmpeg stopped');
  });
}

// Called when nobody is watching any more (server.js turns the virtual monitor off after a while).
let onIdle = () => {};

async function open(ws) {
  close();
  try {
    // The virtual monitor may be switched off while it is not used: attach it first.
    try { await vmonitor.on(); } catch {}
    await findTarget();
  } catch (e) {
    out(ws, { t: 'error', text: e.message });
    return ws.close();
  }
  if (ws.readyState !== 1) return;
  out(ws, { t: 'info', w: target.screen.w, h: target.screen.h });
  startStream(ws);
  ws.on('close', () => { if (viewer && viewer.ws === ws) close(); onIdle(); });
}

function close() {
  if (!viewer) return;
  const v = viewer;
  viewer = null;
  clearInterval(v.watch);
  try { v.proc.kill(); } catch {}
}

// ---------- touches -> mouse ----------

const { MOUSE } = input;

// x, y are 0..1 within the virtual monitor.
function pointer(msg) {
  if (!target) return;
  const { screen, desk } = target;
  const px = screen.x + Math.max(0, Math.min(1, Number(msg.x) || 0)) * (screen.w - 1);
  const py = screen.y + Math.max(0, Math.min(1, Number(msg.y) || 0)) * (screen.h - 1);
  const nx = ((px - desk.x) * 65535) / (desk.r - desk.x - 1);
  const ny = ((py - desk.y) * 65535) / (desk.b - desk.y - 1);
  const at = MOUSE.move | MOUSE.abs | MOUSE.desk;
  switch (msg.a) {
    case 'move': return input.mouse(nx, ny, at);
    case 'down': return input.mouse(nx, ny, at | MOUSE.ldown);
    case 'up': return input.mouse(nx, ny, at | MOUSE.lup);
    case 'click': input.mouse(nx, ny, at | MOUSE.ldown); return input.mouse(nx, ny, at | MOUSE.lup);
    case 'rclick': input.mouse(nx, ny, at | MOUSE.rdown); return input.mouse(nx, ny, at | MOUSE.rup);
    case 'wheel': return input.mouse(nx, ny, at | MOUSE.wheel, Number(msg.d) || 0);
  }
}

// ---------- drawing tablet: the tablet's surface is one whole monitor, the finger is the pen ----------

let drawTarget = null; // { screen, desk }
let drawDown = false;

// Picks monitor number i (the main one first, then left to right) and tells the tablet its size.
async function drawScreen(i) {
  const list = (await screens()).sort((a, b) => (b.primary - a.primary) || (a.x - b.x));
  const n = list.length;
  const idx = (((Number(i) || 0) % n) + n) % n;
  drawTarget = {
    screen: list[idx],
    desk: {
      x: Math.min(...list.map((s) => s.x)),
      y: Math.min(...list.map((s) => s.y)),
      r: Math.max(...list.map((s) => s.x + s.w)),
      b: Math.max(...list.map((s) => s.y + s.h))
    }
  };
  return { i: idx, n, w: list[idx].w, h: list[idx].h };
}

// a: 'hover' (only moves), 'down' (pen on the paper = left button), 'move', 'up'. x, y are 0..1 on the monitor.
function draw(msg) {
  if (!drawTarget) return;
  const { screen, desk } = drawTarget;
  const px = screen.x + Math.max(0, Math.min(1, Number(msg.x) || 0)) * (screen.w - 1);
  const py = screen.y + Math.max(0, Math.min(1, Number(msg.y) || 0)) * (screen.h - 1);
  const nx = ((px - desk.x) * 65535) / (desk.r - desk.x - 1);
  const ny = ((py - desk.y) * 65535) / (desk.b - desk.y - 1);
  const at = MOUSE.move | MOUSE.abs | MOUSE.desk;
  if (msg.a === 'down') {
    drawDown = true;
    return input.mouse(nx, ny, at | MOUSE.ldown);
  }
  if (msg.a === 'up') {
    drawDown = false;
    return input.mouse(nx, ny, at | MOUSE.lup);
  }
  input.mouse(nx, ny, at);
}

// The tablet went away with the pen still down.
function drawRelease() {
  if (drawDown) input.mouse(0, 0, MOUSE.lup);
  drawDown = false;
}

const watching = () => !!viewer;
const setOnIdle = (fn) => { onIdle = fn; };

module.exports = { watching, setOnIdle, open, close, pointer, findTarget, screens, drawScreen, draw, drawRelease };
