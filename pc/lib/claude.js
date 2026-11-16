// Claude usage limits on the tablet. Claude Code hands its status-line command a JSON with the plan's
// usage windows (rate_limits: five_hour, seven_day); "LumenDesk.exe --claude-status" is that command and
// saves the numbers here (save). The server watches the file (start) and sends them to the tablet.
// Nothing is fetched from the internet and no login or token is touched.
const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');
const paths = require('./paths');

const FILE = path.join(paths.DATA, 'claude-usage.json');
const events = new EventEmitter();

const pct = (v) => Math.max(0, Math.min(100, Math.round(Number(v) || 0)));

// Only numbers leave the status-line JSON: a percentage and the reset time (Unix seconds) per window.
function windowOf(w) {
  if (!w || typeof w !== 'object' || !Number.isFinite(Number(w.resets_at))) return null;
  return { pct: pct(w.used_percentage), resets: Math.round(Number(w.resets_at)) };
}

// Called by the status-line helper with what Claude Code sent; writes a temp file and renames it, so the
// server never reads half a file. Returns a short text for the status line itself.
function save(input) {
  let j = {};
  try { j = JSON.parse(input || '{}'); } catch {}
  const r = (j && j.rate_limits) || {};
  const five = windowOf(r.five_hour);
  const week = windowOf(r.seven_day);
  if (five || week) {
    fs.mkdirSync(paths.DATA, { recursive: true });
    const tmp = FILE + '.' + process.pid + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify({ at: Date.now(), five_hour: five, seven_day: week }));
    fs.renameSync(tmp, FILE);
  }
  const model = j && j.model && typeof j.model.display_name === 'string' ? j.model.display_name : '';
  return [model, five && `5h ${five.pct} %`, week && `week ${week.pct} %`].filter(Boolean).join(' · ');
}

// What the tablet gets: windows whose reset time has passed are dropped (the numbers are no longer true).
let latest = null;
function current() {
  if (!latest) return null;
  const now = Date.now() / 1000;
  const keep = (w) => (w && w.resets > now ? w : null);
  const five = keep(latest.five_hour);
  const week = keep(latest.seven_day);
  return five || week ? { five, week, at: latest.at } : null;
}

function read() {
  try {
    const j = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    const clean = (w) => (w && Number.isFinite(w.resets) ? { pct: pct(w.pct), resets: Math.round(w.resets) } : null);
    latest = { at: Number(j.at) || 0, five_hour: clean(j.five_hour), seven_day: clean(j.seven_day) };
  } catch {
    latest = null;
  }
  events.emit('change');
}

let started = false;
function start() {
  if (started) return;
  started = true;
  read();
  fs.watchFile(FILE, { interval: 2000 }, read);
  // A window that runs out has to disappear on the tablet even when nothing new arrives.
  setInterval(() => events.emit('change'), 60000).unref();
}

module.exports = { FILE, save, current, start, events };
