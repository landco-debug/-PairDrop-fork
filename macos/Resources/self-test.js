await window.PairDropHost.call('selfTestTrace', {stage: 'fixture-start'});
// Real WebRTC data channels + production Peer/NativeBridge + native streamed disk writes.
const left = new RTCPeerConnection({iceServers: []});
const right = new RTCPeerConnection({iceServers: []});
const sender = new Peer(null, true, 'ci-hidden-receiver', 'ip', 'ci');
const receiver = new Peer(null, false, 'ci-hidden-sender', 'ip', 'ci');
receiver._conn = right;
pairDrop.peers.peers['ci-hidden-sender'] = receiver;
const outgoing = left.createDataChannel('production-transfer', {ordered: true});
sender._channel = outgoing;
sender._send = message => outgoing.send(message);
outgoing.onmessage = event => sender._onMessage(event.data);
outgoing.binaryType = 'arraybuffer';
const waitOpen = channel => channel.readyState === 'open' ? Promise.resolve() : new Promise(resolve => { channel.onopen = resolve; });
const incomingReady = new Promise(resolve => {
    right.ondatachannel = async event => {
        receiver._channel = event.channel;
        event.channel.binaryType = 'arraybuffer';
        event.channel.onmessage = message => receiver._onMessage(message.data);
        receiver._send = message => event.channel.send(message);
        await waitOpen(event.channel);
        resolve();
    };
});
const gather = pc => pc.iceGatheringState === 'complete' ? Promise.resolve() : new Promise(resolve => {
    pc.addEventListener('icegatheringstatechange', () => { if (pc.iceGatheringState === 'complete') resolve(); });
});
await left.setLocalDescription(await left.createOffer());
await gather(left);
await right.setRemoteDescription(left.localDescription);
await right.setLocalDescription(await right.createAnswer());
await gather(right);
await left.setRemoteDescription(right.localDescription);
await Promise.all([waitOpen(outgoing), incomingReady]);
// The native test has removed this web view from its window before starting.
await new Promise(resolve => setTimeout(resolve, 2500));
const received = new Promise(resolve => {
    const handler = event => {
        if (event.detail.peerId !== 'ci-hidden-sender') return;
        Events.off('files-received', handler);
        resolve(event.detail.files[0]);
    };
    Events.on('files-received', handler);
});
const acknowledged = new Promise(resolve => {
    const handler = event => {
        if (event.detail.requestId !== 'ci-file-once') return;
        Events.off('files-sent', handler);
        resolve();
    };
    Events.on('files-sent', handler);
});
const bytes = new Uint8Array(1100123);
for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
await sender.requestFileTransfer([new File([bytes], 'pairdrop-ci-v2.bin', {type: 'application/octet-stream'})], 'ci-file-once');
const complete = await received;
await window.PairDropHost.call('selfTestTrace', {stage: 'file-received'});
await acknowledged;
const result = {secure: window.isSecureContext, rtc: true, bridge: !!window.PairDropBridge,
    disk: complete.nativeSaved === true && complete.size === bytes.length,
    hidden: document.hidden, requestAccepted: !receiver._requestPending};
const sendRemoteText = async (text, requestId) => {
    const ack = new Promise(resolve => {
        const handler = event => { if (event.detail.requestId === requestId) { Events.off('text-sent', handler); resolve(); } };
        Events.on('text-sent', handler);
    });
    sender.sendText(text, requestId); await ack;
};
await window.PairDropHost.call('selfTestTrace', {stage: 'unpaired-text-start'});
await sendRemoteText('unpaired-must-not-replace', 'ci-unpaired-text');
if (!await window.PairDropHost.call('selfTestClipboard', {expected: 'clipboard-ci-seed'})) throw new Error('Unpaired peer replaced clipboard');
// Exercise the actual Swift Services -> registered file -> prepare -> lazy HTTP range -> RTC path.
const secret = 'ci-context-secret-'.padEnd(256, 'x');
await PersistentStorage.addRoomSecret(secret, 'CI Android', 'Android');
receiver._updateRoomIds('secret', secret);
pairDrop.peersUI._joinPeer({id: 'ci-hidden-sender', name: {displayName: 'CI Android', deviceName: 'Android',
    os: 'Android', browser: 'Chrome', type: 'mobile'}}, 'secret', secret);
pairDrop.peersUI._onPeerConnected('ci-hidden-sender', '0123456789abcdef');
sender._autoAccept = true;
const stableId = (await window.PairDropBridge.snapshot()).find(peer => peer.id === 'ci-hidden-sender').stableId;
for (const [source, options] of [
    ['service', {resetPreferred: true}], // Actual first-time native chooser.
    ['service', {preferred: 'obsolete-pairing-fingerprint'}], // Must ask, never redirect automatically.
    ['menu', {}] // Reuse the new pairing saved by the chooser; native lazy-file path.
]) {
    const contextReceived = new Promise(resolve => {
        const handler = event => {
            if (event.detail.peerId !== 'ci-hidden-receiver') return;
            Events.off('files-received', handler);
            resolve(event.detail.files[0]);
        };
        Events.on('files-received', handler);
    });
    const contextAcknowledged = new Promise(resolve => {
        const handler = event => {
            if (event.detail.peerId !== 'ci-hidden-sender') return;
            Events.off('files-sent', handler);
            resolve();
        };
        Events.on('files-sent', handler);
    });
    await window.PairDropHost.call('selfTestSend', {source, ...options});
    const contextFile = await contextReceived;
    await contextAcknowledged;
    if (!contextFile.nativeSaved || contextFile.size !== bytes.length) throw new Error('Native outbound transfer failed: ' + source);
}
result.contextSend = true;
result.menuSend = true;
result.stalePreferred = true;
const unicodeText = 'Android → Mac\nПривет, мир 👋\nhttps://example.com/?q=тест';
await sendRemoteText(unicodeText, 'ci-paired-text');
if (!await window.PairDropHost.call('selfTestClipboard', {expected: unicodeText, enabled: false})) throw new Error('Incoming paired text missing from system clipboard');
await sendRemoteText('disabled-must-not-replace', 'ci-disabled-text');
if (!await window.PairDropHost.call('selfTestClipboard', {expected: unicodeText, enabled: true})) throw new Error('Disabled auto-copy replaced clipboard');
const selectedText = 'Mac → Android\nВыделенный текст «🙂»\n  spaces preserved  ';
const selectionReceived = new Promise(resolve => {
    const handler = event => {
        if (event.detail.peerId === 'ci-hidden-receiver') { Events.off('text-received', handler); resolve(event.detail.text); }
    };
    Events.on('text-received', handler);
});
await window.PairDropHost.call('selfTestSelection', {text: selectedText});
if (await selectionReceived !== selectedText) throw new Error('System Service changed selected text');
if (!await window.PairDropHost.call('selfTestClipboard', {expected: unicodeText})) throw new Error('Sending a selection replaced the local clipboard');
result.clipboardBothWays = true;
result.clipboardPolicy = true;
await PersistentStorage.deleteRoomSecret(secret);
left.close(); right.close();
delete pairDrop.peers.peers['ci-hidden-sender'];
return result;
