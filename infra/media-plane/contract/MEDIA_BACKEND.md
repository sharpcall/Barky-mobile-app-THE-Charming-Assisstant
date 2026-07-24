# Media backend interface (logical)

This is the **stable** media-plane API. Every backend (`mediasoup`, future `rustrtc`, …) must implement these operations.  
Wire format is JSON over WebSocket (or HTTP POST batch) — see [signaling-messages.md](./signaling-messages.md).

Backends may use different internal primitives, but **request/response field names and meanings stay the same**.

---

## Concepts

| Term | Meaning |
|------|---------|
| `roomId` | Arbitrary string; Barky device-pair or any call key |
| `peerId` | Client-chosen stable id within the room (device id) |
| `transportId` | Backend id for a WebRTC transport |
| `producerId` | Outbound media track from a peer |
| `consumerId` | Inbound media track for a peer |
| `rtpCapabilities` | Router / peer RTP caps (ORTC-compatible object) |
| `iceServers` | STUN/TURN list from env (coturn separate) |

---

## Operations

### `getIceServers()`

Return ICE servers for PeerConnection construction.

**Response**

```json
{
  "iceServers": [
    { "urls": ["stun:…"] },
    { "urls": ["turn:…"], "username": "…", "credential": "…" }
  ]
}
```

Source: `TURN_URLS` (comma-separated), `TURN_USER`, `TURN_PASS`. Empty list is allowed for LAN-only tests.

---

### `joinRoom({ roomId, peerId })`

Register peer in room. Create room if missing. Return router RTP capabilities and peer list.

**Response**

```json
{
  "roomId": "…",
  "peerId": "…",
  "rtpCapabilities": { },
  "peers": ["otherPeerId"],
  "iceServers": [ ]
}
```

---

### `createTransport({ roomId, peerId, direction? })`

Create a WebRTC transport for the peer.

- `direction`: optional `"send"` | `"recv"` | `"both"` (default `"both"` if backend uses dual; mediasoup typically creates one transport per call and reuses it).

**Response** (mediasoup-shaped; rustrtc must map to the same fields)

```json
{
  "transportId": "…",
  "iceParameters": { },
  "iceCandidates": [ ],
  "dtlsParameters": { },
  "sctpParameters": null
}
```

---

### `connectTransport({ roomId, peerId, transportId, dtlsParameters })`

Complete DTLS on the transport (client → server).

**Response**

```json
{ "transportId": "…", "connected": true }
```

---

### `produce({ roomId, peerId, transportId, kind, rtpParameters, appData? })`

Publish audio or video.

- `kind`: `"audio"` | `"video"`
- `rtpParameters`: WebRTC RTP parameters object

**Response**

```json
{
  "producerId": "…",
  "kind": "audio"
}
```

Other peers in the room should be notified (`producerAvailable` / equivalent) so they can `consume`.

---

### `consume({ roomId, peerId, producerId, rtpCapabilities })`

Create a consumer of another peer’s producer for this peer.

**Response**

```json
{
  "consumerId": "…",
  "producerId": "…",
  "transportId": "…",
  "kind": "audio",
  "rtpParameters": { },
  "producerPaused": false
}
```

Client must have a **recv-capable** transport already created and connected (or use the same dual transport, depending on backend).

---

### `closeProducer({ roomId, peerId, producerId })` (optional but recommended)

Stop publishing; notify others.

### `leaveRoom({ roomId, peerId })`

Remove peer, close transports/producers/consumers, notify remaining peers.

### `getRouterRtpCapabilities({ roomId })`

Return current router RTP capabilities (same object as join when room exists).

### `restartIce({ roomId, peerId, transportId })` (optional)

Return new `iceParameters` for ICE restart.

---

## Error shape

All failed ops:

```json
{
  "ok": false,
  "error": {
    "code": "ROOM_NOT_FOUND" | "PEER_NOT_FOUND" | "TRANSPORT_NOT_FOUND" | "INVALID" | "INTERNAL",
    "message": "human readable"
  }
}
```

---

## Non-goals

- No SIP / FreeSWITCH / Asterisk
- No long-term storage of media
- No requirement that the Kotlin app embeds mediasoup or rustrtc APIs — only this contract over the network
