// GPL-3.0. The privileged host is installed only in the bundled, local frontend.
class NativeBridge {
    constructor(app) {
        this.app = app;
        this.requests = new Map();
        window.PairDropBridge = this;
        for (const name of ['peer-connected', 'peer-disconnected', 'peer-display-name-changed', 'peer-room-types-changed']) {
            Events.on(name, () => this.publishPeers());
        }
        Events.on('peer-disconnected', e => {
            for (const [id, request] of this.requests) {
                if (request.peerId === e.detail) this.expireRequest(id);
            }
            this.post('peerLeft', {peerId: e.detail});
        });
        for (const name of ['files-sent', 'text-sent', 'transfer-failed']) {
            Events.on(name, e => {
                if (e.detail?.requestId) this.post(name === 'transfer-failed' ? 'failed' : 'sent', e.detail);
            });
        }
        Events.on('text-received', e => this.post('text', {
            text: e.detail.text, url: extractFirstHttpUrl(e.detail.text),
            sender: document.getElementById(e.detail.peerId)?.ui?._displayName() || 'PairDrop'
        }));
        Events.on('files-transfer-request', e => this.receiveRequest(e.detail));
        Events.on('ws-connected', () => this.post('connection', {online: true}));
        Events.on('ws-disconnected', () => this.post('connection', {online: false}));
        document.getElementById('notification')?.setAttribute('hidden', 'true');
        this.post('ready', {});
        this.setNativeName().catch(error => console.error(error));
    }

    post(kind, data) {
        window.PairDropHost.call(kind, data).catch(error => console.error('Native bridge:', error));
    }

    async setNativeName() {
        if (!this.app.footerUI) return;
        if (!await this.app.footerUI._getSavedDisplayName()) {
            await this.app.footerUI._saveDisplayName('MacBook · PairDrop Fork');
        }
    }

    async snapshot() {
        const peers = [];
        for (const [id, peer] of Object.entries(this.app.peers.peers)) {
            if (peer._channel?.readyState !== 'open') continue;
            const secret = peer._getPairSecret();
            const stableId = secret
                ? Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(secret))),
                    b => b.toString(16).padStart(2, '0')).join('')
                : id;
            if (this.app.peers.peers[id] !== peer || peer._channel?.readyState !== 'open') continue;
            const ui = document.getElementById(id)?.ui;
            peers.push({id, stableId, paired: !!secret,
                name: ui?._displayName() || id, deviceName: ui?._deviceName?.() || ''});
        }
        return peers.sort((a, b) => Number(b.paired) - Number(a.paired));
    }

    async publishPeers() {
        const revision = this._peerRevision = (this._peerRevision || 0) + 1;
        const peers = await this.snapshot();
        if (revision === this._peerRevision) this.post('peers', {peers});
    }

    async prepareToSend({preferred = null, timeoutMs = 15000} = {}) {
        const knownPairs = await PersistentStorage.getAllRoomSecrets().catch(() => []);
        return new Promise((resolve, reject) => {
            let finished = false, settleTimer, deadlineTimer, connectedHandler, revision = 0;
            const names = ['peer-connected', 'peer-disconnected', 'peer-room-types-changed',
                'peer-display-name-changed', 'ws-connected'];
            const cleanup = () => {
                clearTimeout(settleTimer); clearTimeout(deadlineTimer);
                names.forEach(name => Events.off(name, changed));
                if (connectedHandler) Events.off('ws-connected', connectedHandler);
            };
            const finish = (peers, error, code = null) => {
                if (finished) return;
                finished = true; cleanup();
                error ? reject(Object.assign(new Error(error), {code})) : resolve(peers);
            };
            const evaluate = async (settled = false) => {
                const generation = ++revision;
                const peers = await this.snapshot();
                if (finished || generation !== revision) return;
                if (preferred) {
                    if (peers.some(peer => peer.stableId === preferred)) finish(peers);
                    return;
                }
                if (settled && peers.length && (!knownPairs.length || peers.some(peer => peer.paired))) finish(peers);
            };
            const changed = () => {
                if (finished) return;
                clearTimeout(settleTimer);
                evaluate().catch(error => finish([], error.message));
                settleTimer = setTimeout(() => evaluate(true).catch(error => finish([], error.message)), 1200);
            };
            names.forEach(name => Events.on(name, changed));
            deadlineTimer = setTimeout(async () => {
                try {
                    const peers = await this.snapshot();
                    if (!preferred && peers.length) finish(peers);
                    else finish([], 'Сохранённый получатель недоступен. Выберите устройство ещё раз.', 'receiver-unavailable');
                } catch (error) { finish([], error.message); }
            }, timeoutMs);
            // Refresh only on an explicit send. No background polling or reload of the receiver.
            this.app.server._connect();
            const refresh = () => {
                this.app.server.send({type: 'join-ip-room'});
                if (knownPairs.length) this.app.server.send({type: 'room-secrets', roomSecrets: knownPairs});
                changed();
            };
            if (this.app.server._isConnected()) refresh();
            else {
                connectedHandler = () => { Events.off('ws-connected', connectedHandler); refresh(); };
                Events.on('ws-connected', connectedHandler);
            }
            changed();
        });
    }

    // Expected discovery failures cross WKWebView as data, preserving their actual message.
    // A stale saved pairing may offer a fresh chooser, but never authorize another target.
    async prepareNativeSend(options) {
        try {
            return {ok: true, peers: await this.prepareToSend(options), needsSelection: false};
        } catch (error) {
            if (error.code === 'receiver-unavailable') {
                const peers = await this.snapshot().catch(() => []);
                if (peers.length) return {ok: true, peers, needsSelection: true, message: error.message};
            }
            return {ok: false, error: error.message || String(error)};
        }
    }

    receiveRequest({request, peerId}) {
        const id = crypto.randomUUID();
        this.requests.set(id, {request, peerId});
        this.post('request', {id, peerId,
            sender: document.getElementById(peerId)?.ui?._displayName() || 'PairDrop',
            names: request.header.map(file => file.name), size: request.totalSize});
    }

    expireRequest(id) {
        this.requests.delete(id);
        this.post('requestExpired', {id});
    }

    respondToRequest(id, accepted) {
        const entry = this.requests.get(id);
        if (!entry) return false;
        const peer = this.app.peers.peers[entry.peerId];
        if (!peer || peer._requestPending !== entry.request) { this.expireRequest(id); return false; }
        this.requests.delete(id);
        Events.fire('respond-to-files-transfer-request', {to: entry.peerId, accepted: !!accepted});
        this.post('requestExpired', {id});
        return true;
    }

    async send(command) {
        const peer = this.app.peers.peers[command.to];
        if (!peer || peer._channel?.readyState !== 'open') throw new Error('Получатель отключён');
        if (Array.isArray(command.files) && command.files.length) {
            const files = command.files.map(meta => ({
                name: meta.name, type: meta.type || 'application/octet-stream', size: meta.size,
                nativeFile: true,
                slice(start, end) {
                    return {arrayBuffer: async () => {
                        if (meta.size === 0) return new ArrayBuffer(0);
                        const response = await fetch(`/native-files/${encodeURIComponent(meta.token)}`, {
                            headers: {Range: `bytes=${start}-${Math.min(end, meta.size) - 1}`}
                        });
                        if (!response.ok) throw new Error('Не удалось прочитать файл');
                        return response.arrayBuffer();
                    }};
                }
            }));
            await peer.requestFileTransfer(files, command.id);
        } else {
            peer.sendText(command.text, command.id);
        }
        return true;
    }
}
