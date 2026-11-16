// Spotify through the official Web API (Premium required). PKCE login, no client secret.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { EventEmitter } = require('events');

const SCOPES = 'user-read-playback-state user-modify-playback-state user-read-currently-playing playlist-read-private playlist-read-collaborative user-library-read';
const API = 'https://api.spotify.com/v1';

const events = new EventEmitter();
let dataDir = '';
let clientId = '';
let redirectUri = '';
let tokens = null; // { access, refresh, expires }
let pending = null; // { verifier, state }
let playback = null;
let lastError = '';
let pollTimer = null;
let wanted = false;

function tokenFile() {
  return path.join(dataDir, 'spotify-token.json');
}

function init(opts) {
  dataDir = opts.dataDir;
  redirectUri = opts.redirectUri;
  try { tokens = JSON.parse(fs.readFileSync(tokenFile(), 'utf8')); } catch { tokens = null; }
}

function setClientId(id) {
  if (id !== clientId) {
    clientId = id || '';
  }
}

function saveTokens() {
  fs.mkdirSync(dataDir, { recursive: true });
  fs.writeFileSync(tokenFile(), JSON.stringify(tokens));
}

function b64url(buf) {
  return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function loginUrl() {
  if (!clientId) throw new Error('Najprv zadaj Client ID zo Spotify Developer Dashboard');
  const verifier = b64url(crypto.randomBytes(48));
  const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
  const state = b64url(crypto.randomBytes(12));
  pending = { verifier, state };
  const q = new URLSearchParams({
    client_id: clientId,
    response_type: 'code',
    redirect_uri: redirectUri,
    code_challenge_method: 'S256',
    code_challenge: challenge,
    scope: SCOPES,
    state
  });
  return 'https://accounts.spotify.com/authorize?' + q;
}

async function tokenRequest(params) {
  const res = await fetch('https://accounts.spotify.com/api/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: clientId, ...params })
  });
  const j = await res.json();
  if (!res.ok) throw new Error(j.error_description || j.error || 'Spotify login failed');
  tokens = {
    access: j.access_token,
    refresh: j.refresh_token || (tokens && tokens.refresh),
    expires: Date.now() + (j.expires_in - 60) * 1000,
    scope: j.scope || (tokens && tokens.scope) || ''
  };
  saveTokens();
}

async function finishLogin(code, state) {
  if (!pending || state !== pending.state) throw new Error('Invalid login, please try again');
  await tokenRequest({ grant_type: 'authorization_code', code, redirect_uri: redirectUri, code_verifier: pending.verifier });
  pending = null;
  lastError = '';
  events.emit('status', status());
  poll();
}

async function accessToken() {
  if (!tokens) return null;
  if (Date.now() > tokens.expires) await tokenRequest({ grant_type: 'refresh_token', refresh_token: tokens.refresh });
  return tokens.access;
}

