'use strict';

/**
 * Minimal Barky UDP relay for cellular / CGNAT.
 * Phones register by deviceId; media/control packets are forwarded as raw walkie bytes.
 *
 * Wire:
 *   REG 0xF0 | idLen:u8 | id utf8
 *   FWD 0xF1 | destIdLen:u8 | destId utf8 | raw walkie payload...
 *   ACK 0xF2 | 0x01 (optional response to REG)
 */

const dgram = require('dgram');

const PORT = parseInt(process.env.BARKY_RELAY_PORT || '39000', 10);
const HOST = process.env.BARKY_RELAY_HOST || '0.0.0.0';
const STALE_MS = parseInt(process.env.BARKY_RELAY_STALE_MS || String(5 * 60 * 1000), 10);

/** @type {Map<string, { address: string, port: number, at: number }>} */
const table = new Map();

const sock = dgram.createSocket('udp4');

function prune() {
  const now = Date.now();
  for (const [id, e] of table) {
    if (now - e.at > STALE_MS) table.delete(id);
  }
}

setInterval(prune, 30_000).unref();

sock.on('message', (msg, rinfo) => {
  if (!msg || msg.length < 2) return;
  const type = msg[0];
  try {
    if (type === 0xf0) {
      // REG
      const idLen = msg[1];
      if (idLen < 1 || msg.length < 2 + idLen) return;
      const id = msg.subarray(2, 2 + idLen).toString('utf8');
      if (!id || id.length > 128) return;
      table.set(id, { address: rinfo.address, port: rinfo.port, at: Date.now() });
      const ack = Buffer.from([0xf2, 0x01]);
      sock.send(ack, rinfo.port, rinfo.address);
      if (process.env.BARKY_RELAY_LOG === '1') {
        console.log('[relay] REG', id, `${rinfo.address}:${rinfo.port}`, 'peers=', table.size);
      }
      return;
    }
    if (type === 0xf1) {
      // FWD
      const idLen = msg[1];
      if (idLen < 1 || msg.length < 2 + idLen + 1) return;
      const destId = msg.subarray(2, 2 + idLen).toString('utf8');
      const payload = msg.subarray(2 + idLen);
      const dest = table.get(destId);
      if (!dest) {
        if (process.env.BARKY_RELAY_LOG === '1') {
          console.log('[relay] FWD miss', destId, 'from', rinfo.address);
        }
        return;
      }
      dest.at = Date.now();
      // Refresh sender if they sent FWD (also keeps path warm)
      // (sender should REG periodically; optional)
      sock.send(payload, dest.port, dest.address);
      return;
    }
  } catch (e) {
    console.error('[relay] error', e && e.message ? e.message : e);
  }
});

sock.on('listening', () => {
  const a = sock.address();
  console.log(`[barky-udp-relay] listening ${a.address}:${a.port} peers=${table.size}`);
});

sock.on('error', (err) => {
  console.error('[barky-udp-relay] socket error', err);
  process.exit(1);
});

sock.bind(PORT, HOST);

// Lightweight health over the same process via HTTP on RELAY_HEALTH_PORT
const healthPort = parseInt(process.env.BARKY_RELAY_HEALTH_PORT || '39001', 10);
if (healthPort > 0) {
  const http = require('http');
  http
    .createServer((req, res) => {
      if (req.url === '/health' || req.url === '/') {
        const body = JSON.stringify({
          ok: true,
          service: 'barky-udp-relay',
          peers: table.size,
          port: PORT,
        });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(body);
        return;
      }
      res.writeHead(404);
      res.end();
    })
    .listen(healthPort, '0.0.0.0', () => {
      console.log(`[barky-udp-relay] health http://0.0.0.0:${healthPort}/health`);
    });
}
