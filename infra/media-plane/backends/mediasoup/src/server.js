'use strict';

/**
 * Barky media-plane entry (mediasoup backend).
 * HTTP health + WebSocket/HTTP signaling. No mediasoup API leaks to clients —
 * only contract JSON from infra/media-plane/contract/.
 */

const http = require('http');
const { WebSocketServer } = require('ws');
const path = require('path');
const { config } = require('./config');
const { MediasoupHandler } = require('./mediasoup-handler');

function loadBuildHealth() {
  const candidates = [
    path.join(__dirname, '..', '..', '..', 'shared', 'health.js'),
    path.join(__dirname, 'health.js'),
  ];
  for (const p of candidates) {
    try {
      return require(p).buildHealth;
    } catch {
      /* try next */
    }
  }
  return ({ backend, startedAtMs, rooms, peers, version }) => ({
    ok: true,
    backend: backend || 'unknown',
    uptimeSec: Math.floor((Date.now() - (startedAtMs || Date.now())) / 1000),
    rooms: Number(rooms) || 0,
    peers: Number(peers) || 0,
    ...(version ? { version } : {}),
  });
}

const buildHealth = loadBuildHealth();

const startedAtMs = Date.now();
const handler = new MediasoupHandler();

function log(...args) {
  if (config.logLevel === 'none') return;
  console.log('[media-plane]', ...args);
}

function sendJson(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(data),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  });
  res.end(data);
}

function errorPayload(e) {
  return {
    ok: false,
    error: {
      code: e.code || 'INTERNAL',
      message: e.message || String(e),
    },
  };
}

/**
 * Dispatch one contract message. Returns reply object (without envelope wrapping).
 */
async function dispatch(msg, session) {
  const type = msg.type;
  const roomId = msg.roomId ?? session.roomId;
  const peerId = msg.peerId ?? session.peerId;
  const payload = msg.payload || {};

  switch (type) {
    case 'ping':
      return { type: 'pong', ok: true, payload: { t: Date.now() } };

    case 'getIceServers': {
      const r = handler.getIceServers();
      return { type: 'iceServersResult', ok: true, payload: r };
    }

    case 'join': {
      if (!roomId || !peerId) {
        throw Object.assign(new Error('roomId and peerId required'), {
          code: 'INVALID',
        });
      }
      session.roomId = roomId;
      session.peerId = peerId;
      const r = await handler.joinRoom({
        roomId,
        peerId,
        notify: session.notify,
      });
      return {
        type: 'joinResult',
        ok: true,
        payload: {
          rtpCapabilities: r.rtpCapabilities,
          peers: r.peers,
          iceServers: r.iceServers,
        },
      };
    }

    case 'getRouterRtpCapabilities': {
      const caps = handler.getRouterRtpCapabilities(roomId);
      if (!caps) {
        throw Object.assign(new Error('room not found'), {
          code: 'ROOM_NOT_FOUND',
        });
      }
      return {
        type: 'routerRtpCapabilities',
        ok: true,
        payload: { rtpCapabilities: caps },
      };
    }

    case 'createTransport': {
      const r = await handler.createTransport({
        roomId,
        peerId,
        direction: payload.direction,
      });
      return { type: 'createTransportResult', ok: true, payload: r };
    }

    case 'connectTransport': {
      const r = await handler.connectTransport({
        roomId,
        peerId,
        transportId: payload.transportId,
        dtlsParameters: payload.dtlsParameters,
      });
      return { type: 'connectTransportResult', ok: true, payload: r };
    }

    case 'produce': {
      const r = await handler.produce({
        roomId,
        peerId,
        transportId: payload.transportId,
        kind: payload.kind,
        rtpParameters: payload.rtpParameters,
        appData: payload.appData,
      });
      return { type: 'produceResult', ok: true, payload: r };
    }

    case 'consume': {
      const r = await handler.consume({
        roomId,
        peerId,
        producerId: payload.producerId,
        rtpCapabilities: payload.rtpCapabilities,
        transportId: payload.transportId,
      });
      return { type: 'consumeResult', ok: true, payload: r };
    }

    case 'closeProducer': {
      const r = await handler.closeProducer({
        roomId,
        peerId,
        producerId: payload.producerId,
      });
      return { type: 'closeProducerResult', ok: true, payload: r };
    }

    case 'restartIce': {
      const r = await handler.restartIce({
        roomId,
        peerId,
        transportId: payload.transportId,
      });
      return { type: 'restartIceResult', ok: true, payload: r };
    }

    case 'leave': {
      const r = await handler.leaveRoom({ roomId, peerId });
      session.roomId = null;
      session.peerId = null;
      return { type: 'leaveResult', ok: true, payload: r };
    }

    default:
      throw Object.assign(new Error(`unknown type: ${type}`), {
        code: 'INVALID',
      });
  }
}

