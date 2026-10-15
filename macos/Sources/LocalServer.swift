// SPDX-License-Identifier: GPL-3.0-only
import Foundation
import Network
import UniformTypeIdentifiers

/// Only bundled public assets and explicitly registered file capabilities are served.
/// The stable loopback origin keeps IndexedDB pairing across launches.
final class LocalServer {
    static let port: UInt16 = 49731
    let baseURL = URL(string: "http://127.0.0.1:\(LocalServer.port)/")!
    private let root: URL
    private let queue = DispatchQueue(label: "PairDrop.local-http")
    private var listener: NWListener?
    private var files: [String: URL] = [:]

    init(root: URL) { self.root = root.standardizedFileURL }

    func start(completion: @escaping (Error?) -> Void) throws {
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: Self.port)!)
        let listener = try NWListener(using: params)
        self.listener = listener
        var completed = false
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                if !completed { completed = true; DispatchQueue.main.async { completion(nil) } }
            case .failed(let error):
                if !completed { completed = true; DispatchQueue.main.async { completion(error) } }
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            connection.start(queue: self?.queue ?? .global())
            self?.readRequest(connection, buffer: Data())
        }
        listener.start(queue: queue)
    }

    func register(_ url: URL) throws -> [String: Any] {
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .contentTypeKey])
        guard values.isRegularFile == true else { throw HostError.message("Выберите обычные файлы, без папок.") }
        let token = UUID().uuidString + UUID().uuidString
        queue.sync { files[token] = url }
        return ["token": token, "name": url.lastPathComponent, "size": values.fileSize ?? 0,
                "type": values.contentType?.preferredMIMEType ?? "application/octet-stream"]
    }

    func release(tokens: [String]) { queue.async { tokens.forEach { self.files.removeValue(forKey: $0) } } }

    private func readRequest(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, done, error in
            guard let self = self else { connection.cancel(); return }
            var request = buffer
            if let data = data { request.append(data) }
            if request.count > 16384 { self.respond(connection, status: "431 Request Header Fields Too Large"); return }
            if let text = String(data: request, encoding: .utf8), text.contains("\r\n\r\n") {
                self.serve(connection, request: text)
            } else if done || error != nil { connection.cancel() }
            else { self.readRequest(connection, buffer: request) }
        }
    }

    private func serve(_ connection: NWConnection, request: String) {
        let lines = request.components(separatedBy: "\r\n")
        let first = (lines.first ?? "").split(separator: " ")
        guard first.count == 3, first[0] == "GET" || first[0] == "HEAD" else {
            respond(connection, status: "405 Method Not Allowed"); return
        }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[String(line[..<colon]).lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        guard headers["host"] == "127.0.0.1:\(Self.port)" else { respond(connection, status: "403 Forbidden"); return }
        let path = String(first[1]).split(separator: "?", maxSplits: 1).first.map(String.init) ?? "/"
        guard let decoded = path.removingPercentEncoding, !decoded.contains("\0"),
              !decoded.split(separator: "/").contains("..") else { respond(connection, status: "403 Forbidden"); return }
        let head = first[0] == "HEAD"
        if decoded.hasPrefix("/native-files/") {
            let token = String(decoded.dropFirst("/native-files/".count))
            guard let url = files[token], let range = headers["range"], range.hasPrefix("bytes=") else {
                respond(connection, status: "404 Not Found"); return
            }
            let parts = range.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false)
            guard parts.count == 2, let start = UInt64(parts[0]), let end = UInt64(parts[1]),
                  end >= start, end - start < 65536 else { respond(connection, status: "416 Range Not Satisfiable"); return }
            do {
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard end < UInt64(size) else { respond(connection, status: "416 Range Not Satisfiable"); return }
                let handle = try FileHandle(forReadingFrom: url)
                defer { try? handle.close() }
                try handle.seek(toOffset: start)
                let data = try handle.read(upToCount: Int(end - start + 1)) ?? Data()
                respond(connection, status: "206 Partial Content", type: "application/octet-stream", data: data, head: head,
                        extra: "Content-Range: bytes \(start)-\(end)/\(size)\r\n")
            } catch { respond(connection, status: "500 Internal Server Error") }
            return
        }
        let resource = decoded == "/" ? "index.html" : String(decoded.dropFirst())
        let url = root.appendingPathComponent(resource).standardizedFileURL
        guard url.path.hasPrefix(root.path + "/"), let data = try? Data(contentsOf: url) else {
            respond(connection, status: "404 Not Found"); return
        }
        let types = ["html": "text/html", "js": "application/javascript", "css": "text/css", "json": "application/json",
                     "svg": "image/svg+xml", "png": "image/png", "ttf": "font/ttf", "mp3": "audio/mpeg", "ogg": "audio/ogg"]
        respond(connection, status: "200 OK", type: types[url.pathExtension] ?? "application/octet-stream", data: data, head: head)
    }

    private func respond(_ connection: NWConnection, status: String, type: String = "text/plain", data: Data = Data(),
                         head: Bool = false, extra: String = "") {
        let headers = "HTTP/1.1 \(status)\r\nContent-Type: \(type)\r\nContent-Length: \(data.count)\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\(extra)\r\n"
        var response = Data(headers.utf8)
        if !head { response.append(data) }
        connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
    }
}

enum HostError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}
