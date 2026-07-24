'use strict';

/**
 * All mediasoup-specific logic lives in this file only.
 * server.js talks to this module via the logical media-backend contract.
 */

const mediasoup = require('mediasoup');
const { config } = require('./config');

/** @typedef {{ id: string, closed: boolean, close: Function }} MsEntity */

class MediasoupHandler {
  constructor() {
    /** @type {import('mediasoup').types.Worker[]} */
    this.workers = [];
    this.nextWorkerIdx = 0;
    /** @type {Map<string, Room>} */
    this.rooms = new Map();
  }

  async init() {
    const n = config.mediasoup.numWorkers;
    for (let i = 0; i < n; i++) {
      const worker = await mediasoup.createWorker({
        logLevel: config.mediasoup.worker.logLevel,
        logTags: config.mediasoup.worker.logTags,
        rtcMinPort: config.mediasoup.worker.rtcMinPort,
        rtcMaxPort: config.mediasoup.worker.rtcMaxPort,
      });
      worker.on('died', () => {
        console.error('[mediasoup] worker died, exiting in 2s');
        setTimeout(() => process.exit(1), 2000);
      });
      this.workers.push(worker);
      console.log(`[mediasoup] worker ${worker.pid} ready`);
    }
  }

  getIceServers() {
    return { iceServers: config.iceServers };
  }

  stats() {
    let peers = 0;
    for (const room of this.rooms.values()) {
      peers += room.peers.size;
    }
    return { rooms: this.rooms.size, peers };
  }

  getRouterRtpCapabilities(roomId) {
    const room = this.rooms.get(roomId);
    if (!room) {
      return null;
    }
    return room.router.rtpCapabilities;
  }

  /**
   * @param {{ roomId: string, peerId: string, notify: (msg: object) => void }} opts
   */
  async joinRoom({ roomId, peerId, notify }) {
    if (!roomId || !peerId) {
      throw err('INVALID', 'roomId and peerId required');
    }
    let room = this.rooms.get(roomId);
    if (!room) {
      room = await this._createRoom(roomId);
      this.rooms.set(roomId, room);
    }

    if (room.peers.has(peerId)) {
      // Replace stale peer (reconnect)
      await this._removePeer(room, peerId, { silent: true });
    }

    const peer = {
      id: peerId,
      notify,
      transports: new Map(),
      producers: new Map(),
      consumers: new Map(),
    };
    room.peers.set(peerId, peer);

    const others = [...room.peers.keys()].filter((id) => id !== peerId);
    for (const otherId of others) {
      const other = room.peers.get(otherId);
      other?.notify?.({
        v: 1,
        type: 'peerJoined',
        roomId,
        payload: { peerId },
      });
    }

    // Tell joiner about existing producers
    for (const otherId of others) {
      const other = room.peers.get(otherId);
      if (!other) continue;
      for (const producer of other.producers.values()) {
        peer.notify?.({
          v: 1,
          type: 'producerAvailable',
          roomId,
          payload: {
            peerId: otherId,
            producerId: producer.id,
            kind: producer.kind,
          },
        });
      }
    }

    return {
      roomId,
      peerId,
      rtpCapabilities: room.router.rtpCapabilities,
      peers: others,
      iceServers: config.iceServers,
    };
  }

  async createTransport({ roomId, peerId, direction }) {
    const { room, peer } = this._requirePeer(roomId, peerId);
    void direction; // reserved for multi-transport clients

    const transport = await room.router.createWebRtcTransport({
      ...config.mediasoup.webRtcTransport,
    });

    transport.on('dtlsstatechange', (state) => {
      if (state === 'closed' || state === 'failed') {
        transport.close();
      }
    });
    transport.on('@close', () => {
      peer.transports.delete(transport.id);
    });

    if (config.mediasoup.webRtcTransport.maxIncomingBitrate) {
      try {
        await transport.setMaxIncomingBitrate(
          config.mediasoup.webRtcTransport.maxIncomingBitrate
        );
      } catch {
        /* optional */
      }
    }

    peer.transports.set(transport.id, transport);

    return {
      transportId: transport.id,
      iceParameters: transport.iceParameters,
      iceCandidates: transport.iceCandidates,
      dtlsParameters: transport.dtlsParameters,
      sctpParameters: transport.sctpParameters || null,
    };
  }

  async connectTransport({ roomId, peerId, transportId, dtlsParameters }) {
    const { peer } = this._requirePeer(roomId, peerId);
    const transport = peer.transports.get(transportId);
    if (!transport) throw err('TRANSPORT_NOT_FOUND', `transport ${transportId}`);

    await transport.connect({ dtlsParameters });
    return { transportId, connected: true };
  }

  async produce({ roomId, peerId, transportId, kind, rtpParameters, appData }) {
    const { room, peer } = this._requirePeer(roomId, peerId);
    const transport = peer.transports.get(transportId);
    if (!transport) throw err('TRANSPORT_NOT_FOUND', `transport ${transportId}`);
    if (kind !== 'audio' && kind !== 'video') {
      throw err('INVALID', 'kind must be audio or video');
    }

    const producer = await transport.produce({
      kind,
      rtpParameters,
      appData: appData || {},
    });

    peer.producers.set(producer.id, producer);
    producer.on('transportclose', () => {
      peer.producers.delete(producer.id);
    });

    for (const [otherId, other] of room.peers) {
      if (otherId === peerId) continue;
      other.notify?.({
        v: 1,
        type: 'producerAvailable',
        roomId,
        payload: { peerId, producerId: producer.id, kind: producer.kind },
      });
    }

    return { producerId: producer.id, kind: producer.kind };
  }