function wrapReply(msg, result) {
  return {
    v: 1,
    id: msg.id,
    type: result.type,
    ok: result.ok !== false,
    payload: result.payload,
  };
}

function wrapError(msg, e) {
  const ep = errorPayload(e);
  return {
    v: 1,
    id: msg?.id,
    type: 'error',
    ok: false,
    error: ep.error,
  };
}

async function main() {
  await handler.init();

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);

    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'Content-Type',
        'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      });
      res.end();
      return;
    }

    if (req.method === 'GET' && (url.pathname === '/health' || url.pathname === '/')) {
      const s = handler.stats();
      sendJson(
        res,
        200,
        buildHealth({
          backend: config.backendName,
          startedAtMs,
          rooms: s.rooms,
          peers: s.peers,
          version: config.version,
        })
      );
      return;
    }

    if (req.method === 'POST' && url.pathname === '/v1/signal') {
      let body = '';
      for await (const chunk of req) {
        body += chunk;
        if (body.length > 2_000_000) {
          sendJson(res, 413, errorPayload(Object.assign(new Error('body too large'), { code: 'INVALID' })));
          return;
        }
      }
      let msg;
      try {
        msg = JSON.parse(body || '{}');
      } catch {
        sendJson(res, 400, errorPayload(Object.assign(new Error('invalid JSON'), { code: 'INVALID' })));
        return;
      }

      // HTTP path cannot push events; notify is a no-op
      const session = {
        roomId: msg.roomId || null,
        peerId: msg.peerId || null,
        notify: () => {},
      };

      try {
        const result = await dispatch(msg, session);
        sendJson(res, 200, wrapReply(msg, result));
      } catch (e) {
        sendJson(res, 400, wrapError(msg, e));
      }
      return;
    }

    sendJson(res, 404, { ok: false, error: { code: 'INVALID', message: 'not found' } });
  });

  const wss = new WebSocketServer({ server, path: '/ws' });

  wss.on('connection', (ws) => {
    const session = {
      roomId: null,
      peerId: null,
      notify: (eventMsg) => {
        if (ws.readyState === ws.OPEN) {
          ws.send(JSON.stringify(eventMsg));
        }
      },
    };

    ws.on('message', async (data) => {
      let msg;
      try {
        msg = JSON.parse(String(data));
      } catch {
        ws.send(JSON.stringify(wrapError(null, Object.assign(new Error('invalid JSON'), { code: 'INVALID' }))));
        return;
      }

      try {
        const result = await dispatch(msg, session);
        ws.send(JSON.stringify(wrapReply(msg, result)));
      } catch (e) {
        log('dispatch error', e.code || e.message);
        ws.send(JSON.stringify(wrapError(msg, e)));
      }
    });

    ws.on('close', () => {
      if (session.roomId && session.peerId) {
        handler.onDisconnect(session.roomId, session.peerId);
      }
    });
  });

  server.listen(config.port, config.host, () => {
    log(
      `backend=${config.backendName} listening http://${config.host}:${config.port}  ws://…/ws`
    );
    log(
      `RTC ports ${config.mediasoup.worker.rtcMinPort}-${config.mediasoup.worker.rtcMaxPort}` +
        (config.mediasoup.webRtcTransport.listenIps[0].announcedIp
          ? ` announcedIp=${config.mediasoup.webRtcTransport.listenIps[0].announcedIp}`
          : ' (set MEDIASOUP_ANNOUNCED_IP for remote clients)')
    );
    log(`ICE servers configured: ${config.iceServers.length}`);
  });
}

main().catch((e) => {
  console.error('[media-plane] fatal', e);
  process.exit(1);
});
