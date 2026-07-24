'use strict';

const path = require('path');
const fs = require('fs');

// Load backends/mediasoup/.env if present (does not override existing process.env)
const envPath = path.join(__dirname, '..', '.env');
if (fs.existsSync(envPath)) {
  require('dotenv').config({ path: envPath });
} else {
  require('dotenv').config();
}

function intEnv(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) ? n : fallback;
}

function parseIceServers() {
  const urlsRaw = (process.env.TURN_URLS || '').trim();
  if (!urlsRaw) return [];

  const urls = urlsRaw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  if (urls.length === 0) return [];

  const user = (process.env.TURN_USER || '').trim();
  const pass = (process.env.TURN_PASS || '').trim();

  const stunUrls = urls.filter((u) => u.toLowerCase().startsWith('stun:'));
  const turnUrls = urls.filter((u) => !u.toLowerCase().startsWith('stun:'));

  const servers = [];
  if (stunUrls.length) {
    servers.push({ urls: stunUrls });
  }
  if (turnUrls.length) {
    const entry = { urls: turnUrls };
    if (user) entry.username = user;
    if (pass) entry.credential = pass;
    servers.push(entry);
  }
  // If only mixed and we didn't split usefully, still return one block
  if (servers.length === 0) {
    const entry = { urls };
    if (user) entry.username = user;
    if (pass) entry.credential = pass;
    servers.push(entry);
  }
  return servers;
}

const mediaCodecs = [
  {
    kind: 'audio',
    mimeType: 'audio/opus',
    clockRate: 48000,
    channels: 2,
  },
  {
    kind: 'video',
    mimeType: 'video/VP8',
    clockRate: 90000,
    parameters: {
      'x-google-start-bitrate': 1000,
    },
  },
  {
    kind: 'video',
    mimeType: 'video/H264',
    clockRate: 90000,
    parameters: {
      'packetization-mode': 1,
      'profile-level-id': '42e01f',
      'level-asymmetry-allowed': 1,
    },
  },
];

const config = {
  host: process.env.HOST || '0.0.0.0',
  port: intEnv('PORT', 3000),
  logLevel: process.env.LOG_LEVEL || 'warn',
  backendName: 'mediasoup',
  version: '1.0.0',
  iceServers: parseIceServers(),
  mediasoup: {
    numWorkers: Math.max(1, intEnv('MEDIASOUP_NUM_WORKERS', 1)),
    worker: {
      logLevel: process.env.LOG_LEVEL === 'debug' ? 'debug' : 'warn',
      logTags: ['info', 'ice', 'dtls', 'rtp', 'srtp', 'rtcp'],
      rtcMinPort: intEnv('MEDIASOUP_MIN_PORT', 40000),
      rtcMaxPort: intEnv('MEDIASOUP_MAX_PORT', 40100),
    },
    router: {
      mediaCodecs,
    },
    webRtcTransport: {
      listenIps: [
        {
          ip: '0.0.0.0',
          announcedIp: (process.env.MEDIASOUP_ANNOUNCED_IP || '').trim() || undefined,
        },
      ],
      enableUdp: true,
      enableTcp: true,
      preferUdp: true,
      initialAvailableOutgoingBitrate: 1_000_000,
      maxIncomingBitrate: 3_000_000,
    },
  },
};

module.exports = { config, parseIceServers };
