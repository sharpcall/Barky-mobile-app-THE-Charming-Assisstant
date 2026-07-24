# Signaling messages (backend-agnostic)

Transport: **WebSocket** preferred (`/ws`).  
Optional: `POST /v1/signal` with the same JSON body (request/response, no push events).

Every message is a single JSON object.

## Envelope

### Client → server

```json
{
  "v": 1,
  "id": "client-correlation-id",
  "type": "join",
  "roomId": "pair-or-arbitrary",
  "peerId": "device-id",
  "payload": { }
}
```

| Field | Required | Notes |
|-------|----------|--------|
| `v` | yes | Protocol version; currently `1` |
| `id` | yes | Echoed in the reply for request/response |
| `type` | yes | See types below |
| `roomId` | most ops | Room key |
| `peerId` | most ops | Peer key |
| `payload` | no | Type-specific |

### Server → client (reply)

```json
{
  "v": 1,
  "id": "client-correlation-id",
  "type": "joinResult",
  "ok": true,
  "payload": { }
}
```

### Server → client (event, no client `id`)

```json
{
  "v": 1,
  "type": "peerJoined",
  "roomId": "…",
  "payload": { "peerId": "…" }
}
```

---

## Request types (`type`) and payloads

### `getIceServers`

- **payload:** `{}`
- **result type:** `iceServersResult`
- **payload:** `{ "iceServers": [ … ] }`

### `join`

- **payload:** `{}` (or `{ "displayName": "…" }` optional)
- **result type:** `joinResult`
- **payload:**  
  `{ "rtpCapabilities": {}, "peers": ["…"], "iceServers": [] }`

### `getRouterRtpCapabilities`

- **payload:** `{}`
- **result type:** `routerRtpCapabilities`
- **payload:** `{ "rtpCapabilities": {} }`

### `createTransport`

- **payload:** `{ "direction": "send" | "recv" | "both" }` (optional)
- **result type:** `createTransportResult`
- **payload:**  
  `{ "transportId", "iceParameters", "iceCandidates", "dtlsParameters", "sctpParameters" }`

### `connectTransport`

- **payload:** `{ "transportId": "…", "dtlsParameters": {} }`
- **result type:** `connectTransportResult`
- **payload:** `{ "transportId": "…", "connected": true }`

### `produce`

- **payload:**  
  `{ "transportId", "kind": "audio"|"video", "rtpParameters": {}, "appData": {} }`
- **result type:** `produceResult`
- **payload:** `{ "producerId", "kind" }`

### `consume`

- **payload:**  
  `{ "producerId", "rtpCapabilities": {}, "transportId"? }`  
  (`transportId` optional if peer has exactly one recv/dual transport)
- **result type:** `consumeResult`
- **payload:**  
  `{ "consumerId", "producerId", "transportId", "kind", "rtpParameters", "producerPaused" }`

### `closeProducer`

- **payload:** `{ "producerId": "…" }`
- **result type:** `closeProducerResult`
- **payload:** `{ "producerId": "…" }`

### `restartIce`

- **payload:** `{ "transportId": "…" }`
- **result type:** `restartIceResult`
- **payload:** `{ "transportId", "iceParameters" }`

### `leave`

- **payload:** `{}`
- **result type:** `leaveResult`
- **payload:** `{ "left": true }`

### `ping`

- **payload:** `{}`
- **result type:** `pong`
- **payload:** `{ "t": <server unix ms> }`

---

## Server push events

| `type` | When | `payload` |
|--------|------|-----------|
| `peerJoined` | Another peer joins the room | `{ "peerId" }` |
| `peerLeft` | Peer leaves or disconnects | `{ "peerId" }` |
| `producerAvailable` | Peer produced media | `{ "peerId", "producerId", "kind" }` |
| `producerClosed` | Producer closed | `{ "peerId", "producerId" }` |
| `consumerClosed` | Consumer closed by server | `{ "consumerId", "producerId" }` |
| `error` | Async/unsolicited error | `{ "code", "message" }` |

---

## HTTP

### `GET /health`

```json
{
  "ok": true,
  "backend": "mediasoup",
  "uptimeSec": 12,
  "rooms": 1,
  "peers": 2
}
```

### `POST /v1/signal`

Body = same as one client message. Response = single reply object.  
Push events are **not** available on HTTP; use WebSocket for 1:1 calls.

---

## Client flow (1:1)

```
1. WS connect
2. getIceServers  → configure PeerConnection ICE
3. join(roomId, peerId) → rtpCapabilities
4. createTransport → set remote ICE/DTLS on local PC / mediasoup-client Device
5. connectTransport(dtlsParameters)
6. produce(audio/video)
7. on producerAvailable → consume(producerId, localRtpCapabilities)
8. leave on hangup
```

Exact local WebRTC wiring depends on whether the app uses `mediasoup-client`, raw `RTCPeerConnection`, or a future rustrtc client SDK — **signaling stays this document**.
