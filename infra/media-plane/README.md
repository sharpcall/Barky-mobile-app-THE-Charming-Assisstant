# Barky media plane

Swappable **SFU / media-plane** for Barky 1:1 (and small-room) WebRTC.  
Clients speak a **backend-agnostic** signaling contract; only one backend process runs at a time.

```
infra/media-plane/
  contract/           # interface + JSON message types (stable)
  backends/
    mediasoup/        # Node + mediasoup (implemented)
    rustrtc/          # stub — same contract, not implemented
  shared/             # health helpers / notes
  docker/             # optional container builds
```

## Design rules

1. **All mediasoup imports live only under `backends/mediasoup/`.**  
   Nothing outside that folder may `require('mediasoup')`.
2. **Clients use messages in `contract/signaling-messages.md`.**  
   They never call mediasoup APIs directly.
3. **ICE/TURN is env-driven** (`TURN_URLS`, `TURN_USER`, `TURN_PASS`).  
   coturn is separate (`infra/oracle-coturn/`). No FreeSWITCH/Asterisk.
4. **Room id** is an arbitrary string (e.g. Barky device-pair key).

## Quick start (mediasoup)

```bash
cd backends/mediasoup
cp .env.example .env
# edit TURN_* and MEDIASOUP_* as needed
npm install
npm start
```

- Signaling + health: `http://0.0.0.0:3000` (override with `PORT`)
- WebSocket: `ws://0.0.0.0:3000/ws`
- Health: `GET /health`
- RTC UDP ports: `MEDIASOUP_MIN_PORT`–`MEDIASOUP_MAX_PORT` (default 40000–40100)

## How backends plug in

| Layer | Responsibility |
|-------|----------------|
| `contract/MEDIA_BACKEND.md` | Logical ops: createTransport, connect, produce, consume, iceServers |
| `contract/signaling-messages.md` | Wire format (JSON) for phones / tools |
| `backends/<name>/` | Implements the contract for one stack |
| Deploy | Run **one** backend image/process; point clients at its `PORT` |

A backend is “compliant” when it accepts the same message types and returns the same shape of answers (transport params, DTLS params, producer/consumer ids, RTP capabilities, ICE server list).

## Swap mediasoup → rustrtc later

See [backends/rustrtc/README.md](backends/rustrtc/README.md). Short version:

1. Implement the same contract under `backends/rustrtc/` (no change to client message types).
2. Deploy the rustrtc process on the same host/ports (or update only the media-plane URL).
3. Keep coturn / `TURN_*` env; drop the Node mediasoup process.

## Related infra

- **coturn (STUN/TURN):** `infra/oracle-coturn/`
- **Android app:** not wired to this plane yet (intentionally).
