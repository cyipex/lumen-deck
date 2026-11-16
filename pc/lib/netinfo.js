// How to reach this PC, for the settings window (only this PC sees it): its IPv4 addresses per adapter,
// the Wi-Fi it is on and whether Windows treats the network as private or public.
const os = require('os');
const { execFile } = require('child_process');

function addresses() {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family === 'IPv4' && !a.internal && !a.address.startsWith('169.254.')) out.push({ adapter: name, ip: a.address });
    }
  }
  return out;
}

const run = (cmd, args) => new Promise((resolve) => {
  execFile(cmd, args, { windowsHide: true, timeout: 6000 }, (err, out) => resolve(err ? '' : String(out)));
});

async function wifi() {
  // "SSID : name" in the Wi-Fi adapter's block; BSSID lines are skipped.
  const out = await run('netsh.exe', ['wlan', 'show', 'interfaces']);
  const m = /^\s*SSID\s*:\s*(.+?)\s*$/m.exec(out);
  return m ? m[1] : null;
}

async function profiles() {
  const out = await run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    'Get-NetConnectionProfile | Select-Object InterfaceAlias, NetworkCategory, Name | ConvertTo-Json -Compress']);
  try {
    const j = JSON.parse(out);
    return (Array.isArray(j) ? j : [j]).map((p) => ({
      adapter: String(p.InterfaceAlias || ''),
      network: String(p.Name || ''),
      // 0 = Public, 1 = Private, 2 = Domain (numbers in older PowerShell, names in newer).
      category: ({ 0: 'Public', 1: 'Private', 2: 'Domain' })[p.NetworkCategory] || String(p.NetworkCategory || '')
    }));
  } catch {
    return [];
  }
}

async function info() {
  const [ssid, nets] = await Promise.all([wifi(), profiles()]);
  return { addresses: addresses(), wifi: ssid, networks: nets };
}

module.exports = { info, addresses };
