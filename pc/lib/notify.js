// Notifications from the PC to the tablet. Windows stores every notification (Discord, Teams, WhatsApp, Windows…)
// in the local database wpndatabase.db; we read only new notifications from it, read-only.
const path = require('path');
const { EventEmitter } = require('events');

const DB = path.join(process.env.LOCALAPPDATA || '', 'Microsoft', 'Windows', 'Notifications', 'wpndatabase.db');
const events = new EventEmitter();

let sqlite = null;
try {
  // Built-in SQLite in Node 22.5+; we do not want the experimental-feature warning in the log.
  const emit = process.emitWarning;
  process.emitWarning = () => {};
  sqlite = require('node:sqlite');
  process.emitWarning = emit;
} catch {}

let db = null;
let lastId = null;
let timer = null;
let appNames = () => [];

function open() {
  if (db) return db;
  if (!sqlite) throw new Error('This Node version has no SQLite');
  db = new sqlite.DatabaseSync(DB, { readOnly: true });
  return db;
}

const ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
function decode(s) {
  return String(s)
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/<[^>]+>/g, '')
    .replace(/&(#x?[0-9a-f]+|\w+);/gi, (m, e) => {
      if (e[0] === '#') return String.fromCodePoint(parseInt(e[1] === 'x' || e[1] === 'X' ? e.slice(2) : e.slice(1), e[1] === 'x' || e[1] === 'X' ? 16 : 10));
      return ENT[e] ?? m;
    })
    .trim();
}

// A nice app name: from the Start menu list (same AppID), otherwise from the identifier.
function appName(primaryId) {
  const hit = appNames().find((a) => a.id && a.id.toLowerCase() === primaryId.toLowerCase());
  if (hit) return hit.name;
  const tail = primaryId.split('!').pop().split('.').filter(Boolean).pop() || primaryId;
  return tail.replace(/_\w+$/, '');
}

function parse(row) {
  const xml = Buffer.from(row.Payload || []).toString('utf8');
  const texts = [...xml.matchAll(/<text\b[^>]*>([\s\S]*?)<\/text>/g)].map((m) => decode(m[1])).filter(Boolean);
  if (!texts.length) return null;
  return {
    id: Number(row.Id),
    handler: row.PrimaryId,
    app: appName(row.PrimaryId),
    title: texts[0].slice(0, 120),
    body: texts.slice(1).join(' · ').slice(0, 300)
  };
}

function poll(settings) {
  try {
    const d = open();
    if (lastId === null) {
      // On start nothing old is sent, only what arrives from now on.
      lastId = Number(d.prepare('SELECT IFNULL(MAX(Id), 0) AS m FROM Notification').get().m);
      return;
    }
    const rows = d.prepare(
      `SELECT n.Id AS Id, h.PrimaryId AS PrimaryId, n.Payload AS Payload
       FROM Notification n JOIN NotificationHandler h ON n.HandlerId = h.RecordId
       WHERE n.Type = 'toast' AND n.Id > ? ORDER BY n.Id LIMIT 20`
    ).all(lastId);
    for (const r of rows) {
      lastId = Math.max(lastId, Number(r.Id));
      const s = settings();
      if (!s.enabled || s.muted.includes(r.PrimaryId)) continue;
      const n = parse(r);
      if (n) events.emit('notify', n);
    }
  } catch (e) {
    // Windows may lock the database for a moment; we try again next round.
    db = null;
  }
}

// settings(): { enabled, muted: [PrimaryId…] } – read for every notification, so changes apply immediately.
function start(settings, names) {
  if (timer) return;
  if (names) appNames = names;
  poll(settings);
  timer = setInterval(() => poll(settings), 1500);
}

function stop() {
  clearInterval(timer);
  timer = null;
  lastId = null;
}

// Apps that have ever sent a notification (for the picker in settings).
function knownApps() {
  const rows = open().prepare(
    `SELECT DISTINCT h.PrimaryId AS PrimaryId FROM NotificationHandler h
     JOIN Notification n ON n.HandlerId = h.RecordId WHERE n.Type = 'toast'`
  ).all();
  return rows.map((r) => ({ id: r.PrimaryId, name: appName(r.PrimaryId) })).sort((a, b) => a.name.localeCompare(b.name));
}

module.exports = { start, stop, knownApps, events };
