import Foundation
import LumenKit

/// The shared app's downloads on the iPhone (FileDownloader in :phoneui): a background URLSession, so a film goes on
/// downloading with the app closed; iOS wakes the app at the end. Files go to Documents/Téléchargements, named by the
/// shared app; each task carries "id|name" so the app finds its line again after a restart.
final class LumenDownloader: NSObject, FileDownloader, URLSessionDownloadDelegate {
    static let shared = LumenDownloader()

    /// Given by iOS when it wakes the app for the session's events; called once they are all handled.
    var backgroundCompletion: (() -> Void)?
    private var lastReport = [Int64: Date]()
    private let lock = NSLock()

    lazy var session: URLSession = {
        let c = URLSessionConfiguration.background(withIdentifier: "com.lumentv.downloads")
        c.sessionSendsLaunchEvents = true
        c.isDiscretionary = false
        c.allowsCellularAccess = true
        return URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }()

    static var folder: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("Téléchargements", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func file(_ name: String) -> URL { folder.appendingPathComponent(name) }

    // ---- FileDownloader (called by the shared app) -----------------------------------------------------------

    func start(id: Int64, url: String, userAgent: String?, fileName: String) {
        guard let u = URL(string: url) else {
            MainKt.downloads().onFailed(id: id, message: "Adresse invalide")
            return
        }
        var request = URLRequest(url: u)
        request.setValue(userAgent ?? "Lumen TV", forHTTPHeaderField: "User-Agent")
        let task = session.downloadTask(with: request)
        task.taskDescription = "\(id)|\(fileName)"
        task.resume()
    }

    func cancel(id: Int64) {
        session.getAllTasks { tasks in
            tasks.filter { $0.taskDescription?.hasPrefix("\(id)|") == true }.forEach { $0.cancel() }
        }
    }

    func remove(fileName: String) {
        try? FileManager.default.removeItem(at: Self.file(fileName))
    }

    // ---- URLSession ------------------------------------------------------------------------------------------

    private func parse(_ task: URLSessionTask) -> (Int64, String)? {
        guard let d = task.taskDescription, let bar = d.firstIndex(of: "|"), let id = Int64(d[..<bar]) else { return nil }
        return (id, String(d[d.index(after: bar)...]))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let (id, _) = parse(downloadTask) else { return }
        // Once a second at most: each report is a database write.
        lock.lock()
        let now = Date()
        let due = now.timeIntervalSince(lastReport[id] ?? .distantPast) >= 1
        if due { lastReport[id] = now }
        lock.unlock()
        if due { MainKt.downloads().onProgress(id: id, bytes: totalBytesWritten, total: max(0, totalBytesExpectedToWrite)) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let (id, name) = parse(downloadTask) else { return }
        if let http = downloadTask.response as? HTTPURLResponse, !(200 ..< 300).contains(http.statusCode) {
            MainKt.downloads().onFailed(id: id, message: "Le serveur a répondu \(http.statusCode)")
            return
        }
        let dest = Self.file(name)
        do {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: location, to: dest)
            // Not in the iCloud backup: films are big and can be downloaded again.
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var url = dest
            try? url.setResourceValues(values)
            let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? NSNumber)?.int64Value ?? 0
            MainKt.downloads().onDone(id: id, bytes: size)
        } catch {
            MainKt.downloads().onFailed(id: id, message: "Espace insuffisant ou fichier illisible")
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let (id, _) = parse(task) else { return }
        if (error as NSError).code == NSURLErrorCancelled { return }
        MainKt.downloads().onFailed(id: id, message: error.localizedDescription)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.backgroundCompletion?()
            self.backgroundCompletion = nil
        }
    }
}
