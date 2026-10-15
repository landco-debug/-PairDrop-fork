// SPDX-License-Identifier: GPL-3.0-only
import AppKit
import UniformTypeIdentifiers

@objc(ShareViewController)
final class ShareViewController: NSViewController {
    private var started = false
    private let work = DispatchQueue(label: "PairDrop.share-preparation")

    override func loadView() {
        view = NSView(frame: NSRect(x: 0, y: 0, width: 330, height: 100))
        let label = NSTextField(labelWithString: "Подготовка отправки через PairDrop…")
        label.frame = NSRect(x: 20, y: 38, width: 300, height: 24)
        view.addSubview(label)
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        guard !started else { return }
        started = true
        prepare()
    }

    private func prepare() {
        let group = DispatchGroup()
        let lock = NSLock()
        var values: [(Int, NSPasteboardItem)] = []
        var failure: Error?
        var index = 0
        func append(_ item: NSPasteboardItem, _ order: Int) { lock.lock(); values.append((order, item)); lock.unlock() }
        func record(_ error: Error?) { lock.lock(); if failure == nil { failure = error }; lock.unlock() }
        for input in extensionContext?.inputItems as? [NSExtensionItem] ?? [] {
            if (input.attachments ?? []).isEmpty, let text = input.attributedContentText?.string, !text.isEmpty {
                let item = NSPasteboardItem(); item.setString(text, forType: .string); append(item, index); index += 1
            }
            for provider in input.attachments ?? [] {
                let order = index; index += 1; group.enter()
                if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                    provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { value, error in
                        defer { group.leave() }
                        if let error = error { record(error); return }
                        let url = (value as? URL) ?? (value as? Data).flatMap { URL(dataRepresentation: $0, relativeTo: nil) }
                        guard let url = url, url.isFileURL else { record(self.error("Не удалось прочитать файл")); return }
                        do {
                            let copy = try self.preserveFile(url)
                            let item = NSPasteboardItem(); item.setString(copy.absoluteString, forType: .fileURL); append(item, order)
                        } catch { record(error) }
                    }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { value, error in
                        defer { group.leave() }
                        if let error = error { record(error); return }
                        let text = (value as? URL)?.absoluteString ?? (value as? String)
                        if let text = text { let item = NSPasteboardItem(); item.setString(text, forType: .string); append(item, order) }
                    }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { value, error in
                        defer { group.leave() }
                        if let error = error { record(error); return }
                        let text = (value as? String) ?? (value as? Data).flatMap { String(data: $0, encoding: .utf8) }
                        if let text = text { let item = NSPasteboardItem(); item.setString(text, forType: .string); append(item, order) }
                    }
                } else if let type = provider.registeredTypeIdentifiers.first(where: { UTType($0)?.conforms(to: .data) == true }) {
                    provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                        defer { group.leave() }
                        if let error = error { record(error); return }
                        guard let url = url else { record(self.error("Приложение не предоставило файл")); return }
                        do {
                            let copy = try self.preserveFile(url)
                            let item = NSPasteboardItem(); item.setString(copy.absoluteString, forType: .fileURL); append(item, order)
                        } catch { record(error) }
                    }
                } else { record(error("Этот тип данных не поддерживается")); group.leave() }
            }
        }
        group.notify(queue: .main) {
            if let failure = failure { self.extensionContext?.cancelRequest(withError: failure); return }
            guard !values.isEmpty else { self.extensionContext?.cancelRequest(withError: self.error("Нет данных для отправки")); return }
            let id = UUID().uuidString
            let pasteboard = NSPasteboard(name: NSPasteboard.Name("PairDrop-" + id))
            pasteboard.clearContents()
            let stamp = NSPasteboardItem()
            stamp.setString(String(Date().timeIntervalSince1970), forType: NSPasteboard.PasteboardType("site.landco.pairdrop.timestamp"))
            pasteboard.writeObjects(values.sorted { $0.0 < $1.0 }.map { $0.1 } + [stamp])
            NSWorkspace.shared.open(URL(string: "pairdrop-fork://share/" + id)!)
            self.extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
        }
    }

    private func preserveFile(_ source: URL) throws -> URL {
        let fm = FileManager.default
        let root = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("PairDropShare", isDirectory: true)
        let directory = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = source.lastPathComponent.isEmpty ? "file" : source.lastPathComponent
        let destination = directory.appendingPathComponent(name)
        let access = source.startAccessingSecurityScopedResource()
        defer { if access { source.stopAccessingSecurityScopedResource() } }
        try fm.copyItem(at: source, to: destination)
        return destination
    }

    private func error(_ text: String) -> Error { NSError(domain: "PairDrop.Share", code: 1, userInfo: [NSLocalizedDescriptionKey: text]) }
}
