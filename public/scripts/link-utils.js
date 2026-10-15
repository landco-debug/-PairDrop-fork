// GPL-3.0: notification links may arrive with a title or a source on another line.
function extractFirstHttpUrl(message) {
    const candidates = String(message).match(/(?:https?:\/\/|www\.)[^\s<>"'`\u0000-\u001f]+/gi) || [];
    for (let candidate of candidates) {
        candidate = candidate.replace(/[.,;!\u3002\uFF0C\u00BB\u201D]+$/, '');
        for (const [open, close] of [['(', ')'], ['[', ']'], ['{', '}']]) {
            while (candidate.endsWith(close)
                && candidate.split(close).length > candidate.split(open).length) {
                candidate = candidate.slice(0, -1);
            }
        }
        try {
            const url = new URL(/^www\./i.test(candidate) ? `https://${candidate}` : candidate);
            if (['https:', 'http:'].includes(url.protocol) && url.hostname) return url.href;
        } catch (_) { /* Try the next candidate. */ }
    }
    return null;
}
