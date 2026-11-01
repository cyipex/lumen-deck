// Server supervisor: starts server.js and restarts it when it crashes. Everything the server prints
// goes to data/logs/server.log (at 1 MB the old log is moved to server.old.log).
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const paths = require('./lib/paths');

const LOG_DIR = path.join(paths.DATA, 'logs');
const LOG = path.join(LOG_DIR, 'server.log');
fs.mkdirSync(LOG_DIR, { recursive: true });

function log(line) {
  try {
    if (fs.existsSync(LOG) && fs.statSync(LOG).size > 1e6) fs.renameSync(LOG, path.join(LOG_DIR, 'server.old.log'));
    fs.appendFileSync(LOG, `${new Date().toISOString()} ${line}\n`);
  } catch {}
}

function pipe(stream, prefix) {
  let buf = '';
  stream.setEncoding('utf8');
  stream.on('data', (d) => {
    buf += d;
    const lines = buf.split(/\r?\n/);
    buf = lines.pop();
    for (const l of lines) if (l.trim()) log(prefix + l);
  });
}

let crashes = [];

function start() {
  // Installed: the same exe with --server; from the repository: node server.js.
  const args = paths.packaged ? ['--server'] : [path.join(__dirname, 'server.js')];
  const p = spawn(process.execPath, args, {
    cwd: paths.APP,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    env: { ...process.env, LC_SUPERVISED: '1' }
  });
  pipe(p.stdout, '');
  pipe(p.stderr, 'ERROR ');
  p.on('exit', (code, signal) => {
    // Code 0: the server stopped by itself (e.g. another one is already running), so the supervisor stops too.
    if (code === 0) {
      log('Server stopped.');
      process.exit(0);
    }
    const now = Date.now();
    crashes = crashes.filter((t) => now - t < 60000).concat(now);
    // Repeated crashes: wait longer, so a server stuck in a crash loop does not flood the PC.
    const delay = crashes.length > 5 ? 30000 : 1000 * crashes.length;
    log(`Server crashed (code ${code}${signal ? ', ' + signal : ''}), restarting in ${delay / 1000} s.`);
    setTimeout(start, delay);
  });
}

log('Supervisor is starting the server.');
start();
