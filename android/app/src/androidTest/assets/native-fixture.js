(async () => {
    const left = new RTCPeerConnection({iceServers: []}), right = new RTCPeerConnection({iceServers: []});
    const remote = new Peer(null, true, 'ci-device', 'ip', 'ci');
    const receiver = new Peer(null, false, 'ci-remote', 'ip', 'ci');
    receiver._conn = right; pairDrop.peers.peers['ci-remote'] = receiver;
    const channel = left.createDataChannel('clipboard-integration', {ordered: true});
    remote._channel = channel; remote._send = data => channel.send(data);
    channel.binaryType = 'arraybuffer'; channel.onmessage = event => remote._onMessage(event.data);
    const opened = ch => ch.readyState === 'open' ? Promise.resolve() : new Promise(resolve => ch.addEventListener('open', resolve, {once: true}));
    const ready = new Promise(resolve => { right.ondatachannel = async event => {
        receiver._channel = event.channel; event.channel.binaryType = 'arraybuffer';
        receiver._send = data => event.channel.send(data);event.channel.onmessage = message => receiver._onMessage(message.data);
        await opened(event.channel);resolve();
    }; });
    const gather = pc => pc.iceGatheringState === 'complete' ? Promise.resolve() : new Promise(resolve => {
        pc.addEventListener('icegatheringstatechange', () => { if (pc.iceGatheringState === 'complete') resolve(); });
    });
    await left.setLocalDescription(await left.createOffer());await gather(left);
    await right.setRemoteDescription(left.localDescription);
    await right.setLocalDescription(await right.createAnswer());await gather(right);
    await left.setRemoteDescription(right.localDescription);await Promise.all([opened(channel),ready]);
    const secret = 'ci-clipboard-pair'.padEnd(256, 'x');
    if (await PersistentStorage.getRoomSecretEntry(secret)) await PersistentStorage.deleteRoomSecret(secret);
    await PersistentStorage.addRoomSecret(secret, 'CI Mac', 'Mac');receiver._updateRoomIds('secret', secret);
    const stableId = (await window.PairDropBridge.snapshot()).find(peer => peer.id === 'ci-remote').stableId;
    Events.on('text-received', event => {
        if (event.detail.peerId === 'ci-device') window.PairDropHost.call('testEvent', {event: 'selectionReceived', text: event.detail.text});
    });
    window.ClipboardFixture = {
        send(text, paired) {
            if (paired) receiver._roomIds.secret = secret;else delete receiver._roomIds.secret;
            remote.sendText(text, 'remote-' + crypto.randomUUID());
        },
        close() { left.close();right.close();delete pairDrop.peers.peers['ci-remote']; }
    };
    await window.PairDropHost.call('testEvent', {event: 'fixtureReady', stableId});
})().catch(error => window.PairDropHost.call('testEvent', {event: 'failure', error: error.stack || error.message}));
