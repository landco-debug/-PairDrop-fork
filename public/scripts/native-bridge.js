// GPL-3.0. The privileged host is installed only in the bundled, local frontend.
class NativeBridge {
    constructor(app) {
        this.app = app;
        window.PairDropBridge = this;
        for (const name of ['peer-connected', 'peer-disconnected', 'peer-display-name-changed']) {
            Events.on(name, () => this.publishPeers());
        }
        for (const name of ['files-sent', 'text-sent', 'transfer-failed']) {
            Events.on(name, e => {
                if (e.detail?.requestId) this.post(name === 'transfer-failed' ? 'failed' : 'sent', e.detail);
            });
        }
        Events.on('text-received', e => this.post('text', {
            text: e.detail.text, url: extractFirstHttpUrl(e.detail.text),
            sender: document.getElementById(e.detail.peerId)?.ui?._displayName() || 'PairDrop'
        }));
        Events.on('files-transfer-request', () => this.post('show', {}));
        Events.on('ws-connected', () => this.post('connection', {online: true}));
        Events.on('ws-disconnected', () => this.post('connection', {online: false}));
        document.getElementById('notification')?.setAttribute('hidden', 'true');
        this.post('ready', {});
    }

    post(kind, data) {
        window.PairDropHost.call(kind, data).catch(error => console.error('Native bridge:', error));
    }

    async publishPeers() {
        const peers = [];
        for (const [id, peer] of Object.entries(this.app.peers.peers)) {
            if (peer._channel?.readyState !== 'open') continue;
            const secret = peer._getPairSecret();
            const stableId = secret
                ? Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(secret))),
                    b => b.toString(16).padStart(2, '0')).join('')
                : id;
            peers.push({id, stableId, paired: !!secret,
                name: document.getElementById(id)?.ui?._displayName() || id});
        }
        this.post('peers', {peers});
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
