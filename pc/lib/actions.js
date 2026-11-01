// Runs macro steps. Every tile has a list of steps that run one after another.
const { spawn } = require('child_process');
const input = require('./input');
const yeelight = require('./yeelight');
const spotify = require('./spotify');
const browsers = require('./browsers');

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// show: the program window should be shown (otherwise it inherits the server's hidden window, e.g. Chrome would stay in the background).
function detached(cmd, args, show = false) {
  const p = spawn(cmd, args, { detached: true, stdio: 'ignore', windowsHide: !show });
  p.on('error', () => {});
  p.unref();
}

const SYSTEM = {
  lock: () => detached('rundll32.exe', ['user32.dll,LockWorkStation']),
  sleep: () => detached('rundll32.exe', ['powrprof.dll,SetSuspendState', '0,1,0']),
  shutdown: () => detached('shutdown.exe', ['/s', '/t', '0']),
  restart: () => detached('shutdown.exe', ['/r', '/t', '0'])
};

async function runStep(step, ctx) {
  switch (step.type) {
    case 'keys':
      input.combo(step.combo);
      break;
    case 'text':
      input.type(step.text);
      break;
    case 'media':
      input.media(step.key);
      break;
    case 'run':
      if (!step.path) throw new Error('The "Run program" step has no path');
      detached('cmd.exe', ['/c', 'start', '""', step.path, ...(step.args ? splitArgs(step.args) : [])]);
      break;
    case 'app':
      if (!step.appId) throw new Error('The "App" step has no app selected');
      detached('explorer.exe', ['shell:AppsFolder\\' + step.appId]);
      break;
    case 'url': {
      if (!step.url) throw new Error('The "Web page" step has no address');
      const url = /^[a-z][a-z0-9+.-]*:/i.test(step.url) ? step.url : 'https://' + step.url;
      // Selected browser and profile (account), otherwise the default Windows browser.
      if (step.browser && /^https?:/i.test(url)) {
        const { exe, args } = browsers.launch(step.browser, url);
        detached(exe, args, true);
        // Windows does not let a window started from the background jump to the front, so the helper brings it forward.
        setTimeout(() => input.focus(require('path').basename(exe, '.exe')), 700);
        setTimeout(() => input.focus(require('path').basename(exe, '.exe')), 1800);
      } else {
        detached('cmd.exe', ['/c', 'start', '""', url.replace(/&/g, '^&')]);
      }
      break;
    }
    case 'system':
      if (!SYSTEM[step.cmd]) throw new Error('Unknown system command: ' + step.cmd);
      SYSTEM[step.cmd]();
      break;
    case 'wait':
      await wait(Math.max(0, Math.min(60000, Number(step.ms) || 0)));
      break;
    case 'light': {
      const dev = ctx.lights.find((l) => l.id === step.device);
      yeelight.command(dev, step.cmd || 'toggle', step.value);
      break;
    }
    case 'spotify': {
      const ok = await spotify.control(step.cmd || 'toggle', step.uri || step.value);
      if (!ok && ['toggle', 'next', 'prev'].includes(step.cmd || 'toggle')) {
        input.media({ toggle: 'playpause', next: 'next', prev: 'prev' }[step.cmd || 'toggle']);
      }
      break;
    }
    default:
      throw new Error('Unknown step type: ' + step.type);
  }
}

function splitArgs(s) {
  return (String(s).match(/"[^"]*"|\S+/g) || []).map((a) => a.replace(/^"|"$/g, ''));
}

async function runSteps(steps, ctx) {
  for (const step of steps || []) await runStep(step, ctx);
}

module.exports = { runSteps };