async function api(method, url, body) {
  const t = await accessToken();
  if (!t) throw new Error('Spotify is not logged in');
  const res = await fetch(API + url, {
    method,
    headers: { Authorization: 'Bearer ' + t, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined
  });
  if (res.status === 204) return null;
  const text = await res.text();
  let j = null;
  try { j = text ? JSON.parse(text) : null; } catch {}
  if (!res.ok) {
    const msg = (j && j.error && j.error.message) || res.statusText;
    const err = new Error(msg);
    err.status = res.status;
    err.reason = j && j.error && j.error.reason;
    throw err;
  }
  return j;
}

function simplify(p) {
  if (!p || !p.item) return { active: false };
  const it = p.item;
  const images = (it.album && it.album.images) || (it.images) || [];
  return {
    active: true,
    playing: !!p.is_playing,
    title: it.name,
    artist: (it.artists || []).map((a) => a.name).join(', ') || (it.show && it.show.name) || '',
    image: (images.find((i) => i.width && i.width <= 320) || images[0] || {}).url || '',
    progress: p.progress_ms || 0,
    duration: it.duration_ms || 0,
    volume: p.device ? p.device.volume_percent : null,
    shuffle: !!p.shuffle_state,
    repeat: p.repeat_state || 'off',
    device: p.device ? p.device.name : '',
    at: Date.now()
  };
}

async function poll() {
  clearTimeout(pollTimer);
  if (!wanted || !tokens) return;
  try {
    const p = await api('GET', '/me/player?additional_types=episode');
    const next = simplify(p);
    const changed = JSON.stringify({ ...next, at: 0, progress: 0 }) !== JSON.stringify({ ...(playback || {}), at: 0, progress: 0 });
    const jumped = playback && next.active && Math.abs((next.progress - playback.progress) - (next.at - playback.at) * (playback.playing ? 1 : 0)) > 2500;
    playback = next;
    if (changed || jumped) events.emit('playback', playback);
    lastError = '';
  } catch (e) {
    lastError = e.message;
  }
  pollTimer = setTimeout(poll, 1000);
}

// Turns regular polling of what is playing on/off (only while a tablet or settings window is connected).
function setActive(on) {
  if (on === wanted) return;
  wanted = on;
  if (on) poll();
  else clearTimeout(pollTimer);
}

function bump(fields) {
  if (!playback) return;
  playback = { ...playback, ...fields, at: Date.now() };
  events.emit('playback', playback);
}

// Commands from the tablet. Returns false if Spotify is not connected (media keys are used then).
async function control(cmd, value) {
  if (!tokens) return false;
  try {
    switch (cmd) {
      case 'toggle':
        if (playback && playback.playing) { bump({ playing: false }); await api('PUT', '/me/player/pause'); }
        else { bump({ playing: true }); await api('PUT', '/me/player/play'); }
        break;
      case 'play': bump({ playing: true }); await api('PUT', '/me/player/play'); break;
      case 'pause': bump({ playing: false }); await api('PUT', '/me/player/pause'); break;
      case 'next': await api('POST', '/me/player/next'); break;
      case 'prev': await api('POST', '/me/player/previous'); break;
      case 'seek': bump({ progress: Math.round(value) }); await api('PUT', '/me/player/seek?position_ms=' + Math.round(value)); break;
      case 'volume': bump({ volume: Math.round(value) }); await api('PUT', '/me/player/volume?volume_percent=' + Math.round(value)); break;
      case 'shuffle': bump({ shuffle: !playback.shuffle }); await api('PUT', '/me/player/shuffle?state=' + !playback.shuffle); break;
      case 'repeat': {
        const nextMode = playback.repeat === 'off' ? 'context' : playback.repeat === 'context' ? 'track' : 'off';
        bump({ repeat: nextMode });
        await api('PUT', '/me/player/repeat?state=' + nextMode);
        break;
      }
      case 'uri': {
        const uri = String(value || '');
        const body = uri.includes(':track:') ? { uris: [uri] } : { context_uri: uri };
        await api('PUT', '/me/player/play', body);
        break;
      }
      default: throw new Error('Unknown Spotify command: ' + cmd);
    }
    setTimeout(poll, 350);
    return true;
  } catch (e) {
    lastError = e.reason === 'NO_ACTIVE_DEVICE' ? 'Spotify is not playing anything, open it on the PC' : e.message;
    events.emit('status', status());
    return e.reason === 'NO_ACTIVE_DEVICE' ? false : true;
  }
}

async function playlists() {
  const j = await api('GET', '/me/playlists?limit=50');
  return (j.items || []).filter(Boolean).map((p) => ({ name: p.name, uri: p.uri, image: ((p.images || [])[0] || {}).url || '' }));
}

// ---------- library for the tablet ----------

function needsRelogin() {
  return !!tokens && !String(tokens.scope || '').includes('user-library-read');
}

const RELOGIN = 'The library needs a new permission: open Lumen Desk → Spotify on the PC, log out and log in again.';

function track(t) {
  if (!t || !t.uri) return null;
  const images = (t.album && t.album.images) || [];
  return {
    uri: t.uri,
    name: t.name,
    artist: (t.artists || []).map((a) => a.name).join(', '),
    image: (images[images.length - 1] || images[0] || {}).url || '',
    duration: t.duration_ms || 0
  };
}

// The list on the left: Liked Songs + playlists.
async function sources() {
  if (needsRelogin()) throw new Error(RELOGIN);
  const [liked, lists] = await Promise.all([
    api('GET', '/me/tracks?limit=1'),
    api('GET', '/me/playlists?limit=50')
  ]);
  return [{ id: 'liked', name: 'Liked Songs', image: '', count: liked.total || 0 }].concat(
    (lists.items || []).filter(Boolean).map((p) => ({
      id: p.id,
      uri: p.uri,
      name: p.name,
      image: ((p.images || []).slice(-1)[0] || {}).url || '',
      count: (p.items && p.items.total) || (p.tracks && p.tracks.total) || 0
    }))
  );
}

// Tracks of one source in pages of 50 (the tablet loads more while scrolling).
async function tracks(source, offset = 0) {
  offset = Math.max(0, Number(offset) || 0);
  let j;
  if (source === 'liked') j = await api('GET', `/me/tracks?limit=50&offset=${offset}`);
  else {
    const id = encodeURIComponent(String(source));
    try {
      j = await api('GET', `/playlists/${id}/items?limit=50&offset=${offset}`);
    } catch (e) {
      // In development mode Spotify does not let apps read other people's (only followed) playlists, but they can be played.
      if (e.status === 403) return { items: [], total: 0, offset, forbidden: true };
      if (e.status !== 404) throw e;
      j = await api('GET', `/playlists/${id}/tracks?limit=50&offset=${offset}`);
    }
  }
  const items = (j.items || []).map((i) => track(i.track || i.item)).filter(Boolean);
  return { items, total: j.total || items.length, offset };
}

async function search(q) {
  q = String(q || '').trim();
  if (!q) return { items: [], total: 0, offset: 0 };
  const j = await api('GET', `/search?type=track&limit=10&q=${encodeURIComponent(q)}`);
  const items = ((j.tracks && j.tracks.items) || []).map(track).filter(Boolean);
  return { items, total: items.length, offset: 0 };
}

// ---------- starting Spotify on the PC ----------

const { execFile, spawn } = require('child_process');
const run = (cmd, args) => new Promise((resolve) => execFile(cmd, args, { windowsHide: true, timeout: 5000 }, (err, out) => resolve(err ? null : String(out))));
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// The desktop app (classic or Microsoft Store) registers spotify: links; without it, the web player in the browser.
async function launch() {
  const app = (await run('reg.exe', ['query', 'HKCU\\Software\\Classes\\spotify'])) || (await run('reg.exe', ['query', 'HKCR\\spotify']));
  const target = app ? 'spotify:' : 'https://open.spotify.com';
  const p = spawn('cmd.exe', ['/c', 'start', '""', target], { detached: true, stdio: 'ignore', windowsHide: true });
  p.on('error', () => {});
  p.unref();
  return app ? 'app' : 'web';
}

async function devices() {
  const d = await api('GET', '/me/player/devices');
  return (d && d.devices) || [];
}

const pickDevice = (list) => list.find((x) => x.is_active) || list.find((x) => x.type === 'Computer') || list[0];

// A device to play on; when Spotify is not open anywhere it is started on this PC and we wait for it (up to 25 s).
async function ensureDevice() {
  let dev = pickDevice(await devices());
  if (dev) return dev;
  await launch();
  for (let i = 0; i < 25 && !dev; i++) {
    await wait(1000);
    dev = pickDevice(await devices().catch(() => []));
  }
  if (!dev) throw new Error('Spotify did not start in time – try again in a moment');
  return dev;
}

// "Open Spotify" on the tablet, or Play while Spotify is closed: start it and continue where it stopped.
async function open() {
  if (!tokens) return launch();
  const running = (await run('tasklist.exe', ['/FI', 'IMAGENAME eq Spotify.exe', '/NH'])) || '';
  const list = await devices().catch(() => []);
  if (!list.length || !/spotify\.exe/i.test(running)) {
    const dev = await ensureDevice();
    await api('PUT', '/me/player', { device_ids: [dev.id], play: true }).catch(() => {});
  } else {
    await api('PUT', '/me/player', { device_ids: [pickDevice(list).id], play: true }).catch(() => {});
  }
  setTimeout(poll, 800);
  return 'ok';
}

// If nothing is playing, play on the computer (or on the first device Spotify knows); Spotify is started if needed.
async function deviceQuery() {
  if (playback && playback.active) return '';
  const dev = await ensureDevice();
  return '?device_id=' + encodeURIComponent(dev.id);
}

// Plays a track: from a playlist it continues with the playlist's next tracks, otherwise with the list it was picked from.
async function playItem({ source, uri, uris, shuffle }) {
  const q = await deviceQuery();
  let body;
  if (shuffle !== undefined) await api('PUT', '/me/player/shuffle?state=' + !!shuffle + q.replace('?', '&')).catch(() => {});
  if (source && source !== 'liked' && source !== 'search') {
    body = { context_uri: 'spotify:playlist:' + source };
    if (uri) body.offset = { uri };
  }
  else {
    const list = (Array.isArray(uris) ? uris : [uri]).filter((u) => typeof u === 'string' && u.startsWith('spotify:')).slice(0, 100);
    const i = Math.max(0, list.indexOf(uri));
    body = { uris: list.slice(i) };
  }
  bump({ playing: true });
  await api('PUT', '/me/player/play' + q, body);
  setTimeout(poll, 400);
}

function logout() {
  tokens = null;
  playback = null;
  try { fs.unlinkSync(tokenFile()); } catch {}
  events.emit('status', status());
  events.emit('playback', { active: false });
}

function status() {
  return { configured: !!clientId, connected: !!tokens, redirectUri, error: needsRelogin() ? RELOGIN : lastError };
}

function current() {
  return playback || { active: false };
}

module.exports = { init, setClientId, loginUrl, finishLogin, control, playlists, sources, tracks, search, playItem, open, launch, logout, status, current, setActive, events };
