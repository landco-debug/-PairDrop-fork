(() => {
    let serial = 0;
    const pending = new Map();
    nativePairDrop.onmessage = event => {
        const reply = JSON.parse(event.data);
        const callback = pending.get(reply.id);
        if (!callback) return;
        pending.delete(reply.id);
        reply.ok ? callback.resolve(reply.data) : callback.reject(new Error(reply.error));
    };
    window.PairDropHost = Object.freeze({platform: 'android', call(kind, data = {}) {
        return new Promise((resolve, reject) => {
            const id = ++serial;
            pending.set(id, {resolve, reject});
            nativePairDrop.postMessage(JSON.stringify({id, kind, data}));
        });
    }});
})();
