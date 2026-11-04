import SwiftUI

/// Which engine plays: "Auto" as another-iptv-player does (AVPlayer for mp4 / mov / m3u8, FFmpeg for the rest,
/// the other one when the first fails), or one forced, to compare.
enum Engine: String, CaseIterable, Identifiable {
    case auto = "Auto", ffmpeg = "FFmpeg", avplayer = "AVPlayer"
    var id: String { rawValue }
}

struct ContentView: View {
    @EnvironmentObject private var store: Store
    @State private var query = ""
    @State private var showLive = false
    @State private var liveFormat = "ts"
    @State private var engine = Engine.auto
    @State private var playing: Item?

    private var results: [Item] {
        let list = showLive ? store.channels : store.movies
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return Array(list.prefix(60)) }
        return Array(list.lazy.filter { $0.name.localizedCaseInsensitiveContains(q) }.prefix(100))
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Compte Xtream (reste sur cet iPhone)") {
                    TextField("Serveur (http://…:port)", text: $store.server).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    TextField("Identifiant", text: $store.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Mot de passe", text: $store.password)
                    Button(store.loading ? "Chargement…" : "Charger les films et chaînes") { Task { await store.load() } }.disabled(store.loading)
                    if !store.status.isEmpty { Text(store.status).font(.footnote).foregroundStyle(.secondary) }
                }
                Section("Lecture") {
                    Picker("Contenu", selection: $showLive) { Text("Films").tag(false); Text("Chaînes").tag(true) }.pickerStyle(.segmented)
                    Picker("Moteur", selection: $engine) { ForEach(Engine.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented)
                    if showLive {
                        Picker("Format du direct", selection: $liveFormat) { Text("TS").tag("ts"); Text("HLS (m3u8)").tag("m3u8") }.pickerStyle(.segmented)
                    }
                    TextField(showLive ? "Rechercher une chaîne" : "Rechercher un film", text: $query).autocorrectionDisabled()
                }
                Section(showLive ? "Chaînes" : "Films") {
                    ForEach(results) { item in
                        Button { playing = item } label: {
                            HStack {
                                Text(item.name).lineLimit(2)
                                Spacer()
                                Text(item.live ? liveFormat.uppercased() : item.ext.uppercased()).font(.caption.monospaced()).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Lumen TV · test iPhone")
        }
        .fullScreenCover(item: $playing) { item in
            if let url = store.url(for: item, liveFormat: liveFormat) {
                PlayerScreen(url: url, title: item.name, engine: engine) { playing = nil }
            }
        }
    }
}
