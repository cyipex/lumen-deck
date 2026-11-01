// Tablet over a USB cable: when it is connected and has USB debugging enabled, we set up "adb reverse".
// The tablet then reaches the server at its own 127.0.0.1:47801 through the cable – faster and more stable
// than Wi-Fi (especially for the second monitor), and the tablet charges at the same time.
const { execFile } = require('child_process');
const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');

const ADB = path.join(require('./paths').BIN, 'adb.exe');
const events = new EventEmitter();

let port = 47801;
let timer = null;
let reversed = new Set(); // serial numbers of tablets that already have reverse running

function adb(args) {
  return new Promise((resolve) => {
    execFile(ADB, args, { windowsHide: true, timeout: 8000 }, (err, out) => resolve(err ? null : String(out)));
  });
}

async function check() {
  const out = await adb(['devices']);
  if (out === null) return;
  const now = new Set(
    out.split(/\r?\n/).slice(1)
      .map((l) => l.trim().split(/\s+/))
      .filter((p) => p.length >= 2 && p[1] === 'device')
      .map((p) => p[0])
  );
  for (const serial of now) {
    if (reversed.has(serial)) continue;
    const ok = await adb(['-s', serial, 'reverse', `tcp:${port}`, `tcp:${port}`]);
    if (ok !== null) {
      reversed.add(serial);
      console.log(`USB: tablet ${serial} connected by cable (adb reverse).`);
      events.emit('change');
    }
  }
  for (const serial of [...reversed]) {
    if (!now.has(serial)) {
      reversed.delete(serial);
      console.log(`USB: tablet ${serial} disconnected.`);
      events.emit('change');
    }
  }
}

function start(p) {
  port = p;
  if (timer || !fs.existsSync(ADB)) return;
  check();
  timer = setInterval(check, 4000);
}

// When the server stops, adb keeps running (Android Studio and other tools use it too).
function stop() {
  clearInterval(timer);
  timer = null;
}

module.exports = { start, stop, connected: () => reversed.size > 0, events };
