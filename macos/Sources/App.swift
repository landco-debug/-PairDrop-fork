// SPDX-License-Identifier: GPL-3.0-only
import AppKit
import WebKit
import UserNotifications

@main
struct PairDropMain {
    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.run()
        withExtendedLifetime(delegate) {}
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate,
                         WKScriptMessageHandlerWithReply, WKNavigationDelegate, WKUIDelegate, NSToolbarDelegate,
                         UNUserNotificationCenterDelegate {
    private var window: NSWindow!
    private var webView: WKWebView!
    private var server: LocalServer!
    private let receiveStore = ReceiveStore()
    private var peers: [[String: Any]] = []
    private var earlyURLs: [URL] = []
    private var ready = false
    private var online = false
    private var pending: [[String: Any]] = []
    private var active: [String: [String]] = [:]
    private var statusItem: NSStatusItem?
    private var activity: NSObjectProtocol?
    private var preparingSend = false
    private var incoming: [String: [String: Any]] = [:]
    private var showingIncoming = false
    private var selfTestAcceptedIncoming = false
    private let selfTest = CommandLine.arguments.contains("--self-test")
    private var preferred: String? { UserDefaults.standard.string(forKey: "preferred-peer") }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        installMenu()
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories([UNNotificationCategory(identifier: "text", actions: [
            UNNotificationAction(identifier: "copy", title: "Копировать", options: [])
        ], intentIdentifiers: [], options: []),
        UNNotificationCategory(identifier: "transfer", actions: [
            UNNotificationAction(identifier: "acceptTransfer", title: "Принять", options: []),
            UNNotificationAction(identifier: "declineTransfer", title: "Отклонить", options: [])
        ], intentIdentifiers: [], options: [])])
        if !selfTest { center.requestAuthorization(options: [.alert, .sound]) { _, _ in } }
        // Event-driven sockets, no clipboard polling. Let the display sleep normally.
        activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiatedAllowingIdleSystemSleep],
                                                        reason: "Приём PairDrop при закрытом окне")
        let root = Bundle.main.resourceURL!.appendingPathComponent("Web", isDirectory: true)
        server = LocalServer(root: root)
        do {
            try server.start { [weak self] error in
                if let error = error { self?.fail(error.localizedDescription); return }
                self?.createWindow()
            }
        } catch { fail(error.localizedDescription) }
        if selfTest {
            DispatchQueue.main.asyncAfter(deadline: .now() + 90) { self.finishSelfTest(false, "Frontend initialization timed out") }
        }
    }

    private func createWindow() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        // Official API: keep event-driven signaling/data channels running without a visible window.
        config.preferences.inactiveSchedulingPolicy = .none
        config.userContentController.addScriptMessageHandler(self, contentWorld: .page, name: "pairdrop")
        let bridge = "window.PairDropHost = Object.freeze({call: (kind, data = {}) => window.webkit.messageHandlers.pairdrop.postMessage({kind, data})});"
        config.userContentController.addUserScript(WKUserScript(source: bridge, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        webView = WKWebView(frame: NSRect(x: 0, y: 0, width: 920, height: 700), configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        window = NSWindow(contentRect: webView.frame, styleMask: [.titled, .closable, .miniaturizable, .resizable],
                          backing: .buffered, defer: false)
        window.title = "PairDrop Fork · v2"
        window.contentView = webView
        window.delegate = self
        window.isReleasedWhenClosed = false
        let toolbar = NSToolbar(identifier: "PairDrop-controls")
        toolbar.delegate = self
        toolbar.displayMode = .labelOnly
        window.toolbar = toolbar
        window.center()
        showWindow()
        webView.load(URLRequest(url: server.baseURL))
        let urls = earlyURLs; earlyURLs = []
        if !urls.isEmpty { application(NSApp, open: urls) }
    }

    private func installMenu() {
        let menu = NSMenu()
        let appItem = NSMenuItem(); menu.addItem(appItem)
        let appMenu = NSMenu(title: "PairDrop Fork"); appItem.submenu = appMenu
        appMenu.addItem(withTitle: "Показать PairDrop", action: #selector(showWindow), keyEquivalent: "0").target = self
        appMenu.addItem(withTitle: "Отправить файлы…", action: #selector(selectFiles), keyEquivalent: "o").target = self
        let sendTextItem = appMenu.addItem(withTitle: "Отправить текст из буфера", action: #selector(sendClipboard), keyEquivalent: "v")
        sendTextItem.target = self
        sendTextItem.keyEquivalentModifierMask = [.command, .shift]
        let services = NSMenuItem(title: "Службы", action: nil, keyEquivalent: "")
        services.submenu = NSMenu(title: "Службы"); appMenu.addItem(services); NSApp.servicesMenu = services.submenu
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Входящие запросы…", action: #selector(showIncomingRequests), keyEquivalent: "").target = self
        appMenu.addItem(withTitle: "Сбросить получателя по умолчанию", action: #selector(clearPreferred), keyEquivalent: "").target = self
        appMenu.addItem(withTitle: "Значок в строке меню", action: #selector(toggleStatusItem), keyEquivalent: "").target = self
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Закрыть окно", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        appMenu.addItem(withTitle: "Выйти из PairDrop", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        let editItem = NSMenuItem(); menu.addItem(editItem)
        let edit = NSMenu(title: "Правка"); editItem.submenu = edit
        for (title, action, key) in [("Вырезать", "cut:", "x"), ("Копировать", "copy:", "c"), ("Вставить", "paste:", "v"), ("Выделить всё", "selectAll:", "a")] {
            edit.addItem(withTitle: title, action: Selector(action), keyEquivalent: key)
        }
        NSApp.mainMenu = menu
        if UserDefaults.standard.bool(forKey: "show-status-item") { updateStatusItem() }
    }

    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        [.flexibleSpace, NSToolbarItem.Identifier("receiver-menu")]
    }
    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        toolbarDefaultItemIdentifiers(toolbar)
    }
    func toolbar(_ toolbar: NSToolbar, itemForItemIdentifier identifier: NSToolbarItem.Identifier,
                 willBeInsertedIntoToolbar flag: Bool) -> NSToolbarItem? {
        guard identifier.rawValue == "receiver-menu" else { return nil }
        let item = NSMenuToolbarItem(itemIdentifier: identifier)
        item.label = "Меню"
        item.menu = receiverMenu()
        return item
    }
    private func receiverMenu() -> NSMenu {
        (NSApp.mainMenu?.items.first?.submenu?.copy() as? NSMenu) ?? NSMenu()
    }
    @objc private func toggleStatusItem() {
        UserDefaults.standard.set(!UserDefaults.standard.bool(forKey: "show-status-item"), forKey: "show-status-item")
        updateStatusItem()
    }
    private func updateStatusItem() {
        if let item = statusItem { NSStatusBar.system.removeStatusItem(item); statusItem = nil }
        if UserDefaults.standard.bool(forKey: "show-status-item") {
            let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
            item.button?.image = NSImage(systemSymbolName: "arrow.up.arrow.down.circle", accessibilityDescription: "PairDrop")
            item.menu = receiverMenu()
            statusItem = item
        }
    }

    @objc func showWindow() {
        if window?.isMiniaturized == true { window.deminiaturize(nil) }
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
    @objc private func clearPreferred() { UserDefaults.standard.removeObject(forKey: "preferred-peer") }
    @objc private func selectFiles() {
        let panel = NSOpenPanel(); panel.allowsMultipleSelection = true; panel.canChooseDirectories = false
        if panel.runModal() == .OK { enqueueFiles(panel.urls) }
    }
    @objc private func sendClipboard() {
        if let text = NSPasteboard.general.string(forType: .string), !text.isEmpty { enqueue(["text": text]) }
    }

    // Registered by NSServices. No clipboard polling or Accessibility permission is needed.
    @objc func sendSelection(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        if let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
            enqueueFiles(urls)
        } else if let text = pasteboard.string(forType: .string), !text.isEmpty { enqueue(["text": text]) }
        else { error.pointee = "Не получены текст или файлы" }
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        guard server != nil else { earlyURLs.append(contentsOf: urls); return }
        for url in urls {
            guard url.scheme == "pairdrop-fork", url.host == "share", let id = UUID(uuidString: url.lastPathComponent) else { continue }
            let pasteboard = NSPasteboard(name: NSPasteboard.Name("PairDrop-" + id.uuidString))
            let timestampType = NSPasteboard.PasteboardType("site.landco.pairdrop.timestamp")
            guard let stamp = pasteboard.string(forType: timestampType), let date = Double(stamp), abs(Date().timeIntervalSince1970 - date) < 120 else { continue }
            var files: [URL] = []
            var texts: [String] = []
            for item in pasteboard.pasteboardItems ?? [] {
                if let raw = item.string(forType: .fileURL), let file = URL(string: raw), file.isFileURL { files.append(file) }
                else if let text = item.string(forType: .string) { texts.append(text) }
            }
            pasteboard.clearContents()
            if !files.isEmpty { enqueueFiles(files) }
            if !texts.isEmpty { enqueue(["text": texts.joined(separator: "\n")]) }
        }
    }

    private func enqueueFiles(_ urls: [URL]) {
        do { enqueue(["files": try urls.map { try server.register($0) }]) }
        catch { fail(error.localizedDescription) }
    }
    private func enqueue(_ payload: [String: Any]) {
        var command = payload
        command["id"] = UUID().uuidString
        command["created"] = Date().timeIntervalSince1970
        pending.append(command)
        if ready { drain() }
    }

    private func drain() {
        guard ready, !preparingSend, !pending.isEmpty else { return }
        preparingSend = true
        let command = pending.removeFirst()
        // Keep this exact command/capability while waking signaling and waiting for the phone.
        webView.callAsyncJavaScript("return await window.PairDropBridge.prepareToSend({preferred});",
                                    arguments: ["preferred": preferred as Any? ?? NSNull()], in: nil, in: .page) { [weak self] result in
            guard let self = self else { return }
            defer { self.preparingSend = false; self.drain() }
            switch result {
            case .failure(let error):
                self.release(command)
                self.fail(Self.scriptError(error, operation: "Подготовка отправки"))
            case .success(let raw):
                self.chooseAndSend(command, choices: raw as? [[String: Any]] ?? [])
            }
        }
    }

    private func chooseAndSend(_ payload: [String: Any], choices: [[String: Any]]) {
        var command = payload
        guard !choices.isEmpty else { release(command); fail("Получатель недоступен"); return }
        var target = choices.first { ($0["stableId"] as? String) == preferred }
        if preferred != nil && target == nil {
            release(command)
            fail("Сохранённый телефон ещё не подключён. Другому устройству файлы не отправлены.")
            return
        }
        if target == nil {
            showWindow()
            let alert = NSAlert()
            alert.messageText = "Отправить через PairDrop"
            alert.informativeText = "Выберите получателя. Сопряжённое устройство запомнится. Старую вкладку pairdrop.net в Chrome можно закрыть."
            alert.addButton(withTitle: "Отправить"); alert.addButton(withTitle: "Отмена")
            let picker = NSPopUpButton(frame: NSRect(x: 0, y: 0, width: 360, height: 28))
            choices.forEach {
                let device = $0["deviceName"] as? String ?? ""
                picker.addItem(withTitle: ($0["name"] as? String ?? "Устройство") + (device.isEmpty ? "" : " — " + device)
                    + (($0["paired"] as? Bool == true) ? " • сопряжено" : ""))
            }
            alert.accessoryView = picker
            if selfTest {
                guard let index = choices.firstIndex(where: { $0["id"] as? String == "ci-hidden-sender" }) else {
                    finishSelfTest(false, "CI receiver missing from native chooser"); return
                }
                picker.selectItem(at: index)
                DispatchQueue.main.async { alert.buttons[0].performClick(nil) }
            }
            if alert.runModal() == .alertFirstButtonReturn {
                target = choices[picker.indexOfSelectedItem]
                if target?["paired"] as? Bool == true { UserDefaults.standard.set(target?["stableId"], forKey: "preferred-peer") }
            }
        }
        guard let to = target?["id"] as? String, let id = command["id"] as? String else { release(command); return }
        command.removeValue(forKey: "created"); command["to"] = to
        active[id] = (command["files"] as? [[String: Any]] ?? []).compactMap { $0["token"] as? String }
        webView.callAsyncJavaScript("return await window.PairDropBridge.send(command);", arguments: ["command": command], in: nil, in: .page) { [weak self] result in
            if case .failure(let error) = result { self?.complete(id, error: Self.scriptError(error, operation: "Отправка")) }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 120) { [weak self] in
            guard let self = self, self.active[id] != nil else { return }
            self.notify(title: "Передача ещё выполняется", body: "Проверьте состояние PairDrop. Повторная отправка автоматически не запускается.", payload: [:])
        }
    }

    private func release(_ command: [String: Any]) {
        let tokens = (command["files"] as? [[String: Any]] ?? []).compactMap { $0["token"] as? String }
        server.release(tokens: tokens)
    }
    private func complete(_ id: String, error: String? = nil) {
        guard let tokens = active.removeValue(forKey: id) else { return }
        server.release(tokens: tokens)
        if let error = error { fail(error) }
        else { notify(title: "Отправлено", body: "Получатель подтвердил получение.", payload: [:]) }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        guard message.frameInfo.isMainFrame, message.frameInfo.securityOrigin.host == "127.0.0.1",
              message.frameInfo.securityOrigin.port == Int(LocalServer.port),
              let body = message.body as? [String: Any], let kind = body["kind"] as? String,
              let data = body["data"] as? [String: Any] else { replyHandler(nil, "Untrusted frame"); return }
        do {
            switch kind {
            case "ready":
                ready = true
                if selfTest {
                    runSelfTest()
                } else { drain() }
            case "connection":
                online = data["online"] as? Bool ?? false
                drain()
            case "peers":
                peers = data["peers"] as? [[String: Any]] ?? []
                drain()
            case "show": showWindow()
            case "request":
                if let id = data["id"] as? String {
                    incoming[id] = data
                    let names = data["names"] as? [String] ?? []
                    notify(title: "Запрос файла от " + (data["sender"] as? String ?? "устройства"),
                           body: names.prefix(3).joined(separator: ", ") + " · Принять или отклонить",
                           payload: ["request": id], identifier: "request-" + id)
                    if selfTest && data["peerId"] as? String == "ci-hidden-sender" {
                        DispatchQueue.main.async { self.respondIncoming(id, accepted: true) }
                    }
                    if !selfTest && NSApp.isActive && window.isVisible {
                        DispatchQueue.main.async { self.presentIncoming(id) }
                    }
                }
            case "requestExpired":
                if let id = data["id"] as? String { expireIncoming(id) }
            case "text":
                let text = data["text"] as? String ?? ""
                var payload: [String: Any] = ["text": text]
                if let raw = data["url"] as? String, Self.webURL(raw) != nil { payload["url"] = raw }
                notify(title: payload["url"] == nil ? "Получен текст" : "Получена ссылка", body: text, payload: payload)
            case "sent": if let id = data["requestId"] as? String { complete(id) }
            case "failed": if let id = data["requestId"] as? String { complete(id, error: "Передача отклонена или соединение прервано") }
            case "beginFile":
                let id = try receiveStore.begin(name: data["name"] as? String ?? "file", size: (data["size"] as? NSNumber)?.int64Value ?? -1, peerId: data["peerId"] as? String ?? "")
                replyHandler(["id": id], nil); return
            case "peerLeft":
                let peerId = data["peerId"] as? String ?? ""
                receiveStore.cancel(peerId: peerId)
                for (id, request) in incoming where request["peerId"] as? String == peerId { expireIncoming(id) }
            case "appendFile":
                try receiveStore.append(id: data["id"] as? String ?? "", base64: data["data"] as? String ?? "")
            case "finishFile":
                let id = data["id"] as? String ?? ""
                let url = try receiveStore.finish(id: id)
                notify(title: "Файл сохранён", body: url.lastPathComponent, payload: ["file": url.path])
                replyHandler(["id": id], nil); return
            case "selfTestSend":
                guard selfTest else {
                    replyHandler(nil, "Only available during self-test"); return
                }
                UserDefaults.standard.removeObject(forKey: "preferred-peer")
                if let stableId = data["preferred"] as? String {
                    UserDefaults.standard.set(stableId, forKey: "preferred-peer")
                }
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("pairdrop-ci-context.bin")
                try Data((0..<1100123).map { UInt8($0 % 251) }).write(to: url)
                let pasteboard = NSPasteboard.withUniqueName()
                pasteboard.writeObjects([url as NSURL])
                DispatchQueue.main.async {
                    var error: NSString?
                    self.sendSelection(pasteboard, userData: nil, error: &error)
                    pasteboard.releaseGlobally()
                    if let error = error { self.finishSelfTest(false, error as String) }
                }
            default: replyHandler(nil, "Unknown message"); return
            }
            replyHandler(true, nil)
        } catch { replyHandler(nil, error.localizedDescription) }
    }

    private func expireIncoming(_ id: String) {
        incoming.removeValue(forKey: id)
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["request-" + id])
    }
    @objc private func showIncomingRequests() {
        if let id = incoming.keys.first { presentIncoming(id) }
        else { fail("Нет ожидающих запросов на передачу") }
    }
    private func presentIncoming(_ id: String) {
        guard !showingIncoming, let request = incoming[id] else { return }
        showingIncoming = true
        defer { showingIncoming = false }
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Принять файл от " + (request["sender"] as? String ?? "устройства") + "?"
        let names = request["names"] as? [String] ?? []
        let size = (request["size"] as? NSNumber)?.int64Value ?? 0
        alert.informativeText = names.prefix(5).joined(separator: "\n") + "\n" + ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
        alert.addButton(withTitle: "Принять"); alert.addButton(withTitle: "Отклонить")
        let accepted = alert.runModal() == .alertFirstButtonReturn
        if incoming[id] != nil { respondIncoming(id, accepted: accepted) }
    }
    private func respondIncoming(_ id: String, accepted: Bool) {
        guard incoming[id] != nil else { return }
        webView.callAsyncJavaScript("return window.PairDropBridge.respondToRequest(id, accepted);",
                                    arguments: ["id": id, "accepted": accepted], in: nil, in: .page) { [weak self] result in
            self?.expireIncoming(id)
            if case .success(let accepted) = result, self?.selfTest == true, accepted as? Bool == true {
                self?.selfTestAcceptedIncoming = true
            }
            if case .failure(let error) = result { self?.fail(error.localizedDescription) }
        }
    }

    private func notify(title: String, body: String, payload: [String: Any], identifier: String = UUID().uuidString) {
        guard !selfTest else { return }
        let content = UNMutableNotificationContent()
        content.title = title; content.body = String(body.prefix(1000)); content.userInfo = payload
        if payload["text"] != nil { content.categoryIdentifier = "text" }
        if payload["request"] != nil { content.categoryIdentifier = "transfer" }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }
    static func webURL(_ raw: String) -> URL? {
        guard let url = URL(string: raw), ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil else { return nil }
        return url
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        DispatchQueue.main.async {
            let data = response.notification.request.content.userInfo
            if let id = data["request"] as? String {
                if response.actionIdentifier == "acceptTransfer" { self.respondIncoming(id, accepted: true) }
                else if response.actionIdentifier == "declineTransfer" { self.respondIncoming(id, accepted: false) }
                else if response.actionIdentifier == UNNotificationDefaultActionIdentifier { self.presentIncoming(id) }
            }
            else if response.actionIdentifier == "copy", let text = data["text"] as? String { self.copy(text) }
            else if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
                if let raw = data["url"] as? String, let url = Self.webURL(raw) { NSWorkspace.shared.open(url) }
                else if let text = data["text"] as? String { self.copy(text) }
                else if let path = data["file"] as? String { NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)]) }
                else { self.showWindow() }
            }
            completionHandler()
        }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list])
    }
    private func copy(_ text: String) { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string) }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
        if url.host == "127.0.0.1", url.port == Int(LocalServer.port) { decisionHandler(.allow); return }
        if navigationAction.navigationType == .linkActivated, let url = Self.webURL(url.absoluteString) { NSWorkspace.shared.open(url) }
        decisionHandler(.cancel)
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let raw = navigationAction.request.url?.absoluteString, let url = Self.webURL(raw) { NSWorkspace.shared.open(url) }
        return nil
    }
    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel(); panel.allowsMultipleSelection = parameters.allowsMultipleSelection
        panel.begin { completionHandler($0 == .OK ? panel.urls : nil) }
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        ready = false; online = false; peers = []; receiveStore.cancelAll()
        for id in Array(active.keys) { complete(id, error: "WebKit перезапущен; незавершённую передачу нужно повторить вручную") }
        webView.reload()
    }
    func windowShouldClose(_ sender: NSWindow) -> Bool { sender.orderOut(nil); return false }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool { showWindow(); return true }
    func applicationWillTerminate(_ notification: Notification) {
        receiveStore.cancelAll()
        if let activity = activity { ProcessInfo.processInfo.endActivity(activity) }
    }
    private func fail(_ message: String) {
        if selfTest { finishSelfTest(false, message); return }
        let alert = NSAlert(); alert.messageText = "PairDrop"; alert.informativeText = message; alert.runModal()
    }
    private static func scriptError(_ error: Error, operation: String) -> String {
        let native = error as NSError
        let detail = native.userInfo["WKJavaScriptExceptionMessage"] as? String ?? error.localizedDescription
        let source = native.userInfo["WKJavaScriptExceptionSourceURL"] as? String ?? ""
        let line = native.userInfo["WKJavaScriptExceptionLineNumber"] ?? "?"
        return "\(operation): \(detail)\(source.isEmpty ? "" : " (\(source):\(line))")"
    }
    private func runSelfTest() {
        window.orderOut(nil)
        window.contentView = NSView() // Exercise WKPreferences when detached, not just momentarily hidden.
        guard let url = Bundle.main.url(forResource: "self-test", withExtension: "js"),
              let script = try? String(contentsOf: url, encoding: .utf8) else {
            finishSelfTest(false, "Missing WebRTC integration test"); return
        }
        webView.callAsyncJavaScript(script, arguments: [:], in: nil, in: .page) { result in
            switch result {
            case .success(let raw):
                let values = raw as? [String: Bool] ?? [:]
                let path = self.receiveStore.directory.appendingPathComponent("pairdrop-ci-v2.bin")
                let bytes = try? Data(contentsOf: path)
                let service = self.responds(to: NSSelectorFromString("sendSelection:userData:error:"))
                let success = values["secure"] == true && values["rtc"] == true && values["bridge"] == true
                    && values["disk"] == true && values["contextSend"] == true
                    && bytes == Data((0..<1100123).map { UInt8($0 % 251) }) && service
                    && self.selfTestAcceptedIncoming && NSApp.activationPolicy() == .accessory
                    && self.webView.configuration.preferences.inactiveSchedulingPolicy == .none
                try? FileManager.default.removeItem(at: path)
                self.finishSelfTest(success, "detached background WebRTC, native request acceptance, 1.1 MB disk receive, no Dock: \(values), service=\(service), nativeAccept=\(self.selfTestAcceptedIncoming)")
            case .failure(let error): self.finishSelfTest(false, Self.scriptError(error, operation: "Self-test"))
            }
        }
    }
    private func finishSelfTest(_ success: Bool, _ message: String) {
        print("PairDrop self-test: \(success ? "PASS" : "FAIL") \(message)")
        fflush(stdout)
        exit(success ? 0 : 1)
    }
}
