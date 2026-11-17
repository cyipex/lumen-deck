// The tablet app that came with this Lumen Deck (the installer puts it in tablet/, with version.json).
// A connected tablet with an older version is offered it and downloads it over the encrypted connection,
// so updating the tablet needs no cable.
const fs = require('fs');
const path = require('path');
const paths = require('./paths');

let cached = null; // { code, sha256, size, buf }

function info() {
  if (cached) return cached;
  try {
    const v = JSON.parse(fs.readFileSync(path.join(paths.TABLET, 'version.json'), 'utf8'));
    const buf = fs.readFileSync(path.join(paths.TABLET, 'lumen-desk.apk'));
    if (!Number.isInteger(v.code) || buf.length !== v.size) return null;
    cached = { code: v.code, sha256: v.sha256, size: v.size, buf };
  } catch {
    return null;
  }
  return cached;
}

// Called with the version the tablet runs: the offer to send it, or null when it is up to date.
function offerFor(code) {
  const a = info();
  return a && Number(code) < a.code ? { t: 'update', code: a.code, size: a.size } : null;
}

module.exports = { info, offerFor };
