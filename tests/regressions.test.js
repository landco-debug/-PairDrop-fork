import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

class Element {
    constructor() {
        this.attributes = {};
        this.children = new Map();
        this.classList = {add() {}, remove() {}};
        this.style = {};
        this.ui = {_displayName: () => 'Samsung', _connectionHash: '123', _badgeClassName: () => 'badge-room-secret'};
    }
    querySelector(key) {
        if (!this.children.has(key)) this.children.set(key, new Element());
        return this.children.get(key);
    }
    querySelectorAll() { return []; }
    addEventListener() {}
    setAttribute(k, v) { this.attributes[k] = v; }
    removeAttribute(k) { delete this.attributes[k]; }
    appendChild() {}
    focus() {}
    blur() {}
    click() { this.onclick?.(); }
}

function harness() {
    let time = 0, serial = 0;
    const timers = new Map(), events = [], listeners = new Map(), downloads = [], revoked = [], elements = new Map();
    const notifications = [];
    class FakeNotification {
        constructor(title, config) { this.title = title; this.config = config; notifications.push(this); }
        close() { this.closed = true; }
    }
    const context = vm.createContext({
        console: {log() {}, error() {}, warn() {}}, Blob, File, URL: class extends URL {
            static createObjectURL() { return `blob:${++serial}`; }
            static revokeObjectURL(url) { revoked.push(url); }
        },
        setTimeout(fn, delay = 0) { const id = ++serial; timers.set(id, {fn, at: time + delay}); return id; },
        clearTimeout(id) { timers.delete(id); },
        queueMicrotask, btoa, atob,
        navigator: {clipboard: {writeText: async () => {}}, onLine: true},
        window: {isMobile: true, blop: {play() {}}, Notification: FakeNotification, focus() {}},
        Notification: FakeNotification,
        document: {
            visibilityState: 'hidden', activeElement: new Element(),
            createElement(type) {
                const el = new Element();
                if (type === 'a') el.click = () => downloads.push(el.download);
                return el;
            },
            querySelectorAll() { return []; }
        },
        $(id) { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); },
        changeFavicon() {},
        Localization: {getTranslation: key => key},
        BrowserTabsConnector: {peerIsSameBrowser: () => false},
        Events: {
            on(name, fn) { if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(fn); },
            off(name, fn) { listeners.set(name, (listeners.get(name) || []).filter(x => x !== fn)); },
            fire(name, detail) {
                events.push({name, detail});
                for (const fn of [...(listeners.get(name) || [])]) fn({detail});
            }
        }
    });
    for (const name of ['link-utils', 'network', 'ui']) {
        vm.runInContext(readFileSync(new URL(`../public/scripts/${name}.js`, import.meta.url), 'utf8'), context);
    }
    const classes = vm.runInContext('({Peer, FileDigester, ReceiveFileDialog, Notifications, extractFirstHttpUrl})', context);
    async function tick(delta) {
        const target = time + delta;
        for (;;) {
            const next = [...timers.entries()].filter(([, t]) => t.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
            if (!next) break;
            const [id, task] = next;
            timers.delete(id); time = task.at;
            await task.fn();
            await Promise.resolve();
        }
        time = target;
    }
    return {context, ...classes, tick, events, downloads, revoked, notifications, timers};
}

const file = name => new File(['abc'], name, {type: 'application/octet-stream'});

test('closing A cancels its timer; B saves exactly once and releases the receipt queue', async () => {
    const h = harness(), dialog = new h.ReceiveFileDialog();
    await dialog._onFilesReceived('phone', [file('A')], false, 3);
    await dialog._onFilesReceived('phone', [file('B')], false, 3);
    dialog.hide();
    await h.tick(300); // B is now displayed. A's callback must not save B at 500 ms.
    await h.tick(200);
    assert.deepEqual(h.downloads, []);
    await h.tick(300);
    assert.deepEqual(h.downloads, ['B']);
    await h.tick(300);
    assert.equal(dialog._busy, false);
    assert.equal(dialog._filesQueue.length, 0);
    await h.tick(30000);
    assert.ok(h.revoked.length > 0);
});

test('manual save followed by old callback cannot repeat or target another receipt', async () => {
    const h = harness(), dialog = new h.ReceiveFileDialog();
    await dialog._onFilesReceived('phone', [file('A')], false, 3);
    const oldSave = dialog.$downloadBtn.onclick;
    oldSave(); oldSave();
    await dialog._onFilesReceived('phone', [file('B')], false, 3);
    await h.tick(300);
    oldSave();
    await h.tick(800);
    assert.deepEqual(h.downloads, ['A', 'B']);
    assert.equal(dialog._busy, false);
});

