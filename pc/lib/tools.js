// One-click install of the optional tools from the settings window:
//   ffmpeg – captures the PC screen for the second monitor (official Windows build from gyan.dev)
//   adb    – lets the tablet connect over a USB cable (Google's Android platform-tools)
// Only the needed files are kept, in the tools folder (paths.BIN).
const { execFile } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { pipeline } = require('stream/promises');
const { Readable } = require('stream');
const paths = require('./paths');

const TOOLS = {
  ffmpeg: {
    url: 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip',
    sha256: 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256',
    files: ['ffmpeg.exe']
  },
  adb: {
    url: 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip',
    files: ['adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll']
  }
};

const jobs = {}; // name -> { state: 'downloading'|'unpacking'|'error', got, total, error }

function installed(name) {
  return TOOLS[name].files.every((f) => fs.existsSync(path.join(paths.BIN, f)));
}

function status() {
  const out = {};
  for (const name of Object.keys(TOOLS)) out[name] = { installed: installed(name), ...(jobs[name] || {}) };
  return out;
}

function findFile(dir, name) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      const hit = findFile(p, name);
      if (hit) return hit;
    } else if (e.name.toLowerCase() === name.toLowerCase()) return p;
  }
  return null;
}

function unzip(zip, dest) {
  return new Promise((resolve, reject) => {
    const ps = `Expand-Archive -LiteralPath '${zip.replace(/'/g, "''")}' -DestinationPath '${dest.replace(/'/g, "''")}' -Force`;
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ps], { windowsHide: true, timeout: 300000 }, (err) =>
      err ? reject(new Error('Could not unpack the download')) : resolve());
  });
}

async function run(name) {
  const t = TOOLS[name];
  const job = (jobs[name] = { state: 'downloading', got: 0, total: 0 });
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lumen-' + name + '-'));
  try {
    const res = await fetch(t.url);
    if (!res.ok) throw new Error(`Download failed (${res.status})`);
    job.total = Number(res.headers.get('content-length')) || 0;
    const zip = path.join(tmp, name + '.zip');
    const hash = crypto.createHash('sha256');
    const counter = new (require('stream').Transform)({
      transform(chunk, enc, cb) { job.got += chunk.length; hash.update(chunk); cb(null, chunk); }
    });
    await pipeline(Readable.fromWeb(res.body), counter, fs.createWriteStream(zip));
    // Verify the published checksum when the source provides one.
    if (t.sha256) {
      const expected = (await (await fetch(t.sha256)).text()).trim().slice(0, 64).toLowerCase();
      if (hash.digest('hex') !== expected) throw new Error('The download is damaged (checksum mismatch). Try again.');
    }
    job.state = 'unpacking';
    const out = path.join(tmp, 'x');
    await unzip(zip, out);
    fs.mkdirSync(paths.BIN, { recursive: true });
    for (const f of t.files) {
      const src = findFile(out, f);
      if (!src) throw new Error(`${f} was not found in the download`);
      fs.copyFileSync(src, path.join(paths.BIN, f));
    }
    delete jobs[name];
    console.log(`Tools: ${name} installed.`);
  } catch (e) {
    jobs[name] = { state: 'error', error: e.message };
    console.error(`Tools: ${name} failed:`, e.message);
  } finally {
    fs.rm(tmp, { recursive: true, force: true }, () => {});
  }
}

// Starts the install in the background; the settings window polls status().
function install(name, onDone) {
  if (!TOOLS[name]) throw new Error('Unknown tool');
  if (jobs[name] && jobs[name].state !== 'error') return status();
  run(name).then(() => { if (installed(name) && onDone) onDone(name); });
  return status();
}

module.exports = { status, install, installed };