  async consume({ roomId, peerId, producerId, rtpCapabilities, transportId }) {
    const { room, peer } = this._requirePeer(roomId, peerId);

    let remoteProducer = null;
    let ownerId = null;
    for (const [pid, p] of room.peers) {
      if (p.producers.has(producerId)) {
        remoteProducer = p.producers.get(producerId);
        ownerId = pid;
        break;
      }
    }
    if (!remoteProducer) {
      throw err('INVALID', `producer ${producerId} not found in room`);
    }
    if (ownerId === peerId) {
      throw err('INVALID', 'cannot consume own producer');
    }

    if (!room.router.canConsume({ producerId, rtpCapabilities })) {
      throw err('INVALID', 'cannot consume with given rtpCapabilities');
    }

    let transport = transportId ? peer.transports.get(transportId) : null;
    if (!transport) {
      // Prefer last created transport
      const list = [...peer.transports.values()];
      transport = list[list.length - 1] || null;
    }
    if (!transport) {
      throw err('TRANSPORT_NOT_FOUND', 'createTransport first');
    }

    const consumer = await transport.consume({
      producerId,
      rtpCapabilities,
      paused: false,
    });

    peer.consumers.set(consumer.id, consumer);
    consumer.on('transportclose', () => {
      peer.consumers.delete(consumer.id);
    });
    consumer.on('producerclose', () => {
      peer.consumers.delete(consumer.id);
      peer.notify?.({
        v: 1,
        type: 'consumerClosed',
        roomId,
        payload: { consumerId: consumer.id, producerId },
      });
    });

    return {
      consumerId: consumer.id,
      producerId,
      transportId: transport.id,
      kind: consumer.kind,
      rtpParameters: consumer.rtpParameters,
      producerPaused: consumer.producerPaused,
    };
  }

  async closeProducer({ roomId, peerId, producerId }) {
    const { room, peer } = this._requirePeer(roomId, peerId);
    const producer = peer.producers.get(producerId);
    if (!producer) throw err('INVALID', `producer ${producerId} not found`);

    producer.close();
    peer.producers.delete(producerId);

    for (const [otherId, other] of room.peers) {
      if (otherId === peerId) continue;
      other.notify?.({
        v: 1,
        type: 'producerClosed',
        roomId,
        payload: { peerId, producerId },
      });
    }

    return { producerId };
  }

  async restartIce({ roomId, peerId, transportId }) {
    const { peer } = this._requirePeer(roomId, peerId);
    const transport = peer.transports.get(transportId);
    if (!transport) throw err('TRANSPORT_NOT_FOUND', `transport ${transportId}`);

    const iceParameters = await transport.restartIce();
    return { transportId, iceParameters };
  }

  async leaveRoom({ roomId, peerId }) {
    const room = this.rooms.get(roomId);
    if (!room) return { left: true };
    await this._removePeer(room, peerId, { silent: false });
    if (room.peers.size === 0) {
      room.router.close();
      this.rooms.delete(roomId);
    }
    return { left: true };
  }

  /** Called when WebSocket drops without leave */
  async onDisconnect(roomId, peerId) {
    if (!roomId || !peerId) return;
    try {
      await this.leaveRoom({ roomId, peerId });
    } catch (e) {
      console.warn('[mediasoup] disconnect leave failed', e.message);
    }
  }

  // --- internals ---

  async _createRoom(roomId) {
    const worker = this._nextWorker();
    const router = await worker.createRouter({
      mediaCodecs: config.mediasoup.router.mediaCodecs,
    });
    return {
      id: roomId,
      router,
      peers: new Map(),
    };
  }

  _nextWorker() {
    const w = this.workers[this.nextWorkerIdx % this.workers.length];
    this.nextWorkerIdx += 1;
    return w;
  }

  _requirePeer(roomId, peerId) {
    const room = this.rooms.get(roomId);
    if (!room) throw err('ROOM_NOT_FOUND', `room ${roomId}`);
    const peer = room.peers.get(peerId);
    if (!peer) throw err('PEER_NOT_FOUND', `peer ${peerId}`);
    return { room, peer };
  }

  async _removePeer(room, peerId, { silent }) {
    const peer = room.peers.get(peerId);
    if (!peer) return;

    for (const consumer of peer.consumers.values()) {
      try {
        consumer.close();
      } catch {
        /* ignore */
      }
    }
    for (const producer of peer.producers.values()) {
      try {
        producer.close();
      } catch {
        /* ignore */
      }
    }
    for (const transport of peer.transports.values()) {
      try {
        transport.close();
      } catch {
        /* ignore */
      }
    }

    room.peers.delete(peerId);

    if (!silent) {
      for (const other of room.peers.values()) {
        other.notify?.({
          v: 1,
          type: 'peerLeft',
          roomId: room.id,
          payload: { peerId },
        });
      }
    }
  }
}

function err(code, message) {
  const e = new Error(message);
  e.code = code;
  return e;
}

module.exports = { MediasoupHandler };
