# mediasoup backend

Implements the Barky [media-plane contract](../../contract/MEDIA_BACKEND.md) with **mediasoup** (Node).

## Run

```bash
cd backends/mediasoup
cp .env.example .env
# set MEDIASOUP_ANNOUNCED_IP, TURN_*, ports
npm install
npm start
```

| Endpoint | Purpose |
|----------|---------|
| `GET /health` | Liveness + room/peer counts |
| `WS /ws` | Signaling (preferred) |
| `POST /v1/signal` | One-shot signaling (no push events) |

Default bind: `0.0.0.0:3000`. RTC UDP: `40000–40100` (`MEDIASOUP_MIN_PORT` / `MEDIASOUP_MAX_PORT`).

## Layout

| File | Role |
|------|------|
| `src/server.js` | HTTP + WebSocket; contract dispatch only |
| `src/mediasoup-handler.js` | **Only** place that imports `mediasoup` |
| `src/config.js` | Env: ports, TURN, announced IP, codecs |
| `.env.example` | Template for secrets / deploy |

## Firewall

Open:

- TCP `PORT` (signaling)
- UDP `MEDIASOUP_MIN_PORT`–`MEDIASOUP_MAX_PORT` (media)
- coturn ports separately if TURN is on another host

## Docker

See [../../docker/Dockerfile.mediasoup](../../docker/Dockerfile.mediasoup). Prefer multi-arch builds for Oracle Ampere (linux/arm64).

## Swap out

Clients keep the same JSON messages. Deploy [../rustrtc](../rustrtc) (when implemented) and stop this process — see root [media-plane README](../../README.md).
