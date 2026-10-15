import Foundation

let root = FileManager.default.temporaryDirectory.appendingPathComponent("PairDrop-tests-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let store = ReceiveStore(directory: root)
assert(ReceiveStore.safeName("../../file.txt") == "file.txt")
assert(ReceiveStore.safeName(".profile") == "file.profile")
assert(ReceiveStore.safeName("..") == "file")
let bytes = Data([0, 1, 2, 255, 128])
let first = try store.begin(name: "test.bin", size: 5)
try store.append(id: first, base64: bytes.prefix(2).base64EncodedString())
try store.append(id: first, base64: bytes.suffix(3).base64EncodedString())
let saved = try store.finish(id: first)
let savedBytes = try Data(contentsOf: saved)
assert(savedBytes == bytes)
do { _ = try store.finish(id: first); fatalError("A receipt cannot be saved twice") } catch {}
let second = try store.begin(name: "test.bin", size: 0)
let another = try store.finish(id: second)
assert(another.lastPathComponent == "test (1).bin")
let broken = try store.begin(name: "broken", size: 10)
do { _ = try store.finish(id: broken); fatalError("Truncated file must fail") } catch {}
do { try store.append(id: broken, base64: Data(repeating: 0, count: 11).base64EncodedString()); fatalError("Oversize file must fail") } catch {}
store.cancelAll()
let remaining = try FileManager.default.contentsOfDirectory(atPath: root.path)
assert(!remaining.contains { $0.hasSuffix(".part") })
print("Native disk regression checks: PASS")
