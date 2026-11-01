// Entry point of the installed app, LumenDesk.exe (Node.js and the server packed into one file).
//   LumenDesk.exe               opens the settings window, starting the server first if needed
//   LumenDesk.exe --background  only starts the server (used when Windows starts)
//   LumenDesk.exe --supervisor  keeps the server running and restarts it after a crash (run.js)
//   LumenDesk.exe --server      the server itself (started by the supervisor)
const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');

const args = process.argv.slice(1);
const URL = 'http://127.0.0.1:47801/';

function ping() {
  return new Promise((resolve) => {
    const req = http.get(URL + 'ping', { timeout: 800 }, (res) => {
      res.resume();
      resolve(res.statusCode === 200);
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

async function ensureRunning() {
  if (await ping()) return true;
  const p = spawn(process.execPath, ['--supervisor'], { detached: true, stdio: 'ignore', windowsHide: true });
  p.unref();
  for (let i = 0; i < 40; i++) {
    await new Promise((r) => setTimeout(r, 250));
    if (await ping()) return true;
  }
  return false;
}

// The settings window: Edge in app mode looks like a normal app window; otherwise the default browser.
function openSettings() {
  const pf86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
  const pf = process.env.ProgramFiles || 'C:\\Program Files';
  const edge = [path.join(pf86, 'Microsoft', 'Edge', 'Application', 'msedge.exe'), path.join(pf, 'Microsoft', 'Edge', 'Application', 'msedge.exe')]
    .find((p) => fs.existsSync(p));
  const p = edge
    ? spawn(edge, ['--app=' + URL, '--window-size=1500,920'], { detached: true, stdio: 'ignore' })
    : spawn('cmd.exe', ['/c', 'start', '""', URL], { detached: true, stdio: 'ignore', windowsHide: true });
  p.unref();
}

if (args.includes('--server')) {
  require('./server');
} else if (args.includes('--supervisor')) {
  require('./run');
} else {
  ensureRunning().then((ok) => {
    if (!args.includes('--background')) openSettings();
    if (!ok) process.exitCode = 1;
  });
}
