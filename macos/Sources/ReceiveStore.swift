// SPDX-License-Identifier: GPL-3.0-only
import Foundation

final class ReceiveStore {
    struct Entry {
        let handle: FileHandle
        let temporary: URL
        let name: String
        let expected: Int64
        var written: Int64 = 0
    }
    private var entries: [String: Entry] = [:]
    let directory: URL

    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PairDrop", isDirectory: true)
    }

    static func safeName(_ name: String) -> String {
        let name = (name as NSString).lastPathComponent.replacingOccurrences(of: "\\", with: "_")
        let clean = String(name.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) })
        if clean.isEmpty || clean == "." || clean == ".." { return "file" }
        return clean.hasPrefix(".") ? "file" + clean : clean
    }

    func begin(name: String, size: Int64) throws -> String {
        guard size >= 0 else { throw HostError.message("Некорректный размер файла") }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let id = UUID().uuidString
        let temporary = directory.appendingPathComponent(".\(id).part")
        guard FileManager.default.createFile(atPath: temporary.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
            throw HostError.message("Не удалось создать файл в папке Загрузки/PairDrop")
        }
        entries[id] = Entry(handle: try FileHandle(forWritingTo: temporary), temporary: temporary,
                            name: Self.safeName(name), expected: size)
        return id
    }

    func append(id: String, base64: String) throws {
        guard var entry = entries[id], let data = Data(base64Encoded: base64), data.count <= 65536,
              entry.written + Int64(data.count) <= entry.expected else { throw HostError.message("Ошибка полученных данных") }
        try entry.handle.write(contentsOf: data)
        entry.written += Int64(data.count)
        entries[id] = entry
    }

    func finish(id: String) throws -> URL {
        guard let entry = entries[id], entry.written == entry.expected else { throw HostError.message("Файл получен не полностью") }
        try entry.handle.synchronize()
        try entry.handle.close()
        var destination = directory.appendingPathComponent(entry.name)
        let stem = (entry.name as NSString).deletingPathExtension
        let ext = (entry.name as NSString).pathExtension
        var suffix = 1
        while FileManager.default.fileExists(atPath: destination.path) {
            destination = directory.appendingPathComponent("\(stem) (\(suffix))" + (ext.isEmpty ? "" : "." + ext))
            suffix += 1
        }
        try FileManager.default.moveItem(at: entry.temporary, to: destination)
        entries.removeValue(forKey: id)
        return destination
    }

    func cancelAll() {
        for entry in entries.values {
            try? entry.handle.close()
            try? FileManager.default.removeItem(at: entry.temporary)
        }
        entries.removeAll()
    }
}