test('hidden Android receipt dialogs do not block subsequent batches', async () => {
    const h = harness(), dialog = new h.ReceiveFileDialog();
    dialog.show = () => {}; // Matches the fm-sys WebView wrapper.
    for (const name of ['A', 'B', 'C']) await dialog._onFilesReceived('phone', [file(name)], false, 3);
    await h.tick(2500);
    assert.deepEqual(h.downloads, ['A', 'B', 'C']);
    assert.equal(dialog._filesQueue.length, 0);
    assert.equal(dialog._busy, false);
});

test('download notification click cannot save the next file', () => {
    const h = harness(), n = new h.Notifications();
    n._downloadNotification([file('A')]);
    let clicks = 0;
    n.$downloadBtn.onclick = () => { clicks++; };
    h.notifications[0].onclick();
    assert.equal(clicks, 0);
});

test('captioned link notifications keep their own URL; ordinary messages copy successfully', async () => {
    const h = harness(), n = new h.Notifications(), opened = [], copied = [];
    h.context.window.open = url => opened.push(url);
    h.context.navigator.clipboard.writeText = async text => { copied.push(text); };
    n._messageNotification('Источник: ichip.ru.\nhttps://share.google/abc', 'phone');
    n._messageNotification('another\nhttps://example.com/two', 'phone');
    h.notifications[0].onclick();
    assert.deepEqual(opened, ['https://share.google/abc']);
    n._messageNotification('ordinary text', 'phone');
    await h.notifications[2].onclick();
    assert.deepEqual(copied, ['ordinary text']);
    assert.equal(h.notifications[3].title, 'notifications.copied-text');
});

test('link extraction handles punctuation, unicode and balanced parentheses, and rejects other schemes', () => {
    const h = harness();
    for (const [input, expected] of [
        ['Source\n https://example.com/a. ', 'https://example.com/a'],
        ['(https://example.com/a_(b)).', 'https://example.com/a_(b)'],
        ['See www.example.com/test', 'https://www.example.com/test'],
        ['https://example.com/путь', 'https://example.com/%D0%BF%D1%83%D1%82%D1%8C'],
        ['javascript:alert(1)', null], ['file:///tmp/test', null], ['no link', null]
    ]) assert.equal(h.extractFirstHttpUrl(input), expected, input);
});

test('crossed transfers and additional outbound batches finish once without a new user action', async () => {
    const h = harness();
    // Real Peer/Chunker/Digester; only the browser's FileReader and RTC transport are substituted.
    h.context.FileReader = class {
        addEventListener(_name, fn) { this.onLoad = fn; }
        readAsArrayBuffer(blob) { blob.arrayBuffer().then(data => this.onLoad({target: {result: data}})); }
    };
    class Endpoint extends h.Peer {
        _send(message) { queueMicrotask(() => this.other._onMessage(message)); }
    }
    const a = new Endpoint(null, true, 'phone', 'ip', 'local');
    const b = new Endpoint(null, false, 'mac', 'ip', 'local');
    a.other = b; b.other = a; a._autoAccept = b._autoAccept = true;
    await Promise.all([a.requestFileTransfer([file('from-mac-1')]), b.requestFileTransfer([file('from-phone')])]);
    await a.requestFileTransfer([file('from-mac-2')]);
    for (let i = 0; i < 30; i++) await new Promise(resolve => setImmediate(resolve));
    const received = h.events.filter(e => e.name === 'files-received').flatMap(e => Array.from(e.detail.files, f => f.name));
    assert.deepEqual(received.sort(), ['from-mac-1', 'from-mac-2', 'from-phone']);
    assert.equal(a._outboundActive || a._sending || a._receiving, false);
    assert.equal(b._outboundActive || b._sending || b._receiving, false);
    assert.equal(a._outboundQueue.length, 0);
    a._onFileTransferCompleted(); // Late duplicate ACK must not send/complete another batch.
    assert.equal(h.events.filter(e => e.name === 'files-sent').length, 3);
});

test('a second receive request cannot overwrite an accepted transfer', () => {
    const h = harness(), peer = new h.Peer(null, true, 'phone', 'ip', 'local'), replies = [];
    peer._send = message => replies.push(JSON.parse(message)); peer._autoAccept = true;
    const request = {header: [{name: 'A', size: 3}], totalSize: 3};
    peer._onFilesTransferRequest(request);
    peer._onFilesTransferRequest({header: [{name: 'B', size: 5}], totalSize: 5});
    assert.equal(peer._requestAccepted, request);
    assert.equal(replies.at(-1).accepted, false);
});
