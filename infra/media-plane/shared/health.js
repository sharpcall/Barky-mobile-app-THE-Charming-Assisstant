/**
 * Shared health payload helper (backend-agnostic).
 * Each backend may require() this or re-implement the same shape.
 *
 * GET /health response contract:
 * {
 *   ok: true,
 *   backend: "mediasoup" | "rustrtc" | string,
 *   uptimeSec: number,
 *   rooms: number,
 *   peers: number,
 *   version?: string
 * }
 */

function buildHealth({ backend, startedAtMs, rooms, peers, version }) {
  const now = Date.now();
  const start = typeof startedAtMs === 'number' ? startedAtMs : now;
  return {
    ok: true,
    backend: backend || 'unknown',
    uptimeSec: Math.floor((now - start) / 1000),
    rooms: Number(rooms) || 0,
    peers: Number(peers) || 0,
    ...(version ? { version } : {}),
  };
}

module.exports = { buildHealth };
