import Foundation

/// A film or a live channel of the Xtream account.
struct Item: Identifiable, Hashable {
    let id: Int
    let name: String
    /// Films: the file's extension (mkv, mp4, avi…); channels: empty.
    let ext: String
    let live: Bool
}

/// The Xtream account typed on the iPhone (kept on it only) and its films and channels.
@MainActor
final class Store: ObservableObject {
    @Published var server = UserDefaults.standard.string(forKey: "server") ?? ""
    @Published var username = UserDefaults.standard.string(forKey: "username") ?? ""
    @Published var password = UserDefaults.standard.string(forKey: "password") ?? ""
    @Published var movies: [Item] = []
    @Published var channels: [Item] = []
    @Published var status = ""
    @Published var loading = false

    var base: String {
        var s = server.trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.lowercased().hasPrefix("http") { s = "http://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    private func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s }

    func load() async {
        UserDefaults.standard.set(server, forKey: "server")
        UserDefaults.standard.set(username, forKey: "username")
        UserDefaults.standard.set(password, forKey: "password")
        loading = true
        status = "Chargement…"
        defer { loading = false }
        do {
            async let vod = fetch("get_vod_streams")
            async let live = fetch("get_live_streams")
            movies = try await vod.map { o in
                Item(id: Self.int(o["stream_id"]), name: o["name"] as? String ?? "?", ext: (o["container_extension"] as? String ?? "mp4").lowercased(), live: false)
            }
            channels = try await live.map { o in Item(id: Self.int(o["stream_id"]), name: o["name"] as? String ?? "?", ext: "", live: true) }
            let exts = Dictionary(grouping: movies, by: \.ext).mapValues(\.count).sorted { $0.value > $1.value }.prefix(4)
                .map { "\($0.key) \($0.value)" }.joined(separator: ", ")
            status = "\(movies.count) films (\(exts)) · \(channels.count) chaînes"
        } catch {
            status = "Erreur : \(error.localizedDescription)"
        }
    }

    private func fetch(_ action: String) async throws -> [[String: Any]] {
        guard let url = URL(string: "\(base)/player_api.php?username=\(enc(username))&password=\(enc(password))&action=\(action)") else { throw URLError(.badURL) }
        var req = URLRequest(url: url, timeoutInterval: 90)
        req.setValue("Lumen TV", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: req)
        if let http = response as? HTTPURLResponse, http.statusCode != 200 { throw URLError(.badServerResponse) }
        return (try JSONSerialization.jsonObject(with: data) as? [[String: Any]]) ?? []
    }

    private static func int(_ v: Any?) -> Int {
        if let i = v as? Int { return i }
        if let s = v as? String { return Int(s) ?? 0 }
        return 0
    }

    /// Films: their own extension; channels: TS (as on the TV) or HLS (m3u8), to compare the two engines.
    func url(for item: Item, liveFormat: String) -> URL? {
        let path = item.live ? "live/\(enc(username))/\(enc(password))/\(item.id).\(liveFormat)"
            : "movie/\(enc(username))/\(enc(password))/\(item.id).\(item.ext)"
        return URL(string: "\(base)/\(path)")
    }
}
