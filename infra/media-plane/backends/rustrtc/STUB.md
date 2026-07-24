# rustrtc stub — implementation checklist

Status: **not implemented**.

## Goal

Drop-in replacement for `backends/mediasoup` that speaks the same contract so clients need **no** signaling schema change.

## Must implement

- [ ] `GET /health` → `{ ok, backend: "rustrtc", uptimeSec, rooms, peers }`
- [ ] WebSocket `/ws` (+ optional `POST /v1/signal`) with envelope `v/id/type/roomId/peerId/payload`
- [ ] Ops: `getIceServers`, `join`, `createTransport`, `connectTransport`, `produce`, `consume`, `closeProducer`, `leave`, `getRouterRtpCapabilities`, `restartIce` (optional), `ping`
- [ ] Push events: `peerJoined`, `peerLeft`, `producerAvailable`, `producerClosed`, `consumerClosed`
- [ ] Room id = arbitrary string; 1:1 rooms first
- [ ] ICE from `TURN_URLS` / `TURN_USER` / `TURN_PASS`
- [ ] Listen `0.0.0.0:PORT` (default 3000); media ports via env (document range for firewall)

## Must not

- [ ] Import or depend on `mediasoup` / Node mediasoup-client server APIs
- [ ] FreeSWITCH / Asterisk
- [ ] Change `contract/*` unless versioning `v: 2` with dual support

## Swap procedure (ops)

1. Build/run rustrtc binary or container with the same env names for TURN + PORT.
2. Point load balancer / public URL at the new process (or stop mediasoup and bind same host:port).
3. Leave Kotlin/client message types unchanged; retest health + one 1:1 call.
4. Retire `backends/mediasoup` process (code can remain for fallback).

## Suggested tree (when implemented)

```
backends/rustrtc/
  README.md
  Cargo.toml          # or package as appropriate
  src/
    main.rs
    signaling.rs      # contract messages only
    media.rs          # rustrtc / WebRTC stack
  .env.example
```
