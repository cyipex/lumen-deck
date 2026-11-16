// Files uploaded in the settings window for the tablet: screensaver photos and custom clock fonts.
// Each is stored once under its content hash (data/assets/<id>.<ext>), so the id also lets the tablet
// check that what it received is exactly the file.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const paths = require('./paths');

const DIR = path.join(paths.DATA, 'assets');
const LIMITS = { photo: 12 * 1024 * 1024, font: 4 * 1024 * 1024 };

// What the file really is, from its first bytes (the name or extension the browser sends is not trusted).
function sniff(buf) {
  const hex = buf.subarray(0, 4).toString('hex');
  const ascii = buf.subarray(0, 4).toString('latin1');
  if (hex.startsWith('ffd8ff')) return { kind: 'photo', ext: 'jpg', type: 'image/jpeg' };
  if (hex === '89504e47') return { kind: 'photo', ext: 'png', type: 'image/png' };
  if (ascii === 'RIFF' && buf.subarray(8, 12).toString('latin1') === 'WEBP') return { kind: 'photo', ext: 'webp', type: 'image/webp' };
  if (hex === '00010000' || ascii === 'true') return { kind: 'font', ext: 'ttf', type: 'font/ttf' };
  if (ascii === 'OTTO') return { kind: 'font', ext: 'otf', type: 'font/otf' };
  return null;
}

const idOf = (buf) => crypto.createHash('sha256').update(buf).digest('hex').slice(0, 32);

function save(kind, buf) {
  const t = sniff(buf);
  if (!t || t.kind !== kind) throw new Error(kind === 'font' ? 'This is not a font file (TTF or OTF).' : 'This is not a photo (JPG, PNG or WebP).');
  if (buf.length > LIMITS[kind]) throw new Error(`The file is too big (at most ${LIMITS[kind] / 1024 / 1024} MB).`);
  fs.mkdirSync(DIR, { recursive: true });
  const id = idOf(buf);
  const file = path.join(DIR, `${id}.${t.ext}`);
  if (!fs.existsSync(file)) fs.writeFileSync(file, buf);
  return { id, ext: t.ext, size: buf.length };
}

// The stored file for an id (ids are hex only, so no path tricks), or null.
function find(id) {
  if (!/^[a-f0-9]{32}$/.test(String(id))) return null;
  let names = [];
  try { names = fs.readdirSync(DIR); } catch {}
  const name = names.find((n) => n.startsWith(id + '.'));
  if (!name) return null;
  const file = path.join(DIR, name);
  const buf = fs.readFileSync(file);
  return { file, buf, ...(sniff(buf) || { type: 'application/octet-stream' }) };
}

// Files no setting uses any more are deleted (called after a change).
function prune(used) {
  let names = [];
  try { names = fs.readdirSync(DIR); } catch {}
  for (const n of names) {
    if (!used.has(n.split('.')[0])) {
      try { fs.unlinkSync(path.join(DIR, n)); } catch {}
    }
  }
}

module.exports = { save, find, prune, LIMITS };
