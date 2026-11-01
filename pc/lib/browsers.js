// Installed (Chromium) browsers and their profiles, so a page can be opened under a specific account.
const fs = require('fs');
const path = require('path');

const LOCAL = process.env.LOCALAPPDATA || '';
const PF = process.env.ProgramFiles || 'C:\\Program Files';
const PF86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';

const KNOWN = [
  {
    id: 'chrome', name: 'Chrome',
    data: path.join(LOCAL, 'Google', 'Chrome', 'User Data'),
    exe: [path.join(PF, 'Google', 'Chrome', 'Application', 'chrome.exe'), path.join(PF86, 'Google', 'Chrome', 'Application', 'chrome.exe'), path.join(LOCAL, 'Google', 'Chrome', 'Application', 'chrome.exe')]
  },
  {
    id: 'edge', name: 'Edge',
    data: path.join(LOCAL, 'Microsoft', 'Edge', 'User Data'),
    exe: [path.join(PF86, 'Microsoft', 'Edge', 'Application', 'msedge.exe'), path.join(PF, 'Microsoft', 'Edge', 'Application', 'msedge.exe')]
  },
  {
    id: 'brave', name: 'Brave',
    data: path.join(LOCAL, 'BraveSoftware', 'Brave-Browser', 'User Data'),
    exe: [path.join(PF, 'BraveSoftware', 'Brave-Browser', 'Application', 'brave.exe'), path.join(LOCAL, 'BraveSoftware', 'Brave-Browser', 'Application', 'brave.exe')]
  }
];

function list() {
  const out = [];
  for (const b of KNOWN) {
    const exe = b.exe.find((p) => fs.existsSync(p));
    if (!exe) continue;
    let profiles = [];
    try {
      // Local State may contain empty keys too, so plain JSON.parse and nothing stricter.
      const state = JSON.parse(fs.readFileSync(path.join(b.data, 'Local State'), 'utf8'));
      const cache = (state.profile && state.profile.info_cache) || {};
      profiles = Object.entries(cache).map(([dir, p]) => ({ dir, name: p.name || dir, email: p.user_name || '' }));
    } catch {}
    if (!profiles.length) profiles = [{ dir: 'Default', name: 'Default', email: '' }];
    out.push({ id: b.id, name: b.name, exe, profiles });
  }
  return out;
}

// "chrome|Profile 1" -> { exe, args }
function launch(choice, url) {
  const [id, dir] = String(choice || '').split('|');
  const b = list().find((x) => x.id === id);
  if (!b) throw new Error('Browser not found: ' + id);
  if (!b.profiles.some((p) => p.dir === dir)) throw new Error('Browser profile not found: ' + dir);
  return { exe: b.exe, args: ['--profile-directory=' + dir, url] };
}

module.exports = { list, launch };
