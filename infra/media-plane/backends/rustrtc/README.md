# rustrtc backend (stub)

**Not implemented** — same contract as mediasoup.

When ready, this directory should expose:

- The operations in [`../../contract/MEDIA_BACKEND.md`](../../contract/MEDIA_BACKEND.md)
- The JSON messages in [`../../contract/signaling-messages.md`](../../contract/signaling-messages.md)
- `GET /health` with `"backend": "rustrtc"`
- ICE servers from the same env: `TURN_URLS`, `TURN_USER`, `TURN_PASS`
- Bind `0.0.0.0:PORT` (default 3000) so deploy can hot-swap the process

See [STUB.md](./STUB.md) for the intended implementation checklist.

## Why a stub?

Barky isolates **mediasoup** under `backends/mediasoup/` so a future Rust RTC stack can replace the media plane with **minimal client diffs**: only the process behind the signaling URL changes, not message types.
