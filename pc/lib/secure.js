// The encrypted connection between the tablet and the PC.
//
// Every connection starts with a fresh key exchange (ECDH P-256), so recorded traffic cannot be decrypted later.
// A paired tablet also mixes in its pairing key (32 random bytes made by the PC when the tablet was allowed):
// only the real tablet and the real PC can then read and write messages, even though the tablet's id is not secret.
// A tablet that is not paired yet gets a 6-digit code, shown on both screens; the user compares them before
// pressing Allow, so nobody in the middle can slip in. All messages are AES-256-GCM with a counter (no replays).
//
// Message after the handshake (binary): [counter: 8 bytes][ciphertext + 16-byte tag].
const crypto = require('crypto');

const hkdf = (ikm, salt, info, len) => Buffer.from(crypto.hkdfSync('sha256', ikm, salt, info, len));

class Session {
  constructor(sendKey, receiveKey) {
    this.tx = sendKey;
    this.rx = receiveKey;
    this.txN = 0n;
    this.rxN = 0n;
  }

  // data: a Buffer, or an object sent as JSON.
  seal(data) {
    const pt = Buffer.isBuffer(data) ? data : Buffer.from(typeof data === 'string' ? data : JSON.stringify(data));
    const n = this.txN++;
    const iv = Buffer.alloc(12);
    iv.writeBigUInt64BE(n, 4);
    const c = crypto.createCipheriv('aes-256-gcm', this.tx, iv);
    const head = Buffer.alloc(8);
    head.writeBigUInt64BE(n);
    return Buffer.concat([head, c.update(pt), c.final(), c.getAuthTag()]);
  }

  // Throws when the message was changed, replayed or made without the key.
  open(buf) {
    if (!Buffer.isBuffer(buf) || buf.length < 24) throw new Error('bad message');
    const n = buf.readBigUInt64BE(0);
    if (n !== this.rxN) throw new Error('bad message order');
    const iv = Buffer.alloc(12);
    iv.writeBigUInt64BE(n, 4);
    const d = crypto.createDecipheriv('aes-256-gcm', this.rx, iv);
    d.setAuthTag(buf.subarray(buf.length - 16));
    const pt = Buffer.concat([d.update(buf.subarray(8, buf.length - 16)), d.final()]);
    this.rxN++;
    return pt;
  }
}

const b64 = (s, len) => {
  const b = Buffer.from(String(s || ''), 'base64');
  if (b.length !== len) throw new Error('bad handshake');
  return b;
};

// hello: { deviceId, epk (65-byte P-256 point), cn (16-byte nonce), pcs: ids of the PCs the tablet holds keys for,
// legacy: it holds a key from before PCs had ids }. Older tablets send only paired: true/false.
// keyFor(deviceId) -> the stored pairing key (Buffer) or null; pcId: this PC's id (sent back so the tablet picks the right key).
function accept(hello, keyFor, pcId) {
  const deviceId = String(hello.deviceId || '');
  if (!deviceId || deviceId.length > 100) throw new Error('bad handshake');
  const epk = b64(hello.epk, 65);
  const cn = b64(hello.cn, 16);
  const ecdh = crypto.createECDH('prime256v1');
  const spk = ecdh.generateKeys();
  const sn = crypto.randomBytes(16);
  const shared = ecdh.computeSecret(epk); // throws for a point that is not on the curve
  const claims = Array.isArray(hello.pcs) ? hello.pcs.map(String).includes(pcId) || !!hello.legacy : !!hello.paired;
  const key = claims ? keyFor(deviceId) : null;
  const salt = crypto.createHash('sha256').update(Buffer.concat([cn, sn, epk, spk, Buffer.from(deviceId)])).digest();
  const ikm = key ? Buffer.concat([shared, key]) : shared;
  const code = String(hkdf(ikm, salt, 'lumen-desk code', 4).readUInt32BE(0) % 1000000).padStart(6, '0');
  return {
    mode: key ? 'paired' : 'pair',
    code,
    session: new Session(hkdf(ikm, salt, 'lumen-desk s2c', 32), hkdf(ikm, salt, 'lumen-desk c2s', 32)),
    reply: { t: 'hello2', spk: spk.toString('base64'), sn: sn.toString('base64'), mode: key ? 'paired' : 'pair', pcId }
  };
}

const newKey = () => crypto.randomBytes(32);
const token = () => crypto.randomBytes(18).toString('base64url');

module.exports = { accept, Session, newKey, token };
