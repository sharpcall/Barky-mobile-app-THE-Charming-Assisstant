'use strict';

/**
 * Minimal Barky UDP relay for cellular / CGNAT.
 *
 * REG  0xF0 | idLen:u8 | id utf8
 * FWD  0xF1 | destIdLen | destId | srcIdLen | srcId | raw walkie payload...
 * ACK  0xF2 | 0x01  (response to REG)
 * DELIVER 0xF3 | srcIdLen | srcId | raw walkie payload...  (hub → recipient)
 *
 * Peers register by deviceId; media/control is forwarded with sender identity
 * so SEE_ACK / LISTEN replies can route back through the hub.
 */

const dgram = require('dgram');

const PORT = parseInt(process.env.BARKY_RELAY_PORT || '39000', 10);
const HOST = process.env.BARKY_RELAY_HOST || '0.0.0.0';
const STALE_MS = parseInt(process.env.BARKY_RELAY_STALE_MS || String(5 * 60 * 1000), 10);
const LOG = process.env.BARKY_RELAY_LOG === '1';

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
      sock.send(Buffer.from([0xf2, 0x01]), rinfo.port, rinfo.address);
      if (LOG) console.log('[relay] REG', id, `${rinfo.address}:${rinfo.port}`, 'peers=', table.size);
      return;
    }
    if (type === 0xf1) {
      // FWD: dest + src + payload
      let off = 1;
      if (msg.length < off + 1) return;
      const destLen = msg[off++];
      if (destLen < 1 || msg.length < off + destLen + 1) return;
      const destId = msg.subarray(off, off + destLen).toString('utf8');
      off += destLen;
      const srcLen = msg[off++];
      if (srcLen < 0 || msg.length < off + srcLen + 1) return;
      const srcId = srcLen > 0 ? msg.subarray(off, off + srcLen).toString('utf8') : '';
      off += srcLen;
      const payload = msg.subarray(off);
      if (!payload.length) return;

      // Refresh sender registration from this datagram path
      if (srcId) {
        table.set(srcId, { address: rinfo.address, port: rinfo.port, at: Date.now() });
      }

      const dest = table.get(destId);
      if (!dest) {
        if (LOG) console.log('[relay] FWD miss dest=', destId, 'from', rinfo.address, 'src=', srcId);
        return;
      }
      dest.at = Date.now();

      // DELIVER envelope so peer can reply via FWD to srcId
      const srcBuf = Buffer.from(srcId || '', 'utf8');
      const deliver = Buffer.alloc(2 + srcBuf.length + payload.length);
      deliver[0] = 0xf3;
      deliver[1] = srcBuf.length;
      srcBuf.copy(deliver, 2);
      payload.copy(deliver, 2 + srcBuf.length);

      sock.send(deliver, dest.port, dest.address);
      if (LOG) {
        console.log(
          '[relay] FWD',
          srcId || '?',
          '→',
          destId,
          'bytes=',
          payload.length,
          'to',
          `${dest.address}:${dest.port}`
        );
      }
      return;
    }
  } catch (e) {
    console.error('[relay] error', e && e.message ? e.message : e);
  }
});

sock.on('listening', () => {
  const a = sock.address();
  console.log(`[barky-udp-relay] listening ${a.address}:${a.port}`);
});

sock.on('error', (err) => {
  console.error('[barky-udp-relay] socket error', err);
  process.exit(1);
});

sock.bind(PORT, HOST);

const healthPort = parseInt(process.env.BARKY_RELAY_HEALTH_PORT || '39001', 10);
if (healthPort > 0) {
  require('http')
    .createServer((req, res) => {
      if (req.url === '/health' || req.url === '/') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, service: 'barky-udp-relay', peers: table.size, port: PORT }));
        return;
      }
      res.writeHead(404);
      res.end();
    })
    .listen(healthPort, '0.0.0.0', () => {
      console.log(`[barky-udp-relay] health :${healthPort}`);
    });
}
