# PairDrop fork — project instructions

## Authorized scope

This is the user's new repository: `landco-debug/-PairDrop-fork`.
Base: upstream PairDrop v1.11.2, commit `4862ba3067be1a0f2e0d1e94861dc9200b5bfeea`.
Keep upstream history, GPL licensing and attribution. Never commit to upstream.
The user has authorized implementation, commits, pushes and CI builds in this repository.

## User and product requirements

- User communication and installation instructions: Russian, concise, candid.
- Devices: MacBook Air M1, macOS Sequoia; Samsung Galaxy A57, Android 17 / One UI 9.
- Open source software only. No paid services, telemetry, accounts or arbitrary file-size caps.
- Transfer text, links, pictures and files both ways over LAN and internet using PairDrop.
- macOS: native lightweight application, Apple Silicon build only, ordinary app in Applications.
  No Homebrew, Xcode Command Line Tools or GitHub Desktop on the user's computer.
- Send selected text and links through macOS Services; send files through Finder Services
  and a Share extension. Apps with custom menus may not expose Services: document this.
- macOS must run without a Dock icon (`LSUIElement` / accessory policy). Closing or
  minimizing its window must leave reception available. The menu-bar icon is optional;
  the window must provide a menu for quit/reopen/settings and opening the app reopens it.
- A Chrome PairDrop tab and the native client are two independent endpoints, not a
  duplicate process. Never hide arbitrary Mac peers just because their OS/name matches.
  Identify this native endpoint clearly and explain that the old browser tab should close.
- Sending after minimization must wait for the preferred paired receiver, not choose a
  faster-discovered browser peer. Use bounded event-driven discovery and preserve the
  original command, without retrying completed transfers.
- Incoming transfer notifications must refer to a specific pending request and offer
  native accept/decline. Clicking expired notifications must never accept another request.
- Keep receiving while the Mac window is closed. Closing a window must not quit the receiver.
  A background receiver consumes resources: never promise zero RAM/CPU or delivery while quit/asleep.
  Avoid Electron and clipboard polling. Provide a way to reopen the window and quit.
- Remember a trusted target where identity is stable; never silently send to an arbitrary sole peer.
- Notifications containing a valid HTTP(S) URL, even with a title or source line, must open that
  URL in the default browser when clicked. Bind payload to each notification. Never open URLs
  on receipt, run shell content, or open untrusted URL schemes.
- The user now authorizes two-way text delivery straight to the receiver's clipboard after
  an explicit selection/send on the sender. Auto-copy only paired peers by default, with an
  opt-out; never monitor/retransmit unrelated clipboard changes. Receiving an image/file is not
  equivalent to universal image/file clipboard insertion; do not claim otherwise.
- Existing Android WebView wrapper hides dialogs. Fixing web code alone does not guarantee
  Android background discovery. Track Android changes and do not claim full Android always-on
  delivery until built and tested on a real device. Samsung Internet PWA was rejected.

## Bugs to fix and prove

1. Auto-saved receive dialogs block later queue entries, including hidden Android dialogs.
2. Delayed auto-save callbacks can target the next dialog and save it twice.
3. Download notifications can save an already-saved batch again unintentionally.
4. Shared send/receive busy state can strand outgoing batches during crossed transfers.
5. Link notifications fail on messages containing a caption plus URL.

Use regression tests exercising production classes/events and crossed transfers, not source
substring assertions. Cancel stale async work, bind operations to immutable transfer IDs,
release object URLs/listeners, and make save side effects idempotent per received batch.
Preserve protocol compatibility with upstream unless a deliberate migration is documented.
Do not retry completed sends automatically or drop queued work without showing failure.
No infinite reconnect loops, background polling, or embedding whole large files in native JSON.

## Validation and delivery

- Run relevant Node regression tests and syntax checks; record actual results in docs.
- Native send checks must call the real Services entry point and the file-panel enqueue path,
  read registered file ranges and receive them over WebRTC. Test first selection, saved selection
  and stale saved pairing. An inbound-only bridge test does not verify context-menu sending.
- Preserve WebKit exception messages from NSError.userInfo; discovery timeouts are structured
  outcomes. A stale target may offer a chooser, but a replacement always needs explicit consent.
- Build macOS with GitHub Actions, arm64 only, one clearly named distributable archive.
  The archive should contain the app and concise installation instructions.
- Do not call a source-only implementation, unrun workflow, unsigned extension or simulation
  a verified working installation. State build/signing/device-test limitations precisely.
- Do not require Apple developer credentials or publish/deploy hosting without an actual need.
  A fork does not change pairdrop.net; explain how clients load the patched frontend.
- Put durable project requirements, implementation status and follow-up steps in this repo.
- Target devices are specifically macOS Sequoia 15 and Android 17 / One UI 9.0. Android API 37
  requires ACCESS_LOCAL_NETWORK runtime permission. Never claim A57 battery measurements
  from emulator tests; keep a foreground receiver stoppable and its notification visible.
- Do not spawn subagents unless the user explicitly requests delegation.

